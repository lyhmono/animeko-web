package com.animeko.web.proxy

import com.animeko.web.appLog
import com.animeko.web.http.UrlGuard
import java.net.URI
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 视频代理的请求头会话。
 *
 * 数据源解析出的 UA/Referer/Cookie 存在这里，代理 URL 只携带一个不可猜的 sid。
 * 早先把凭据直接拼进 `/api/proxy?...&cookie=...`，而 HLS 清单会把这种地址复制到
 * **每一个分片**上：它们会进浏览器历史、反向代理与服务端访问日志。
 *
 * sid 本质是能力令牌，所以它必须绑定用途，否则「拿到 sid」等于「用受害者的
 * Cookie 请求任意主机」：
 * - 会话只对被解析出来的那台主机有效；
 * - 清单改写时，服务端把从清单内容里解析出的分片/密钥主机登记进来（客户端无法自行扩大）。
 *
 * 仅内存：不落盘、不写日志、进程重启即失效（重新解析会拿到新会话）。
 * 因此**只能单副本部署**，多副本负载均衡会让分片随机 410。
 */
class ProxySessionStore(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    /** 便于测试里驱动时间，避免 sleep 断言在负载下抖动 */
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    enum class Kind { GRANTED, EXPIRED, HOST_NOT_ALLOWED }

    class Grant(val kind: Kind, val headers: Map<String, String>)

    private class Entry(
        val headers: Map<String, String>,
        /** 允许接收 Cookie 的主机：初始媒体主机 + 数据源显式声明的额外主机 */
        val cookieHosts: Set<String>,
        /** Cookie 来源的协议范围；HTTPS 凭据不能在新子请求中降级到 HTTP。 */
        val cookieScheme: String,
        /**
         * expiresAt 必须 volatile：它在**读取路径**上被写（滑动续期），而分片由多个
         * Netty worker 线程并发拉取。非 volatile 的 long 允许被拆成两次 32 位写入，
         * 读到半个旧值就可能把一个在用会话误判成过期。
         */
        @Volatile var expiresAt: Long,
    ) {
        val allowedHosts: MutableSet<String> =
            Collections.newSetFromMap(ConcurrentHashMap())
    }

    private val sessions = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()

    /** 登记会话并返回 sid；没有额外头需要携带时返回空串（调用方据此不拼 sid 参数）。 */
    fun put(
        mediaUrl: String,
        userAgent: String,
        referer: String,
        cookie: String,
        /** 数据源显式声明的额外 Cookie 主机；默认只信媒体主机本身 */
        extraCookieHosts: Set<String> = emptySet(),
    ): String {
        val headers = buildMap {
            if (userAgent.isNotBlank()) put("User-Agent", userAgent)
            if (referer.isNotBlank()) put("Referer", referer)
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        if (headers.isEmpty()) return ""
        val headerBytes = headers.entries.sumOf {
            it.key.toByteArray(Charsets.UTF_8).size + it.value.toByteArray(Charsets.UTF_8).size
        }
        // 配置里的 Cookie 理论上可以接近 200KB（单个数据源配置的上限），
        // 而每个数据源解析都会 put 一条会话：不封顶的话几千条会话就是几百 MB 常驻内存。
        // 超过上限的请求头几乎只可能是恶意/损坏的配置，拒绝保存比常驻内存更安全。
        if (headerBytes > MAX_HEADER_BYTES_PER_SESSION) {
            appLog.warn(
                "播放会话的请求头合计 {} 字节，超过 {} 字节上限，已拒绝保存（避免超大 Cookie 常驻内存）",
                headerBytes,
                MAX_HEADER_BYTES_PER_SESSION,
            )
            return ""
        }
        purgeIfNeeded()
        val sid = newId()
        val mediaHost = hostOf(mediaUrl)
        val cookieHosts = buildSet {
            mediaHost?.let { add(it) }
            extraCookieHosts.mapNotNullTo(this) {
                it.trim().lowercase().takeIf(String::isNotBlank)
            }
        }
        val cookieScheme = runCatching { URI(mediaUrl).scheme?.lowercase() }.getOrNull() ?: "https"
        val entry = Entry(headers, cookieHosts, cookieScheme, now() + ttlMs)
        mediaHost?.let { entry.allowedHosts.add(it) }
        sessions[sid] = entry
        return sid
    }

    /** 校验会话是否有效、目标主机能否使用该会话；命中则滑动续期。 */
    fun grant(sid: String, targetUrl: String): Grant {
        if (sid.isEmpty()) return Grant(Kind.GRANTED, emptyMap())
        val entry = sessions[sid] ?: return Grant(Kind.EXPIRED, emptyMap())
        // 过期必须在这里判：purge 只在 put 时触发，若之后没有新会话，过期条目会一直可用
        if (entry.expiresAt < now()) {
            sessions.remove(sid, entry)
            return Grant(Kind.EXPIRED, emptyMap())
        }
        val host = hostOf(targetUrl)
        val targetScheme = runCatching { URI(targetUrl).scheme?.lowercase() }.getOrNull()
        // 主机名认不出来 = 无法判断它是不是当初授权的那台，必须**拒绝带凭据**：
        // 之前写的是 `host != null && host !in allowedHosts`，等于 URI 解析失败时
        // 直接放行并把源站 Cookie 发出去 —— 和下面这行注释正好相反。
        // sid 是能力令牌，必须绑定目标主机：否则拿到一个 sid 就能用受害者的
        // Cookie 去请求攻击者指定的任意地址。
        if (host == null || host !in entry.allowedHosts || targetScheme !in setOf("http", "https")) {
            return Grant(Kind.HOST_NOT_ALLOWED, emptyMap())
        }
        // maxOf 保证续期只会往后推：线程在两次 now() 之间被挂起时，
        // 直接赋值会把过期点提前，把正在播放的会话踢掉
        entry.expiresAt = maxOf(entry.expiresAt, now() + ttlMs)
        // 源站 Cookie 只发给媒体主机，以及数据源显式声明的额外主机。清单里自动
        // 追加的 CDN 主机仍需要 UA/Referer 过防盗链，但没有理由把源站 Cookie
        // 也交给它们 —— 一份被篡改的清单就能借此把用户的源站凭据引到第三方。
        val cookieAllowed = host in entry.cookieHosts && !(entry.cookieScheme == "https" && targetScheme == "http")
        val headers = if (cookieAllowed) entry.headers else entry.headers - "Cookie"
        return Grant(Kind.GRANTED, headers)
    }

    /**
     * 登记一批由 [com.animeko.web.proxy.HlsRewriter.rewriteDetailed] 真实改写出的主机。
     *
     * 必须设上界：清单体积上限是 4MB，理论上能塞进上万个**不同**主机，而这个集合
     * 只会随会话滑动续期一直长大（真实清单只有 1~3 个主机）。超限后不再扩大：
     * 后面的分片会以 HOST_NOT_ALLOWED 失败，是可诊断的错，比静默 OOM 好。
     */
    fun allowHosts(sid: String, hosts: Collection<String>) {
        if (sid.isEmpty() || hosts.isEmpty()) return
        val entry = sessions[sid] ?: return
        var added = 0
        for (host in hosts) {
            if (host.isBlank()) continue
            if (entry.allowedHosts.size >= MAX_HOSTS_PER_SESSION) {
                appLog.warn(
                    "清单的主机数超过单会话上限 {}，后续分片会被拒（sid 已登记 {} 个主机）",
                    MAX_HOSTS_PER_SESSION,
                    entry.allowedHosts.size,
                )
                break
            }
            if (entry.allowedHosts.add(host)) added++
        }
        if (added > 0) appLog.debug("会话登记了 {} 个清单主机", added)
    }

    /**
     * 从改写后的清单文本里登记主机。生产路径已经直接使用
     * [com.animeko.web.proxy.HlsRewriter.rewriteDetailed] 返回的主机集合；
     * 这里保留给调用方/测试，但**只认真正的 URI 位置**：
     * 分片行本身，或标签里的 `URI="..."` 属性。旧实现用正则扫描全文，
     * 评论行里随便写一个代理地址就能把任意主机加进白名单。
     */
    internal fun allowPlaylistHosts(sid: String, rewrittenPlaylist: String) {
        if (sid.isEmpty()) return
        val hosts = LinkedHashSet<String>()
        for (rawLine in rewrittenPlaylist.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith(PROXY_URL_PREFIX)) {
                proxyUrlHost(line)?.let(hosts::add)
            } else if (line.startsWith("#")) {
                URI_ATTR.findAll(line).forEach { m ->
                    val value = m.groupValues[1]
                    if (value.startsWith(PROXY_URL_PREFIX)) proxyUrlHost(value)?.let(hosts::add)
                }
            }
        }
        allowHosts(sid, hosts)
    }

    private fun proxyUrlHost(proxyUrl: String): String? {
        val rest = proxyUrl.substringAfter(PROXY_URL_PREFIX, "")
        if (rest.isEmpty()) return null
        val encoded = rest.substringBefore('&').substringBefore('"').substringBefore(' ')
        if (encoded.isEmpty()) return null
        return runCatching { hostOf(java.net.URLDecoder.decode(encoded, "UTF-8")) }.getOrNull()
    }

    val size: Int get() = sessions.size

    private fun newId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // 和 UrlGuard 共用同一套主机名提取：两边算法不一致时，会出现
    // 「守卫按 A 主机放行、会话按 B 主机绑定」的错位；下划线主机名还会让
    // URI.host 直接返回 null。
    private fun hostOf(url: String): String? = UrlGuard.hostOf(url)

    private fun purgeIfNeeded() {
        if (sessions.size < maxEntries) return
        synchronized(this) {
            if (sessions.size < maxEntries) return
            val nowMs = now()
            sessions.entries.removeIf { it.value.expiresAt < nowMs }
            if (sessions.size >= maxEntries) {
                // 仍超限（大量在用会话）：按最早过期淘汰到一半，保证内存有上界
                sessions.entries
                    .sortedBy { it.value.expiresAt }
                    .take(sessions.size - maxEntries / 2)
                    .forEach { sessions.remove(it.key) }
            }
        }
    }

    private companion object {
        const val PROXY_URL_PREFIX = "/api/proxy?url="
        val URI_ATTR = Regex("""URI="([^"]+)"""")

        /** 6 小时空闲即失效；正在播放的请求会不断续期 */
        private const val DEFAULT_TTL_MS = 6L * 60 * 60 * 1000

        private const val DEFAULT_MAX_ENTRIES = 1024

        /**
         * 单会话请求头总字节上限（含 UA/Referer/Cookie）。
         * 正常源站 Cookie 在 4KB 以内；16KB 已足够宽松，又能把最坏内存压到 16MB 量级。
         */
        private const val MAX_HEADER_BYTES_PER_SESSION = 16 * 1024

        /** 单个会话最多登记多少个清单主机（真实清单是 1~3 个） */
        private const val MAX_HOSTS_PER_SESSION = 64
    }
}

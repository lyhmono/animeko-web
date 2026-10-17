package com.animeko.web.proxy

import com.animeko.web.appLog
import com.animeko.web.http.UrlGuard
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
    fun put(mediaUrl: String, userAgent: String, referer: String, cookie: String): String {
        val headers = buildMap {
            if (userAgent.isNotBlank()) put("User-Agent", userAgent)
            if (referer.isNotBlank()) put("Referer", referer)
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        if (headers.isEmpty()) return ""
        purgeIfNeeded()
        val sid = newId()
        val entry = Entry(headers, now() + ttlMs)
        hostOf(mediaUrl)?.let { entry.allowedHosts.add(it) }
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
        // 主机名认不出来 = 无法判断它是不是当初授权的那台，必须**拒绝带凭据**：
        // 之前写的是 `host != null && host !in allowedHosts`，等于 URI 解析失败时
        // 直接放行并把源站 Cookie 发出去 —— 和下面这行注释正好相反。
        // sid 是能力令牌，必须绑定目标主机：否则拿到一个 sid 就能用受害者的
        // Cookie 去请求攻击者指定的任意地址。
        if (host == null || host !in entry.allowedHosts) return Grant(Kind.HOST_NOT_ALLOWED, emptyMap())
        // maxOf 保证续期只会往后推：线程在两次 now() 之间被挂起时，
        // 直接赋值会把过期点提前，把正在播放的会话踢掉
        entry.expiresAt = maxOf(entry.expiresAt, now() + ttlMs)
        return Grant(Kind.GRANTED, entry.headers)
    }

    /** 清单改写完成后，把里面出现的分片/密钥主机登记为该会话可用主机。 */
    /**
     * 登记清单里出现的分片/密钥主机。
     *
     * 必须设上界：清单体积上限是 4MB，理论上能塞进上万个**不同**主机，而这个集合
     * 只会随会话滑动续期一直长大（真实清单只有 1~3 个主机）。不设限等于让一个
     * 恶意数据源用一份清单把服务端内存吃掉几十 MB，并且每次重拉清单都要把 4MB
     * 全量正则 + 逐条 URL 解码一遍。
     * 超限后不再扩大：后面的分片会以 HOST_NOT_ALLOWED 失败，是可诊断的错，
     * 比静默 OOM 好。
     */
    fun allowPlaylistHosts(sid: String, rewrittenPlaylist: String) {
        if (sid.isEmpty()) return
        val entry = sessions[sid] ?: return
        var added = 0
        for (encoded in PROXY_URL_PARAM.findAll(rewrittenPlaylist)) {
            if (entry.allowedHosts.size >= MAX_HOSTS_PER_SESSION) {
                appLog.warn(
                    "清单的主机数超过单会话上限 {}，后续分片会被拒（sid 已登记 {} 个主机）",
                    MAX_HOSTS_PER_SESSION,
                    entry.allowedHosts.size,
                )
                return
            }
            val host = runCatching { hostOf(java.net.URLDecoder.decode(encoded.groupValues[1], "UTF-8")) }
                .getOrNull()
            if (host != null && entry.allowedHosts.add(host)) added++
        }
        if (added > 0) appLog.debug("会话登记了 {} 个清单主机", added)
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
        val PROXY_URL_PARAM = Regex("""/api/proxy\?url=([^&"\s]+)""")

        /** 6 小时空闲即失效；正在播放的请求会不断续期 */
        private const val DEFAULT_TTL_MS = 6L * 60 * 60 * 1000

        private const val DEFAULT_MAX_ENTRIES = 4096

        /** 单个会话最多登记多少个清单主机（真实清单是 1~3 个） */
        private const val MAX_HOSTS_PER_SESSION = 64
    }
}

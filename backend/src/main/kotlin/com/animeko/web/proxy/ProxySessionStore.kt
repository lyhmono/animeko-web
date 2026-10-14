package com.animeko.web.proxy

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 视频代理的请求头会话。
 *
 * 数据源解析出的 UA/Referer/Cookie 存在这里，代理 URL 只携带一个不可猜的 sid。
 * 早先把凭据直接拼进 `/api/proxy?...&cookie=...`，而 HLS 清单会把这种地址复制到
 * **每一个分片**上：它们会进浏览器历史、反向代理与服务端访问日志，泄露面随集数线性增长。
 *
 * 仅内存：不落盘、不写日志、进程重启即失效（重新解析会拿到新会话）。
 */
class ProxySessionStore(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    private class Entry(val headers: Map<String, String>, var expiresAt: Long)

    /** sid -> 请求头。命中时续期，因此连续播放不会因为超时被掐断。 */
    private val sessions = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()

    fun put(userAgent: String, referer: String, cookie: String): String {
        purgeIfNeeded()
        val headers = buildMap {
            if (userAgent.isNotBlank()) put("User-Agent", userAgent)
            if (referer.isNotBlank()) put("Referer", referer)
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        // 全空就不必占一个槽位：代理按无额外头处理即可
        if (headers.isEmpty()) return ""
        val sid = newId()
        sessions[sid] = Entry(headers, System.currentTimeMillis() + ttlMs)
        return sid
    }

    /** 返回 null 表示会话不存在或已过期，调用方据此区分「过期」与「本来就没有额外头」。 */
    fun headers(sid: String): Map<String, String>? {
        if (sid.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis()
        val entry = sessions[sid] ?: return null
        if (entry.expiresAt < now) {
            sessions.remove(sid, entry)
            return null
        }
        entry.expiresAt = now + ttlMs
        return entry.headers
    }

    val size: Int get() = sessions.size

    private fun newId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun purgeIfNeeded() {
        if (sessions.size < maxEntries) return
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { it.value.expiresAt < now }
        if (sessions.size < maxEntries) return
        // 仍然超限（大量活跃会话）：按最早过期淘汰，保证内存有上界而不是无限膨胀
        sessions.entries.sortedBy { it.value.expiresAt }
            .take(sessions.size - maxEntries / 2)
            .forEach { sessions.remove(it.key) }
    }

    companion object {
        /** 6 小时空闲即失效；正在播放的请求会不断续期 */
        private const val DEFAULT_TTL_MS = 6L * 60 * 60 * 1000

        private const val DEFAULT_MAX_ENTRIES = 4096
    }
}

package com.animeko.web.auth

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 站点登录会话：单共享账号（个人自用），凭据来自 ANIMEKO_AUTH_USER/PASSWORD 或 ANIMEKO_AUTH_TOKEN。
 *
 * 用会话 Cookie 而不是让前端带 Authorization 头，是因为 <video>、<img>、hls.js 的分片请求
 * 都没法加自定义请求头 —— 只有 Cookie 会让浏览器自动带到这些子资源上。
 *
 * 仅内存：容器重启后需要重新登录（个人自用可接受）；也因此只能单副本部署。
 */
class SessionStore(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private class Entry(@Volatile var expiresAt: Long)

    private val sessions = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()

    fun create(): String {
        purgeIfNeeded()
        val token = newToken()
        sessions[token] = Entry(now() + ttlMs)
        return token
    }

    /** 校验并滑动续期。 */
    fun isValid(token: String): Boolean {
        if (token.isEmpty()) return false
        val entry = sessions[token] ?: return false
        val nowMs = now()
        if (entry.expiresAt < nowMs) {
            sessions.remove(token, entry)
            return false
        }
        entry.expiresAt = maxOf(entry.expiresAt, nowMs + ttlMs)
        return true
    }

    fun invalidate(token: String) {
        if (token.isNotEmpty()) sessions.remove(token)
    }

    /** Cookie 的 Max-Age 用，避免会话寿命和 Cookie 寿命各写一处对不上。 */
    fun ttlSeconds(): Long = (ttlMs / 1000).coerceAtLeast(1)

    val size: Int get() = sessions.size

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun purgeIfNeeded() {
        if (sessions.size < maxEntries) return
        synchronized(this) {
            if (sessions.size < maxEntries) return
            val nowMs = now()
            sessions.entries.removeIf { it.value.expiresAt < nowMs }
            if (sessions.size >= maxEntries) {
                sessions.entries
                    .sortedBy { it.value.expiresAt }
                    .take(sessions.size - maxEntries / 2)
                    .forEach { sessions.remove(it.key) }
            }
        }
    }

    private companion object {
        /** 30 天不活动才失效；每次请求都会滑动续期 */
        private const val DEFAULT_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val DEFAULT_MAX_ENTRIES = 1024
    }
}

/**
 * 登录失败限速。公网 VPS 上扫描器几小时内就会来试口令，
 * 只靠口令强度不够，这里按客户端 IP 做固定窗口计数。
 */
class LoginRateLimiter(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private class Bucket(val windowStart: Long, @Volatile var count: Int)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** 该客户端当前是否被限流。 */
    fun blocked(key: String): Boolean {
        val nowMs = now()
        val bucket = buckets[key] ?: return false
        if (bucket.windowStart + windowMs < nowMs) {
            buckets.remove(key, bucket)
            return false
        }
        // 扫描器会轮换源 IP，每个 IP 留一个 Bucket：只在 onFailure 里清理
        // 意味着这个 map 会随扫描流量单调增长，而 blocked() 才是被调用得最多的入口
        evictExpired(nowMs)
        return bucket.count >= maxAttempts
    }

    fun onFailure(key: String) {
        val nowMs = now()
        buckets.compute(key) { _, existing ->
            if (existing == null || existing.windowStart + windowMs < nowMs) {
                Bucket(nowMs, 1)
            } else {
                existing.count += 1
                existing
            }
        }
        evictExpired(nowMs)
    }

    private fun evictExpired(nowMs: Long) {
        if (buckets.size <= MAX_BUCKETS) return
        val cutoff = nowMs - windowMs
        buckets.entries.removeIf { it.value.windowStart < cutoff }
        if (buckets.size > MAX_BUCKETS) {
            // 清理完还是超量（攻击仍在进行）：丢掉最老的一半，保证这个 map 有上界
            buckets.entries
                .sortedBy { it.value.windowStart }
                .take(buckets.size - MAX_BUCKETS / 2)
                .forEach { buckets.remove(it.key) }
        }
    }

    fun onSuccess(key: String) {
        buckets.remove(key)
    }

    /** 当前窗口内已失败几次，供日志排查（不做凭据探测反馈）。 */
    fun attempts(key: String): Int {
        val bucket = buckets[key] ?: return 0
        if (bucket.windowStart + windowMs < now()) return 0
        return bucket.count
    }

    /** 当前记账的客户端数量（只用于验证它有上界）。 */
    val trackedClients: Int get() = buckets.size

    /** Retry-After 用：固定窗口剩余时间不会超过 windowMs。 */
    fun windowSeconds(): Long = (windowMs / 1000).coerceAtLeast(1)

    private companion object {
        private const val DEFAULT_MAX_ATTEMPTS = 8
        private const val DEFAULT_WINDOW_MS = 5L * 60 * 1000
        private const val MAX_BUCKETS = 4096
    }
}

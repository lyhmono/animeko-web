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

    private class Entry(@Volatile var expiresAt: Long, var cookieRenewedAt: Long)

    private val sessions = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()

    fun create(): String {
        purgeIfNeeded()
        val token = newToken()
        val nowMs = now()
        sessions[token] = Entry(nowMs + ttlMs, nowMs)
        return token
    }

    /** 校验并滑动续期。 */
    fun isValid(token: String): Boolean {
        if (token.isEmpty()) return false
        val entry = sessions[token] ?: return false
        synchronized(entry) {
            val nowMs = now()
            if (entry.expiresAt <= nowMs) {
                sessions.remove(token, entry)
                return false
            }
            entry.expiresAt = maxOf(entry.expiresAt, nowMs + ttlMs)
            return true
        }
    }

    /** 最多每日（短 TTL 时半个窗口）刷新一次浏览器 Cookie，而不是每个分片都发。 */
    fun claimCookieRenewal(token: String): Boolean {
        val entry = sessions[token] ?: return false
        synchronized(entry) {
            val nowMs = now()
            if (entry.expiresAt <= nowMs) return false
            val interval = minOf(ttlMs / 2, 24L * 60 * 60 * 1000).coerceAtLeast(1)
            if (nowMs - entry.cookieRenewedAt < interval) return false
            entry.cookieRenewedAt = nowMs
            return true
        }
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
 * 未验证的配置凭据限速：比较之前原子取得许可，成功/失败都消耗同一窗口预算。
 * 已验证的随机会话由认证入口直接放行，不经过口令桶。
 */
class LoginRateLimiter(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private class Bucket(val windowStart: Long, @Volatile var count: Int)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** 检查和记账是同一个临界区，不能用 blocked() + onFailure() 作许可。 */
    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val nowMs = now()
        evictExpired(nowMs)
        var bucket = buckets[key]
        if (bucket != null && nowMs - bucket.windowStart >= windowMs) {
            buckets.remove(key)
            bucket = null
        }
        if (bucket == null) {
            // 饱和时拒绝新来源，而不是淘汰仍受限的桶并重新开放其猜测预算。
            if (buckets.size >= MAX_BUCKETS) return false
            buckets[key] = Bucket(nowMs, 1)
            return true
        }
        if (bucket.count >= maxAttempts) return false
        bucket.count++
        return true
    }

    /** 该客户端当前是否被限流。 */
    fun blocked(key: String): Boolean {
        val nowMs = now()
        val bucket = buckets[key] ?: return false
        if (nowMs - bucket.windowStart >= windowMs) {
            buckets.remove(key, bucket)
            return false
        }
        evictExpired(nowMs)
        return bucket.count >= maxAttempts
    }

    fun onFailure(key: String) {
        // 兼容直接记账的调用者；生产认证只调用 tryAcquire，并在获准后验密。
        tryAcquire(key)
    }

    private fun evictExpired(nowMs: Long) {
        if (buckets.size < MAX_BUCKETS) return
        buckets.entries.removeIf { nowMs - it.value.windowStart >= windowMs }
    }

    @Synchronized
    fun onSuccess(key: String) {
        buckets.remove(key)
    }

    /** 当前窗口内获得许可的尝试数（不对客户端反馈候选真假）。 */
    fun attempts(key: String): Int {
        val bucket = buckets[key] ?: return 0
        if (now() - bucket.windowStart >= windowMs) return 0
        return bucket.count
    }

    /** 当前记账的客户端数量（只用于验证它有上界）。 */
    val trackedClients: Int get() = buckets.size

    /** Retry-After 用：固定窗口长度的上界（没有 bucket 时的兜底）。 */
    fun windowSeconds(): Long = (windowMs / 1000).coerceAtLeast(1)

    /**
     * Retry-After 的真实剩余秒数。旧实现固定回 300，哪怕只剩 1 秒也让客户端等满 5 分钟。
     * 向上取整，最少 1 秒。
     */
    fun retryAfterSeconds(key: String): Long {
        val bucket = buckets[key] ?: return windowSeconds()
        val remainingMs = bucket.windowStart + windowMs - now()
        if (remainingMs <= 0) return 1
        return ((remainingMs + 999) / 1000).coerceAtLeast(1)
    }

    private companion object {
        private const val DEFAULT_MAX_ATTEMPTS = 8
        private const val DEFAULT_WINDOW_MS = 5L * 60 * 1000
        private const val MAX_BUCKETS = 4096
    }
}

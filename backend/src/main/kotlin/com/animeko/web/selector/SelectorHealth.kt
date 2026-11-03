package com.animeko.web.selector

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 数据源健康指标：只记录「哪个源、成功/失败、耗时、最近一次错误」，
 * 不记录搜索词、播放地址或任何用户内容。
 */
data class SourceHealth(
    val success: Long,
    val failure: Long,
    val totalMs: Long,
    val lastError: String?,
    val lastAt: Long,
) {
    val avgMs: Long get() = if (success + failure == 0L) 0L else totalMs / (success + failure)
}

object SelectorHealth {
    private class Mutable {
        val success = AtomicLong()
        val failure = AtomicLong()
        val totalMs = AtomicLong()
        @Volatile var lastError: String? = null
        @Volatile var lastAt: Long = 0L
    }

    private val sources = ConcurrentHashMap<String, Mutable>()

    suspend fun <T> record(name: String, block: suspend () -> T): T {
        val m = sources.computeIfAbsent(name) { Mutable() }
        val started = System.nanoTime()
        return try {
            block().also {
                m.success.incrementAndGet()
                m.totalMs.addAndGet((System.nanoTime() - started) / 1_000_000)
                m.lastAt = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            m.failure.incrementAndGet()
            m.totalMs.addAndGet((System.nanoTime() - started) / 1_000_000)
            m.lastError = e.message?.take(120)
            m.lastAt = System.currentTimeMillis()
            throw e
        }
    }

    fun snapshot(): Map<String, SourceHealth> = sources.mapValues { (_, m) ->
        SourceHealth(m.success.get(), m.failure.get(), m.totalMs.get(), m.lastError, m.lastAt)
    }
}
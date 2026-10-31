package com.animeko.web.selector

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Cancellable source-local admission. A waiting caller never books a future start time. */
internal class SourceThrottle(private val nowMillis: () -> Long) {
    private class Entry {
        val mutex = Mutex()
        var lastStarted: Long? = null
        var lastTouched: Long = 0
        var callers: Int = 0
    }
    private val entries = LinkedHashMap<String, Entry>()

    suspend fun await(key: String, interval: Long) {
        if (interval <= 0) return
        currentCoroutineContext().ensureActive()
        val entry = synchronized(entries) {
            val now = nowMillis()
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next().value
                if (item.callers == 0 && now - item.lastTouched >= IDLE_EXPIRY_MS) iterator.remove()
            }
            if (key !in entries && entries.size >= MAX_SOURCES) throw SelectorException("数据源限速队列繁忙，请稍后重试")
            val item = entries.getOrPut(key) { Entry() }
            if (item.callers >= MAX_CALLERS_PER_SOURCE) throw SelectorException("这个数据源等待请求过多，请稍后重试")
            item.callers++
            item.lastTouched = now
            item
        }
        try {
            entry.mutex.withLock {
                currentCoroutineContext().ensureActive()
                val last = entry.lastStarted
                val wait = if (last == null) 0 else (interval - (nowMillis() - last).coerceAtLeast(0)).coerceAtLeast(0)
                if (wait > 0) delay(wait)
                currentCoroutineContext().ensureActive()
                entry.lastStarted = nowMillis()
            }
        } finally {
            synchronized(entries) {
                entry.callers--
                entry.lastTouched = nowMillis()
            }
        }
    }

    companion object {
        private const val MAX_SOURCES = 1_024
        private const val MAX_CALLERS_PER_SOURCE = 64
        private const val IDLE_EXPIRY_MS = 30_000L
    }
}

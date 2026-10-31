package com.animeko.web.selector

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** In-flight only: completion/failure removes the entry, and the last departing waiter cancels it. */
internal class SingleFlight<K, V>(private val capacity: Int = 128) {
    private class Entry<V>(val scope: CoroutineScope, val task: Deferred<V>, var waiters: Int)
    private val entries = mutableMapOf<K, Entry<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        val context = currentCoroutineContext()
        context.ensureActive()
        val entry = synchronized(entries) {
            val existing = entries[key]
            if (existing != null && !existing.task.isCompleted) {
                existing.waiters++
                existing
            } else if (entries.size >= capacity) {
                null // Bypass coalescing instead of allocating an unbounded detached task map.
            } else {
                val scope = CoroutineScope(context.minusKey(Job) + SupervisorJob())
                val task = scope.async(start = CoroutineStart.LAZY) { block() }
                val created = Entry(scope, task, 1)
                entries[key] = created
                task.invokeOnCompletion {
                    synchronized(entries) { if (entries[key] === created) entries.remove(key) }
                    scope.cancel()
                }
                created
            }
        } ?: return block()
        try {
            return entry.task.await()
        } finally {
            synchronized(entries) {
                entry.waiters--
                if (entry.waiters == 0 && !entry.task.isCompleted) {
                    if (entries[key] === entry) entries.remove(key)
                    entry.scope.cancel()
                }
            }
        }
    }
}

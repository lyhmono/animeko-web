package com.animeko.web.bangumi

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Only active requests are retained; each flight owns a job that ends with its task or last waiter. */
internal class SingleFlight<K, V> {
    private class Flight<V>(
        val lifetime: CompletableJob,
        val result: Deferred<V>,
        var waiters: Int,
    )

    private val lock = Any()
    private val flights = HashMap<K, Flight<V>>()

    suspend fun run(key: K, request: suspend () -> V): V {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val flight = synchronized(lock) {
            val existing = flights[key]
            if (existing != null && !existing.result.isCompleted) {
                existing.waiters++
                existing
            } else {
                // A caller owns its wait, not the shared work. Do not retain a client-wide scope.
                val lifetime = SupervisorJob()
                val result = CoroutineScope(caller.minusKey(Job) + lifetime).async(start = CoroutineStart.LAZY) { request() }
                val created = Flight(lifetime, result, 1)
                flights[key] = created
                result.invokeOnCompletion {
                    synchronized(lock) {
                        if (flights[key] === created) flights.remove(key)
                    }
                    lifetime.complete()
                }
                created
            }
        }
        try {
            flight.result.start()
            return flight.result.await()
        } finally {
            val abandoned = synchronized(lock) {
                flight.waiters--
                if (flight.waiters == 0 && !flight.result.isCompleted) {
                    if (flights[key] === flight) flights.remove(key)
                    true
                } else {
                    false
                }
            }
            if (abandoned) {
                flight.lifetime.cancel()
                withContext(NonCancellable) { flight.result.join() }
            }
        }
    }
}

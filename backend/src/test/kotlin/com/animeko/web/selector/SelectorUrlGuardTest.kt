package com.animeko.web.selector

import com.animeko.web.http.UrlGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SelectorUrlGuardTest {
    @Test
    fun `a deadline releases the caller even when DNS ignores interruption`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val guard = SelectorUrlGuard(lookup = { url, _ ->
            active.incrementAndGet()
            entered.complete(Unit)
            try {
                while (true) {
                    try { release.await(); break } catch (_: InterruptedException) { /* mimic native DNS */ }
                }
                url
            } finally { active.decrementAndGet() }
        })
        try {
            val caller = async { withTimeoutOrNull(100) { guard.requireAllowed("https://fixture.test", false) } }
            entered.await()
            assertNull(caller.await())
            assertEquals(1, active.get(), "caller completed without joining the stalled DNS worker")
        } finally {
            release.countDown()
            guard.close()
        }
    }

    @Test
    fun `DNS work and queue are bounded and cancelled queued work is removed`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val guard = SelectorUrlGuard(lookup = { url, _ ->
            entered.complete(Unit)
            while (true) {
                try { release.await(); break } catch (_: InterruptedException) { }
            }
            url
        }, workers = 1, queueSize = 1)
        try {
            val first = async { guard.requireAllowed("https://first.test", false) }
            entered.await()
            val queued = async { guard.requireAllowed("https://queued.test", false) }
            yield()
            assertFailsWith<SelectorException> { guard.requireAllowed("https://overflow.test", false) }
            queued.cancelAndJoin()
            val replacement = async { guard.requireAllowed("https://replacement.test", false) }
            yield()
            replacement.cancelAndJoin()
            first.cancelAndJoin()
        } finally {
            release.countDown()
            guard.close()
        }
    }

    @Test
    fun `the real guard still rejects literal private targets`() = runBlocking {
        SelectorUrlGuard().use { guard ->
            assertFailsWith<UrlGuard.Blocked> { guard.requireAllowed("http://127.0.0.1/x", false) }
            assertEquals("http://127.0.0.1/x", guard.requireAllowed("http://127.0.0.1/x", true))
        }
    }
}

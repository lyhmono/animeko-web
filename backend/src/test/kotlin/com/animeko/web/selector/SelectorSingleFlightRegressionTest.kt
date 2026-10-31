package com.animeko.web.selector

import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SelectorSingleFlightRegressionTest {
    private val base = "https://flight.test"
    private val cfg = SelectorSearchConfig(
        searchUrl = "$base/search?q={keyword}", requestInterval = 0,
        selectorSubjectFormatA = SubjectFormatA.Config(selectLists = "a"),
    )

    @Test
    fun `concurrent identical processed searches use one upstream exchange`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val ordinary = StubFetcher(mapOf("$base/search" to """<a href="/detail/1">A</a>"""))
        val fetcher = object : Fetcher by ordinary {
            override suspend fun fetchBounded(url: String, headers: Map<String, String>, maxBytes: Long): HttpResult {
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
                return ordinary.fetchBounded(url, headers, maxBytes)
            }
        }
        val engine = selectorTestEngine(fetcher)
        val first = async { engine.search(cfg, "same") }
        started.await()
        val second = async { engine.search(cfg, "same!") } // keyword normalization yields the same key
        runCurrent()
        release.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, calls.get())
        engine.search(cfg, "different")
        assertEquals(2, calls.get(), "different keywords do not coalesce or use a TTL cache")
        engine.search(cfg.copy(userAgent = "changed"), "same")
        assertEquals(3, calls.get(), "changed configs must not reuse a previous flight")
    }

    @Test
    fun `cancelling one search waiter does not cancel a remaining waiter`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val ordinary = StubFetcher(mapOf("$base/search" to """<a href="/detail/1">A</a>"""))
        val fetcher = object : Fetcher by ordinary {
            override suspend fun fetchBounded(url: String, headers: Map<String, String>, maxBytes: Long): HttpResult {
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
                return ordinary.fetchBounded(url, headers, maxBytes)
            }
        }
        val engine = selectorTestEngine(fetcher)
        val first = async { engine.search(cfg, "same") }
        started.await()
        val second = async { engine.search(cfg, "same") }
        runCurrent()
        first.cancelAndJoin()
        release.complete(Unit)
        assertEquals("A", second.await().single().name)
        assertEquals(1, calls.get(), "one waiter must not cancel/restart the shared request")
    }

    @Test
    fun `all departing waiters cancel the shared upstream work`() = runTest {
        val started = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val ordinary = StubFetcher(emptyMap())
        val fetcher = object : Fetcher by ordinary {
            override suspend fun fetchBounded(url: String, headers: Map<String, String>, maxBytes: Long): HttpResult {
                started.complete(Unit)
                try { kotlinx.coroutines.awaitCancellation() } finally { closed.complete(Unit) }
            }
        }
        val engine = selectorTestEngine(fetcher)
        val first = async { engine.search(cfg, "same") }
        started.await()
        val second = async { engine.search(cfg, "same") }
        runCurrent()
        first.cancelAndJoin()
        kotlin.test.assertFalse(closed.isCompleted)
        second.cancelAndJoin()
        closed.await()
    }

    @Test
    fun `explicit refresh bypasses an existing flight`() = runTest {
        val started = CompletableDeferred<Unit>()
        val twiceStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val ordinary = StubFetcher(mapOf("$base/search" to """<a href="/a">A</a>"""))
        val fetcher = object : Fetcher by ordinary {
            override suspend fun fetchBounded(url: String, headers: Map<String, String>, maxBytes: Long): HttpResult {
                if (calls.incrementAndGet() == 1) started.complete(Unit) else twiceStarted.complete(Unit)
                release.await()
                return ordinary.fetchBounded(url, headers, maxBytes)
            }
        }
        val engine = selectorTestEngine(fetcher)
        val first = async { engine.search(cfg, "same") }
        started.await()
        val fresh = async { engine.searchFresh(cfg, "same") }
        twiceStarted.await()
        release.complete(Unit)
        assertEquals(first.await(), fresh.await())
        assertEquals(2, calls.get())
    }

    @Test
    fun `changed configs do not join an existing flight`() = runTest {
        val started = CompletableDeferred<Unit>()
        val twiceStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val ordinary = StubFetcher(mapOf("$base/search" to """<a href="/a">A</a>"""))
        val fetcher = object : Fetcher by ordinary {
            override suspend fun fetchBounded(url: String, headers: Map<String, String>, maxBytes: Long): HttpResult {
                if (calls.incrementAndGet() == 1) started.complete(Unit) else twiceStarted.complete(Unit)
                release.await()
                return ordinary.fetchBounded(url, headers, maxBytes)
            }
        }
        val engine = selectorTestEngine(fetcher)
        val first = async { engine.search(cfg, "same") }
        started.await()
        val changed = async { engine.search(cfg.copy(userAgent = "revision-2"), "same") }
        twiceStarted.await()
        release.complete(Unit)
        first.await()
        changed.await()
        assertEquals(2, calls.get())
    }

    @Test
    fun `failed flights are removed before a later retry`() = runTest {
        val calls = AtomicInteger()
        val ordinary = StubFetcher(mapOf("$base/search" to """<a href="/a">A</a>"""))
        val fetcher = object : Fetcher by ordinary {
            override suspend fun fetchBounded(url: String, headers: Map<String, String>, maxBytes: Long): HttpResult {
                if (calls.incrementAndGet() == 1) throw com.animeko.web.http.UpstreamException("fixture failed")
                return ordinary.fetchBounded(url, headers, maxBytes)
            }
        }
        val engine = selectorTestEngine(fetcher)
        kotlin.test.assertFailsWith<com.animeko.web.http.UpstreamException> { engine.search(cfg, "same") }
        assertEquals("A", engine.search(cfg, "same").single().name)
        assertEquals(2, calls.get())
    }
}

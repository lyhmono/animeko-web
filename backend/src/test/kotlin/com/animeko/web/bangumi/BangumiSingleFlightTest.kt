package com.animeko.web.bangumi

import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class BangumiSingleFlightTest {
    private class FlightFetcher(private val respond: suspend (url: String, body: String?) -> HttpResult) : Fetcher {
        val calls = mutableListOf<Pair<String, String?>>()
        override suspend fun fetch(url: String, headers: Map<String, String>, followRedirects: Boolean): HttpResult {
            calls += url to null
            return respond(url, null)
        }
        override suspend fun post(url: String, body: String, headers: Map<String, String>, contentType: String): HttpResult {
            calls += url to body
            return respond(url, body)
        }
        override suspend fun fetchStream(url: String, headers: Map<String, String>, followRedirects: Boolean): StreamResult =
            error("no real upstream or media request is allowed in this test")
    }

    private fun reply(body: String = """{"data":[{"id":7}]}""") = HttpResult(200, emptyMap(), body.toByteArray())

    @Test
    fun `simultaneous identical searches share only the in-flight task`() = runTest {
        val release = CompletableDeferred<Unit>()
        val fetcher = FlightFetcher { _, _ -> release.await(); reply() }
        val client = BangumiClient(fetcher, listOf("https://fixture.test"))
        val first = async { client.search("same", type = 2, limit = 24) }
        runCurrent()
        val second = async { client.search("same", type = 2, limit = 24) }
        runCurrent()
        try {
            assertEquals(1, fetcher.calls.size, "a second waiter must reuse the active upstream task")
        } finally {
            release.complete(Unit)
        }
        assertEquals(first.await(), second.await())

        client.search("same", type = 2, limit = 24)
        assertEquals(2, fetcher.calls.size, "a completed request must not become an implicit response cache")
    }

    @Test
    fun `cancelling the creating caller does not cancel another waiter`() = runTest {
        val release = CompletableDeferred<Unit>()
        var finished = 0
        val fetcher = FlightFetcher { _, _ ->
            try {
                release.await()
                reply()
            } finally {
                finished++
            }
        }
        val client = BangumiClient(fetcher, listOf("https://fixture.test"))
        val first = async { client.search("same") }
        runCurrent()
        val second = async { client.search("same") }
        runCurrent()
        try {
            assertEquals(1, fetcher.calls.size)
            first.cancelAndJoin()
            assertEquals(0, finished, "the shared task must outlive its cancelled creator")
            assertFalse(second.isCompleted)
        } finally {
            release.complete(Unit)
        }
        assertEquals(7, second.await().single().id)
        assertEquals(1, finished)
    }

    @Test
    fun `the last cancelled waiter stops the shared task and permits a fresh retry`() = runTest {
        val release = CompletableDeferred<Unit>()
        var active = 0
        val fetcher = FlightFetcher { _, _ ->
            active++
            try {
                release.await()
                reply()
            } finally {
                active--
            }
        }
        val client = BangumiClient(fetcher, listOf("https://fixture.test"))
        val first = async { client.search("same") }
        runCurrent()
        val second = async { client.search("same") }
        runCurrent()
        try {
            assertEquals(1, fetcher.calls.size)
            first.cancelAndJoin()
            assertEquals(1, active)
            second.cancelAndJoin()
            assertEquals(0, active, "no background task may survive without a waiter")
            val retry = async { client.search("same") }
            runCurrent()
            assertEquals(2, fetcher.calls.size, "an abandoned flight must be removed before retry")
            release.complete(Unit)
            assertEquals(7, retry.await().single().id)
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `search keys distinguish the complete keyword type and limit payload`() = runTest {
        val release = CompletableDeferred<Unit>()
        val fetcher = FlightFetcher { _, _ -> release.await(); reply() }
        val client = BangumiClient(fetcher, listOf("https://fixture.test"))
        val requests = listOf(
            async { client.search("a", type = 2, limit = 24) },
            async { client.search("b", type = 2, limit = 24) },
            async { client.search("a", type = 1, limit = 24) },
            async { client.search("a", type = 2, limit = 12) },
            async { client.search("a", type = 2, limit = 24) },
        )
        runCurrent()
        try {
            assertEquals(4, fetcher.calls.size)
            assertEquals(4, fetcher.calls.map { it.second }.distinct().size)
        } finally {
            release.complete(Unit)
        }
        requests.forEach { assertEquals(7, it.await().single().id) }
    }
}

package com.animeko.web.bangumi

import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class BangumiHedgeLifecycleTest {
    private class FixtureFetcher(private val respond: suspend (url: String, body: String?) -> HttpResult) : Fetcher {
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

    private fun reply(body: String = """{"data":[{"id":7}]}""", status: Int = 200) =
        HttpResult(status, emptyMap(), body.toByteArray())

    @Test
    fun `hedging episode pagination retains completed preferred pages`() = runTest {
        val official = BangumiClient.OFFICIAL_BASE
        val mirror = BangumiClient.MIRROR_BASE
        val fetcher = FixtureFetcher { url, body ->
            when {
                body != null -> {
                    delay(if (url.startsWith(mirror)) 1 else 1_000)
                    reply()
                }
                url.contains("/v0/subjects/") -> {
                    delay(1)
                    reply("""{"id":7}""")
                }
                url.startsWith(official) -> {
                    delay(1_000)
                    reply("""{"total":0,"data":[]}""")
                }
                else -> {
                    val offset = url.substringAfter("offset=").toInt()
                    delay(if (offset == 0) 10 else 150)
                    reply("""{"total":2,"data":[{"id":${offset + 1}}]}""")
                }
            }
        }
        val client = BangumiClient(fetcher, listOf(official, mirror), preferredHedgeMs = 100)
        client.search("warm mirror")
        fetcher.calls.clear()
        val started = currentTime
        val episodes = client.subject(7).eps as JsonArray
        assertEquals(2, episodes.size)
        assertEquals(listOf(0, 1), fetcher.calls.filter { it.first.startsWith("$mirror/v0/episodes?") }.map { it.first.substringAfter("offset=").toInt() })
        assertEquals(1L + 10 + 150, currentTime - started, "completed pages and elapsed preferred work must not be discarded")
    }

    @Test
    fun `cancelling before the hedge window does not start any backup`() = runTest {
        val official = BangumiClient.OFFICIAL_BASE
        val mirror = BangumiClient.MIRROR_BASE
        var hanging = false
        var active = 0
        val fetcher = FixtureFetcher { url, _ ->
            if (!hanging) {
                delay(if (url.startsWith(mirror)) 1 else 1_000)
                reply()
            } else {
                active++
                try { awaitCancellation() } finally { active-- }
            }
        }
        val client = BangumiClient(fetcher, listOf(official, mirror), preferredHedgeMs = 100)
        client.search("warm")
        hanging = true
        fetcher.calls.clear()
        val request = async { client.search("pending") }
        runCurrent()
        advanceTimeBy(50)
        request.cancelAndJoin()
        assertEquals(0, active)
        assertEquals(listOf("$mirror/v0/search/subjects"), fetcher.calls.map { it.first })
    }

    @Test
    fun `cancelling after backups start releases every candidate task`() = runTest {
        val official = BangumiClient.OFFICIAL_BASE
        val mirror = BangumiClient.MIRROR_BASE
        var hanging = false
        var active = 0
        val fetcher = FixtureFetcher { url, _ ->
            if (!hanging) {
                delay(if (url.startsWith(mirror)) 1 else 1_000)
                reply()
            } else {
                active++
                try { awaitCancellation() } finally { active-- }
            }
        }
        val client = BangumiClient(fetcher, listOf(official, mirror), preferredHedgeMs = 100)
        client.search("warm")
        hanging = true
        fetcher.calls.clear()
        val request = async { client.search("pending") }
        runCurrent()
        assertEquals(1, active)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, active)
        request.cancelAndJoin()
        assertEquals(0, active)
        assertEquals(1, fetcher.calls.count { it.first.startsWith(mirror) })
        assertEquals(1, fetcher.calls.count { it.first.startsWith(official) })
    }

    @Test
    fun `a warm 404 keeps its error identity only after every candidate fails`() = runTest {
        val official = BangumiClient.OFFICIAL_BASE
        val mirror = BangumiClient.MIRROR_BASE
        val fetcher = FixtureFetcher { url, body ->
            if (body != null) {
                delay(if (url.startsWith(mirror)) 1 else 1_000)
                reply()
            } else {
                reply("not available", if (url.startsWith(mirror)) 404 else 503)
            }
        }
        val client = BangumiClient(fetcher, listOf(official, mirror), preferredHedgeMs = 100)
        client.search("warm")
        fetcher.calls.clear()
        assertFailsWith<SubjectNotFoundException> { client.subject(99) }
        assertEquals(1, fetcher.calls.count { it.first.startsWith(mirror) })
        assertEquals(1, fetcher.calls.count { it.first.startsWith(official) })
    }

    private class FixtureCancellation(val identity: Any) : CancellationException("fixture cancellation")

    @Test
    fun `an explicit cancellation from a warm request is not converted into an upstream error`() = runTest {
        val expected = FixtureCancellation(Any())
        var cancel = false
        val fetcher = FixtureFetcher { _, _ -> if (cancel) throw expected else reply() }
        val client = BangumiClient(fetcher, listOf("https://fixture.test"))
        client.search("warm")
        cancel = true
        val actual = assertFailsWith<FixtureCancellation> { withTimeout(1_000) { client.search("cancel") } }
        assertSame(expected, actual)
    }

    @Test
    fun `a late cancelling loser cannot replace an already chosen winner with a send failure`() = runTest {
        val a = "https://fixture-a.test"
        val b = "https://fixture-b.test"
        val fetcher = FixtureFetcher { url, _ ->
            if (url.startsWith(a)) {
                delay(10)
                reply("""{"data":[{"id":1}]}""")
            } else {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    // A completed response/decode may still finish after its losing task is cancelled.
                }
                reply("""{"data":[{"id":2}]}""")
            }
        }
        val client = BangumiClient(fetcher, listOf(a, b))
        assertEquals(1, client.search("winner").single().id)
        assertEquals(2, fetcher.calls.size)
    }
}

package com.animeko.web.bangumi

import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import com.animeko.web.http.UpstreamException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class BangumiEndpointSingleFlightTest {
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

    private fun reply(body: String = """{"data":[{"id":7}]}""") = HttpResult(200, emptyMap(), body.toByteArray())

    @Test
    fun `ranking merges only requests with the same limit and offset`() = runTest {
        val release = CompletableDeferred<Unit>()
        val fetcher = FixtureFetcher { _, _ -> release.await(); reply() }
        val client = BangumiClient(fetcher, listOf("https://fixture.test"))
        val requests = listOf(
            async { client.ranking(24, 0) },
            async { client.ranking(24, 0) },
            async { client.ranking(12, 0) },
            async { client.ranking(24, 24) },
        )
        runCurrent()
        try {
            assertEquals(3, fetcher.calls.size)
            assertEquals(3, fetcher.calls.map { it.first }.distinct().size)
        } finally {
            release.complete(Unit)
        }
        requests.forEach { assertEquals(7, it.await().data.single().id) }
    }

    @Test
    fun `calendar callers share an in-flight request without caching completed results`() = runTest {
        val release = CompletableDeferred<Unit>()
        val fetcher = FixtureFetcher { _, _ -> release.await(); reply("[]") }
        val client = BangumiClient(fetcher, listOf("https://fixture.test"))
        val first = async { client.calendar() }
        val second = async { client.calendar() }
        runCurrent()
        try {
            assertEquals(1, fetcher.calls.size)
        } finally {
            release.complete(Unit)
        }
        assertEquals(first.await(), second.await())
        client.calendar()
        assertEquals(2, fetcher.calls.size)
    }

    @Test
    fun `a subject flight includes its episode pages and separates subject ids`() = runTest {
        val release = CompletableDeferred<Unit>()
        val fetcher = FixtureFetcher { url, _ ->
            release.await()
            if (url.contains("/v0/subjects/")) {
                val id = url.substringAfter("/v0/subjects/").substringBefore('?').toInt()
                reply("""{"id":$id}""")
            } else {
                val offset = url.substringAfter("offset=").toInt()
                reply("""{"total":2,"data":[{"id":${offset + 1}}]}""")
            }
        }
        val client = BangumiClient(fetcher, listOf("https://fixture.test"))
        val first = async { client.subject(7) }
        val second = async { client.subject(7) }
        val different = async { client.subject(9) }
        runCurrent()
        try {
            assertEquals(2, fetcher.calls.size, "two subject ids need two tasks, not one task per caller")
        } finally {
            release.complete(Unit)
        }
        assertEquals(first.await(), second.await())
        assertEquals(9, different.await().id)
        assertEquals(2, (first.await().eps as JsonArray).size)
        assertEquals(1, fetcher.calls.count { it.first.contains("/v0/subjects/7?") })
        assertEquals(2, fetcher.calls.count { it.first.contains("/v0/episodes?subject_id=7&") })
    }

    @Test
    fun `a failed shared request is removed before the next attempt`() = runTest {
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val fetcher = FixtureFetcher { _, _ ->
            attempts++
            if (attempts == 1) {
                release.await()
                error("fixture upstream unavailable")
            }
            reply()
        }
        val client = BangumiClient(fetcher, listOf("https://fixture.test"))
        val first = async { runCatching { client.search("same") } }
        runCurrent()
        val second = async { runCatching { client.search("same") } }
        runCurrent()
        try {
            assertEquals(1, attempts)
        } finally {
            release.complete(Unit)
        }
        assertIs<UpstreamException>(first.await().exceptionOrNull())
        assertIs<UpstreamException>(second.await().exceptionOrNull())
        assertEquals(7, client.search("same").single().id)
        assertEquals(2, attempts)
    }

    @Test
    fun `a committed channel change separates in-flight generations even for a shared base`() = runTest {
        val release = CompletableDeferred<Unit>()
        val fetcher = FixtureFetcher { _, _ -> release.await(); reply() }
        val client = BangumiClient(fetcher, listOf(BangumiClient.OFFICIAL_BASE))
        val oldFirst = async { client.search("same") }
        val oldSecond = async { client.search("same") }
        runCurrent()
        assertEquals(1, fetcher.calls.size)
        client.setChannel(BgmChannelConfig(BgmChannel.OFFICIAL))
        val newFirst = async { client.search("same") }
        val newSecond = async { client.search("same") }
        runCurrent()
        try {
            assertEquals(2, fetcher.calls.size, "a channel commit must invalidate the previous flight key")
        } finally {
            release.complete(Unit)
        }
        listOf(oldFirst, oldSecond, newFirst, newSecond).forEach { assertEquals(7, it.await().single().id) }
    }

    @Test
    fun `an old channel winner cannot overwrite the new channels preferred address`() = runTest {
        val a = "http://127.0.0.1:18001"
        val b = "http://127.0.0.1:18002"
        val releaseOld = CompletableDeferred<Unit>()
        val fetcher = FixtureFetcher { url, body ->
            if (body.orEmpty().contains("\"keyword\":\"old\"")) {
                releaseOld.await()
                delay(if (url.startsWith(a)) 0 else 100)
            } else {
                delay(if (url.startsWith(b)) 1 else 100)
            }
            reply()
        }
        val client = BangumiClient(fetcher, listOf(a, b), allowPrivateTargets = true)
        val old = async { client.search("old") }
        runCurrent()
        try {
            client.setChannel(BgmChannelConfig(BgmChannel.CUSTOM, listOf(a, b)))
            client.search("new")
            releaseOld.complete(Unit)
            old.await()
            fetcher.calls.clear()
            client.search("after old completion")
            assertEquals(listOf("$b/v0/search/subjects"), fetcher.calls.map { it.first }, "old completion must not hijack the current generation's winner")
        } finally {
            releaseOld.complete(Unit)
        }
    }
}

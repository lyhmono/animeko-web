package com.animeko.web.bangumi

import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class BangumiRaceRegressionTest {
    private class FixtureFetcher(
        private val respond: suspend (method: String, url: String, body: String?) -> HttpResult,
    ) : Fetcher {
        val calls = mutableListOf<String>()
        override suspend fun fetch(url: String, headers: Map<String, String>, followRedirects: Boolean): HttpResult {
            calls += "GET $url"
            return respond("GET", url, null)
        }
        override suspend fun post(url: String, body: String, headers: Map<String, String>, contentType: String): HttpResult {
            calls += "POST $url"
            return respond("POST", url, body)
        }
        override suspend fun fetchStream(url: String, headers: Map<String, String>, followRedirects: Boolean): StreamResult =
            error("no real upstream or media request is allowed in this test")
    }

    private fun reply(status: Int = 200, body: String) =
        HttpResult(status, mapOf("content-type" to listOf("application/json")), body.toByteArray())

    @Test
    fun `warm mirror 404 still allows an official subject response to win`() = runTest {
        val official = BangumiClient.OFFICIAL_BASE
        val mirror = BangumiClient.MIRROR_BASE
        val fetcher = FixtureFetcher { method, url, _ ->
            when {
                method == "POST" -> {
                    delay(if (url.startsWith(mirror)) 10 else 100)
                    reply(body = """{"data":[{"id":7}]}""")
                }
                url.startsWith("$mirror/v0/subjects/") -> reply(404, "not found")
                url.startsWith("$official/v0/subjects/") -> reply(body = """{"id":7}""")
                url.contains("/v0/episodes?") -> reply(body = """{"total":0,"data":[]}""")
                else -> error("unexpected fixture URL $url")
            }
        }
        val client = BangumiClient(fetcher, listOf(official, mirror))
        client.search("establish mirror winner")
        fetcher.calls.clear()
        client.search("confirm preferred address")
        assertEquals(listOf("POST $mirror/v0/search/subjects"), fetcher.calls)
        fetcher.calls.clear()

        assertEquals(7, client.subject(7).id)
        assertEquals(1, fetcher.calls.count { it.startsWith("GET $mirror/v0/subjects/") }, "a failed candidate must not be resent")
        assertEquals(1, fetcher.calls.count { it.startsWith("GET $official/v0/subjects/") })
    }

    @Test
    fun `hedge starts backups without cancelling or resending the preferred request`() = runTest {
        val official = BangumiClient.OFFICIAL_BASE
        val mirror = BangumiClient.MIRROR_BASE
        var warmed = false
        val fetcher = FixtureFetcher { method, url, _ ->
            check(method == "POST")
            delay(if (url.startsWith(mirror)) if (warmed) 150 else 10 else 1_000)
            reply(body = """{"data":[{"id":7}]}""")
        }
        val client = BangumiClient(fetcher, listOf(official, mirror), preferredHedgeMs = 100)
        client.search("warm")
        warmed = true
        fetcher.calls.clear()
        val started = currentTime

        assertEquals(7, client.search("slow preferred").single().id)
        assertEquals(1, fetcher.calls.count { it.startsWith("POST $mirror/") }, "the original preferred task must remain in the race")
        assertEquals(1, fetcher.calls.count { it.startsWith("POST $official/") })
        assertEquals(150L, currentTime - started, "the preferred task must retain its first 100ms of progress")
    }
}

package com.animeko.web.bangumi

import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import com.animeko.web.http.UpstreamException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BangumiAggregateEpisodeBudgetTest {
    private class Pages(private val pages: Map<Int, String>) : Fetcher {
        val offsets = mutableListOf<Int>()
        override suspend fun fetch(url: String, headers: Map<String, String>, followRedirects: Boolean): HttpResult {
            val body = if (url.contains("/v0/episodes?")) {
                val offset = url.substringAfter("offset=").toInt()
                offsets += offset
                pages[offset] ?: error("unexpected episode offset $offset")
            } else {
                """{"id":7}"""
            }
            return HttpResult(200, emptyMap(), body.toByteArray())
        }
        override suspend fun post(url: String, body: String, headers: Map<String, String>, contentType: String): HttpResult =
            error("unexpected POST")
        override suspend fun fetchStream(url: String, headers: Map<String, String>, followRedirects: Boolean): StreamResult =
            error("no real upstream or media request is allowed in this test")
    }

    private fun pages(sizes: List<Int>, total: Int?): Pages {
        var offset = 0
        val responses = linkedMapOf<Int, String>()
        for (size in sizes) {
            val data = (1..size).joinToString(",", "[", "]") { """{"id":${offset + it}}""" }
            responses[offset] = """{"data":$data${if (total == null) "" else ",\"total\":$total"}}"""
            offset += size
        }
        return Pages(responses)
    }

    @Test
    fun `a last page cannot exceed the remaining aggregate capacity`() = runTest {
        val fetcher = pages(List(9) { 200 } + listOf(150, 51), total = 2000)
        val error = assertFailsWith<UpstreamException> { BangumiClient(fetcher, listOf("https://fixture.test")).subject(7) }
        assertTrue(error.message.orEmpty().contains("上限"))
    }

    @Test
    fun `an upstream total beyond the budget is diagnosed instead of silently truncated`() = runTest {
        val fetcher = pages(List(10) { 200 } + 1, total = 2001)
        val error = assertFailsWith<UpstreamException> { BangumiClient(fetcher, listOf("https://fixture.test")).subject(7) }
        assertTrue(error.message.orEmpty().contains("上限"))
    }

    @Test
    fun `a known total exactly at the budget remains usable`() = runTest {
        val fetcher = pages(List(10) { 200 }, total = 2000)
        val episodes = BangumiClient(fetcher, listOf("https://fixture.test")).subject(7).eps as JsonArray
        assertEquals(2000, episodes.size)
        assertEquals(List(10) { it * 200 }, fetcher.offsets)
    }

    @Test
    fun `an unknown total cannot silently truncate a full aggregate budget`() = runTest {
        val fetcher = pages(List(11) { 200 }, total = null)
        val error = assertFailsWith<UpstreamException> { BangumiClient(fetcher, listOf("https://fixture.test")).subject(7) }
        assertTrue(error.message.orEmpty().contains("上限"))
    }
}

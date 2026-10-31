package com.animeko.web.bangumi

import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.StreamResult
import com.animeko.web.http.UpstreamException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BangumiEpisodeBudgetTest {
    @Test
    fun `a single oversized episode page is rejected before it can exceed the aggregate budget`() = runTest {
        val oversized = (1..2001).joinToString(",", "[", "]") { """{"id":$it}""" }
        val fetcher = object : Fetcher {
            override suspend fun fetch(url: String, headers: Map<String, String>, followRedirects: Boolean): HttpResult {
                val body = if (url.contains("/v0/episodes?")) """{"total":2001,"data":$oversized}""" else """{"id":7}"""
                return HttpResult(200, emptyMap(), body.toByteArray())
            }
            override suspend fun post(url: String, body: String, headers: Map<String, String>, contentType: String): HttpResult =
                error("unexpected POST")
            override suspend fun fetchStream(url: String, headers: Map<String, String>, followRedirects: Boolean): StreamResult =
                error("no real upstream or media request is allowed in this test")
        }

        val error = assertFailsWith<UpstreamException> { BangumiClient(fetcher, listOf("https://fixture.test")).subject(7) }
        assertTrue(error.message.orEmpty().contains("上限"), "the episode budget failure must be diagnosable: ${error.message}")
    }
}

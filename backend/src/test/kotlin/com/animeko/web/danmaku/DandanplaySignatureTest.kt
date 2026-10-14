package com.animeko.web.danmaku

import kotlin.test.Test
import kotlin.test.assertEquals

class DandanplaySignatureTest {

    @Test
    fun `signature matches documented algorithm`() {
        val client = DandanplayClient(
            fetcher = ThrowingFetcher(),
            appId = "testapp",
            appSecret = "testsecret",
        )
        // 期望值由 Python 独立计算：
        // base64(sha256("testapp" + "1735660800" + "/api/v2/comment/123450001" + "testsecret"))
        assertEquals(
            "mPSSHgHVGnwrxg63KjpjjdG/NkXaXtqVt9tGlObj4NA=",
            client.signature("/api/v2/comment/123450001", 1735660800),
        )
    }

    @Test
    fun `configured requires both credentials`() {
        val empty = DandanplayClient(ThrowingFetcher(), "", "")
        assertEquals(false, empty.configured)
        val half = DandanplayClient(ThrowingFetcher(), "id", "")
        assertEquals(false, half.configured)
    }

    private class ThrowingFetcher : com.animeko.web.http.Fetcher {
        override suspend fun fetch(
            url: String,
            headers: Map<String, String>,
            followRedirects: Boolean,
        ): com.animeko.web.http.HttpResult = throw UnsupportedOperationException()

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
            contentType: String,
        ): com.animeko.web.http.HttpResult = throw UnsupportedOperationException()

        override suspend fun fetchStream(
            url: String,
            headers: Map<String, String>,
            followRedirects: Boolean,
        ): com.animeko.web.http.StreamResult = throw UnsupportedOperationException()
    }
}

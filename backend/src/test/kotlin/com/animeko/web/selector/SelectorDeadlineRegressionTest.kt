package com.animeko.web.selector

import com.animeko.web.http.HttpResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SelectorDeadlineRegressionTest {
    private val base = "https://deadline.test"
    private fun cfg() = SelectorSearchConfig(
        searchUrl = "$base/search?q={keyword}", requestInterval = 0,
        matchVideo = MatchVideoConfig(matchVideoUrl = "preferred", matchNestedUrl = "frame"),
    )
    private val outer = """<iframe src="/frame"></iframe><script>var u="https://cdn.test/fallback.m3u8";</script>"""

    @Test
    fun `nested deadline includes an in flight fetch and preserves an available fallback`() = runTest {
        val ordinary = StubFetcher(mapOf("$base/play" to outer))
        val fetcher = object : com.animeko.web.http.Fetcher by ordinary {
            override suspend fun fetchBounded(url: String, headers: Map<String, String>, maxBytes: Long): HttpResult {
                if (url == "$base/frame") awaitCancellation()
                return ordinary.fetchBounded(url, headers, maxBytes)
            }
        }
        val result = withTimeoutOrNull(31_000) { selectorTestEngine(fetcher).resolveVideo(cfg(), "$base/play") }
        assertNotNull(result, "nested read ignored its 30 second deadline")
        assertEquals("https://cdn.test/fallback.m3u8", result.url)
        assertEquals(30_000, currentTime)
    }

    @Test
    fun `nested cancellation is not swallowed as a failed candidate`() = runTest {
        val cancelled = CancellationException("caller left")
        val ordinary = StubFetcher(mapOf("$base/play" to outer))
        val fetcher = object : com.animeko.web.http.Fetcher by ordinary {
            override suspend fun fetchBounded(url: String, headers: Map<String, String>, maxBytes: Long): HttpResult {
                if (url == "$base/frame") throw cancelled
                return ordinary.fetchBounded(url, headers, maxBytes)
            }
        }
        val actual = assertFailsWith<CancellationException> { selectorTestEngine(fetcher).resolveVideo(cfg(), "$base/play") }
        // Coroutine debug stack recovery may copy the outer exception, preserving its cause.
        val original = generateSequence(actual as Throwable) { it.cause }.last()
        assertSame(cancelled, original)
    }
}

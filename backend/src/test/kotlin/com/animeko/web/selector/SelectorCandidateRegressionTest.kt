package com.animeko.web.selector

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SelectorCandidateRegressionTest {
    @Test
    fun `a nested iframe cannot refetch its current page`() = runTest {
        val url = "https://candidate.test/play/outer"
        val fetcher = StubFetcher(mapOf(url to """<iframe src="$url"></iframe>"""))
        val cfg = SelectorSearchConfig(
            searchUrl = "https://candidate.test/s", requestInterval = 0,
            matchVideo = MatchVideoConfig(matchNestedUrl = "outer"),
        )
        assertFailsWith<SelectorException> { selectorTestEngine(fetcher).resolveVideo(cfg, url) }
        assertEquals(listOf(url), fetcher.requested, "candidate deduplication must also track fetched pages")
    }
}

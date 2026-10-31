package com.animeko.web.selector

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectorRedirectRegressionTest {
    private fun cfg() = SelectorSearchConfig(
        searchUrl = "https://old.test/search?q={keyword}",
        requestInterval = 0,
        rawBaseUrl = "",
        selectorSubjectFormatA = SubjectFormatA.Config(selectLists = "a"),
        selectorChannelFormatNoChannel = ChannelFormatNoChannel.Config(selectEpisodes = "a"),
    )

    @Test
    fun `search and episode links use the final redirected origin`() = runTest {
        val fetcher = StubFetcher(
            mapOf(
                "https://new.test/catalog/search" to """<a href="/detail/1">A</a>""",
                "https://new.test/detail/1" to """<a href="/play/1">第1话</a>""",
            ),
            redirects = mapOf(
                "https://old.test/search?q=q" to "https://new.test/catalog/search",
                "https://old.test/detail/1" to "https://new.test/detail/1",
            ),
        )
        val engine = selectorTestEngine(fetcher)
        assertEquals("https://new.test/detail/1", engine.search(cfg(), "q").single().url)
        assertEquals("https://new.test/play/1", engine.episodes(cfg(), "https://old.test/detail/1").episodes.single().playUrl)
        assertEquals("https://override.test/detail/1", engine.search(cfg().copy(rawBaseUrl = "https://override.test"), "q").single().url)
    }

    @Test
    fun `relative nested pages and video use final URLs without expanding cookie scope`() = runTest {
        val fetcher = StubFetcher(
            mapOf(
                "https://new.test/player/outer" to """<iframe src="frame"></iframe>""",
                "https://cdn.test/frames/final" to """<video src="movie.m3u8"></video>""",
            ),
            redirects = mapOf(
                "https://old.test/play/outer" to "https://new.test/player/outer",
                "https://new.test/player/frame" to "https://cdn.test/frames/final",
            ),
        )
        val cfg = cfg().copy(matchVideo = MatchVideoConfig(matchNestedUrl = "frame", cookies = "secret=VALUE"))
        val result = selectorTestEngine(fetcher).resolveVideo(cfg, "https://old.test/play/outer")
        assertEquals("https://cdn.test/frames/movie.m3u8", result.url)
        assertTrue("https://new.test/player/frame" in fetcher.requested)
        assertEquals("secret=VALUE", fetcher.headersSeen.first().second["Cookie"])
        assertFalse(fetcher.headersSeen.drop(1).any { "Cookie" in it.second }, "redirected host did not acquire source cookies")
    }
}

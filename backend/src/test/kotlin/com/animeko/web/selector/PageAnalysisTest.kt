package com.animeko.web.selector

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PageAnalysisTest {
    @Test
    fun `candidate construction stops at the cap rather than collecting all frames`() {
        val html = (0 until 10_000).joinToString("") { """<iframe src="/frame$it"></iframe>""" }
        val page = PageAnalysis(html, "https://page.test/play")
        assertEquals(PageAnalysis.MAX_CANDIDATES, page.videoCandidates.size)
        assertEquals(PageAnalysis.MAX_CANDIDATES, page.nestedCandidates.size)
        assertEquals(1, page.metrics.parses)
        assertEquals(0, page.metrics.rawPasses, "saturated DOM candidates should not trigger another URL scan")
        assertTrue(page.metrics.domVisits < 10_000, "DOM extraction should stop at 5000 frames")
        assertEquals("https://page.test/frame0", page.videoCandidates.first())
    }

    @Test
    fun `one analysis is reused by video fallback and nested candidates`() {
        val page = PageAnalysis("""<iframe src="/frame"></iframe><script>var u="https://cdn.test/a.m3u8";</script>""", "https://page.test/play")
        assertTrue(page.videoCandidates.any { it.endsWith("a.m3u8") })
        assertTrue(page.nestedCandidates.any { it.endsWith("/frame") })
        assertEquals(1, page.metrics.parses)
        assertEquals(1, page.metrics.rawPasses)
    }

    @Test
    fun `raw duplicate matches have an independent work cap`() {
        val html = List(15_000) { "https://cdn.test/duplicate.m3u8 " }.joinToString("")
        val page = PageAnalysis(html, "https://page.test/play")
        assertEquals(1, page.videoCandidates.size)
        assertEquals(10_000, page.metrics.rawMatches)
    }

    @Test
    fun `DOM candidate extraction polls caller cancellation`() {
        val cancelled = CancellationException("left page")
        var polls = 0
        val html = "<span>x</span>".repeat(1_000)
        val actual = assertFailsWith<CancellationException> {
            PageAnalysis(html, "https://page.test/play") { if (++polls == 3) throw cancelled }
        }
        assertSame(cancelled, actual)
        assertEquals(3, polls)
    }
}

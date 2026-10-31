package com.animeko.web.selector

import org.jsoup.Jsoup
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectorSafetyRegressionTest {
    @Test
    fun `CSS regex selectors are rejected before jsoup matching`() {
        val doc = Jsoup.parse("""<a href="https://example.test/">aaab</a>""")
        for (selector in listOf(
            "a:matches((a+)+$)",
            "a:matchesOwn((a+)+$)",
            "a:matchesWholeText((a+)+$)",
            "a:matchesWholeOwnText((a+)+$)",
            "a[href~=example]",
            "a:not(:matchesOwn(aaab))",
        )) {
            assertNull(SelectorEngine.queryAll(doc, selector), "unsafe selector was evaluated: $selector")
        }
        assertEquals(1, SelectorEngine.queryAll(doc, "a[href^=https]:contains(aaab)")?.size)
        assertEquals(1, SelectorEngine.queryAll(doc, "xpath://a[contains(@href, 'example')]")?.size)
    }

    @Test
    fun `source imports report unsupported CSS regex rather than accepting an unbudgeted rule`() {
        val repo = SelectorRepository(selectorTempDir("selector-safety"))
        val args = SelectorSourceArguments(
            name = "CSS regex",
            searchConfig = SelectorSearchConfig(
                searchUrl = "https://example.test/s",
                selectorSubjectFormatA = SubjectFormatA.Config(selectLists = "a:matchesOwn(aaab)"),
            ),
        )
        val error = assertFailsWith<IllegalArgumentException> { repo.save(args) }
        assertTrue(error.message.orEmpty().contains("CSS"), error.message)
        assertTrue(error.message.orEmpty().contains("正则"), error.message)
        assertTrue(repo.list().isEmpty())
    }

    @Test
    fun `many descendant combinators are rejected below the length limit`() {
        val doc = Jsoup.parse("<a>x</a>")
        assertNull(SelectorEngine.queryAll(doc, "b ".repeat(100) + "a"), "complexity is not only punctuation count")
    }

    @Test
    fun `overlong CSS and XPath are not evaluated`() {
        val doc = Jsoup.parse("<a>x</a>")
        assertNull(SelectorEngine.queryAll(doc, "a" + " ".repeat(4096) + ":contains(x)"))
        assertNull(SelectorEngine.queryAll(doc, "xpath://a[" + "true() and ".repeat(512) + "true()]"))
    }
}

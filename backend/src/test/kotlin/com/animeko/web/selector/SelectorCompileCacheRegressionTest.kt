package com.animeko.web.selector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SelectorCompileCacheRegressionTest {
    @Test
    fun `compiled configuration patterns are shared without changing lookaround semantics`() {
        val raw = "(?<ep>\\d+)(?=集)"
        val first = assertNotNull(SafeRegex.compile(raw))
        assertSame(first, SafeRegex.compile(raw), "compile identical config patterns once")
        assertEquals("12", SafeRegex.group(first, "第12集", "ep"))
        assertNull(SafeRegex.group(first, "第12话", "ep"))
        val tiny = SafeRegex.Budget(1)
        assertTrue(!SafeRegex.contains(first, "第12集", budget = tiny))
        assertTrue(SafeRegex.contains(first, "第12集", budget = SafeRegex.Budget()), "matcher/budget must not be shared with the pattern")
    }
}

package com.animeko.web.selector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SelectorHealthTest {
    @Test
    fun `record counts success and failure with elapsed time`() = runTest {
        val name = "fixture-${System.nanoTime()}"
        SelectorHealth.record(name) { "ok" }
        assertFailsWith<IllegalStateException> {
            SelectorHealth.record(name) { error("upstream down") }
        }
        val health = SelectorHealth.snapshot().getValue(name)
        assertEquals(1, health.success)
        assertEquals(1, health.failure)
        assertTrue(health.avgMs >= 0)
        assertTrue(health.lastError?.contains("upstream down") == true)
        assertTrue(health.lastAt > 0)
    }
}
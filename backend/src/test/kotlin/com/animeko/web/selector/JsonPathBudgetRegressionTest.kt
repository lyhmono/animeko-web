package com.animeko.web.selector

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonPathBudgetRegressionTest {
    @Test
    fun `ordinary property expansion stops before materializing an entire wide array`() {
        var reads = 0
        val item = JsonObject(mapOf("a" to JsonPrimitive("value")))
        val wide = JsonArray(object : AbstractList<JsonElement>() {
            override val size = 25_000
            override fun get(index: Int): JsonElement { reads++; return item }
        })
        assertNull(JsonPathEval.evaluate("$.a", wide))
        assertTrue(reads <= 20_001, "read $reads array elements before rejecting the 20000 result cap")
    }

    @Test
    fun `aligned property expansion has the same incremental collector bound`() {
        var reads = 0
        val item = JsonObject(mapOf("a" to JsonPrimitive("value")))
        val wide = JsonArray(object : AbstractList<JsonElement>() {
            override val size = 25_000
            override fun get(index: Int): JsonElement { reads++; return item }
        })
        assertNull(JsonPathEval.evaluateAligned("$['a']", wide))
        assertTrue(reads <= 20_001, "aligned expansion read all $reads elements before rejecting")
    }

    @Test
    fun `repeated empty deep scans exhaust a visited node budget`() {
        val leaves = JsonArray(List(600) { JsonPrimitive(it) })
        var root: JsonElement = JsonObject(mapOf("leaves" to leaves))
        repeat(64) { root = JsonObject(mapOf("a" to root)) }
        // No terminal matches. An output-only limit never fires, despite overlapping walks.
        assertNull(JsonPathEval.evaluate("$..a..a..missing", root))
    }
}

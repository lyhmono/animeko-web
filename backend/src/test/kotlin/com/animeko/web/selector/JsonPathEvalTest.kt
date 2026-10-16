package com.animeko.web.selector

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonPathEvalTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(text: String): JsonObject = json.parseToJsonElement(text).let { it as JsonObject }

    @Test
    fun `wildcard multi property`() {
        val root = parse("""{"data":[{"title":"A","url":"/a"},{"title":"B","url":"/b"}]}""")
        val urls = JsonPathEval.evaluate("$[*]['url', 'link']", root)!!
        assertEquals(listOf("/a", "/b"), urls.map { JsonPathEval.singleString(it) })
    }

    @Test
    fun `wildcard dot property`() {
        val root = parse("""{"data":[{"title":"A"},{"title":"B"}]}""")
        val names = JsonPathEval.evaluate("$[*].title", root)!!
        assertEquals(listOf("A", "B"), names.map { JsonPathEval.singleString(it) })
    }

    @Test
    fun `deep scan`() {
        val root = parse("""{"a":{"b":{"name":"deep"}},"c":{"name":"top"}}""")
        val found = JsonPathEval.evaluate("$..name", root)!!
        assertEquals(setOf("deep", "top"), found.map { JsonPathEval.singleString(it) }.toSet())
    }

    @Test
    fun `index access`() {
        val root = parse("""{"list":[{"id":"x"},{"id":"y"}]}""")
        val first = JsonPathEval.evaluate("$.list[0].id", root)!!
        assertEquals(listOf("x"), first.map { JsonPathEval.singleString(it) })
    }

    @Test
    fun `single string unwraps arrays and objects`() {
        val root = parse("""{"data":[{"url":["/nested","/second"]}]}""")
        val v = JsonPathEval.evaluate("$[*]['url']", root)!!.first()
        assertEquals("/nested", JsonPathEval.singleString(v))
    }

    @Test
    fun `invalid path returns null`() {
        val root = parse("""{"a":1}""")
        assertNull(JsonPathEval.evaluate("data.title", root))
    }

    @Test
    fun `missing key yields empty list`() {
        val root = parse("""{"data":[{"title":"A"}]}""")
        val v = JsonPathEval.evaluate("$[*]['missing']", root)!!
        assertTrue(v.isEmpty())
    }

    @Test
    fun `json null does not become the literal string null`() {
        val root = Json.parseToJsonElement("""[{"title":null,"url":"/x"}]""")
        val names = JsonPathEval.evaluate("\$[*]['title','name']", root)!!
            .map { JsonPathEval.singleString(it) }
        assertEquals(listOf<String?>(null), names)
    }
}

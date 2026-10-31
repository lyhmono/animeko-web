package com.animeko.web.selector

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonAlignmentRegressionTest {
    private val base = "https://json.test"
    private fun cfg(names: String = "$.list[*].title", links: String = "$.list[*].url") = SelectorSearchConfig(
        searchUrl = "$base/search?keyword={keyword}",
        rawBaseUrl = base,
        requestInterval = 0,
        subjectFormatId = SubjectFormatJsonPathIndexed.ID,
        autoMatch = SelectorAutoMatchConfig(preferShorterName = false),
        selectorSubjectFormatJsonPathIndexed = SubjectFormatJsonPathIndexed.Config(
            selectNames = names,
            selectLinks = links,
        ),
    )

    @Test
    fun `dot property keeps missing names aligned`() = runTest {
        val body = """{"list":[{"title":"A","url":"/a"},{"url":"/ad"},{"title":"B","url":"/b"}]}"""
        val hits = selectorTestEngine(StubFetcher(mapOf("$base/search" to body))).search(cfg(), "q")
        assertEquals(listOf("A" to "$base/a", "B" to "$base/b"), hits.map { it.name to it.url })
        val root = Json.parseToJsonElement(body)
        assertEquals(listOf("A", null, "B"), JsonPathEval.evaluateAligned("$.list[*].title", root)?.map { JsonPathEval.singleString(it) })
        // General JsonPath still compacts missing properties: aligned semantics must be opt-in.
        assertEquals(listOf("A", "B"), JsonPathEval.evaluate("$.list[*].title", root)?.map { JsonPathEval.singleString(it) })
    }

    @Test
    fun `dot property keeps missing links aligned`() = runTest {
        val body = """{"list":[{"title":"A","url":"/a"},{"title":"ad"},{"title":"B","url":"/b"}]}"""
        val hits = selectorTestEngine(StubFetcher(mapOf("$base/search" to body))).search(cfg(), "q")
        assertEquals(listOf("A" to "$base/a", "B" to "$base/b"), hits.map { it.name to it.url })
    }

    @Test
    fun `missing intermediate object is an aligned placeholder`() = runTest {
        val body = """{"list":[{"info":{"title":"A"},"url":"/a"},{"url":"/ad"},{"info":{"title":"B"},"url":"/b"}]}"""
        val hits = selectorTestEngine(StubFetcher(mapOf("$base/search" to body))).search(cfg(names = "$.list[*].info.title"), "q")
        assertEquals(listOf("A" to "$base/a", "B" to "$base/b"), hits.map { it.name to it.url })
    }

    @Test
    fun `JSON covers use the selected records and fall back per record`() = runTest {
        val body = """{"meta":{"count":3},"list":[
            {"title":"A","url":"/a","pic":"/a.jpg"},
            {"title":"B","url":"/b","pic":"","cover":"/b.jpg"},
            {"title":"C","url":"/c","img":"https://img.test/c.jpg"}
        ]}"""
        val hits = selectorTestEngine(StubFetcher(mapOf("$base/search" to body))).search(cfg(), "q")
        assertEquals(listOf("$base/a.jpg", "$base/b.jpg", "https://img.test/c.jpg"), hits.map { it.cover })
        assertEquals(listOf("$base/a", "$base/b", "$base/c"), hits.map { it.url })
    }

    @Test
    fun `unrelated JSON record collections do not silently pair`() = runTest {
        val body = """{"names":[{"title":"A"}],"links":[{"url":"/b"}]}"""
        kotlin.test.assertFailsWith<SelectorException> {
            selectorTestEngine(StubFetcher(mapOf("$base/search" to body))).search(
                cfg(names = "$.names[*].title", links = "$.links[*].url"), "q",
            )
        }
    }
}

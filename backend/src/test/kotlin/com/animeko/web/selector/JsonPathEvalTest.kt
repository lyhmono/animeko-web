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

    /**
     * 选择器字符串来自第三方配置，每次搜索都会重新解析一遍。
     * 旧实现用 `^(.+)\[(.+)\]$` 拆尾部键列表，两个贪婪 .+ 加行尾锚点在最坏情况是平方级
     * 回溯：实测长度 20001 要 2.7 秒、80001 要 42 秒，而这条路径上没人给配置长度封顶。
     * 一份 `selectNames = "$[[[[[…"` 就能让一台机器被一个数据源按住 CPU。
     */
    @Test
    fun `a pathological selector string does not blow up`() {
        val root = Json.parseToJsonElement("""[{"title":"甲","url":"/a"}]""")
        for (n in listOf(20_000, 80_000)) {
            val path = "\$" + "[".repeat(n)
            val started = System.nanoTime()
            JsonPathEval.evaluateAligned(path, root)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue(ms < 500, "长度 $n 的选择器花了 ${ms}ms——又退回回溯型解析了")
        }
    }

    /** 换成线性拆分后，分割语义必须与原来那条贪婪正则一致 */
    @Test
    fun `aligned split still happens at the last bracket`() {
        val root = Json.parseToJsonElement("""[{"title":"甲","url":"/a","pic":"/p"}]""")
        assertEquals(
            listOf("甲"),
            JsonPathEval.evaluateAligned("\$[*]['title','name']", root)!!.map { JsonPathEval.singleString(it) },
        )
        // 键列表里的顺序就是优先级：前一个键不存在时退到后一个
        assertEquals(
            listOf("甲"),
            JsonPathEval.evaluateAligned("\$[*]['missing','title']", root)!!.map { JsonPathEval.singleString(it) },
        )
        // 尾部不是「键列表」形态时退回普通求值，结果不变
        assertEquals(JsonPathEval.evaluate("\$[*]['title']", root), JsonPathEval.evaluateAligned("\$[*]['title']", root))
        assertEquals(JsonPathEval.evaluate("\$[]", root), JsonPathEval.evaluateAligned("\$[]", root))
    }
}

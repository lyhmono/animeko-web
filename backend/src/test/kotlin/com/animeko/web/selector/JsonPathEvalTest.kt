package com.animeko.web.selector

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonPathEvalTest {

    /**
     * `..key` 连用会组合爆炸，而且爆炸来自「扫描次数」而不是「嵌套深度」，
     * 所以 JsonGuard 的 128 层深度闸门一点忙都帮不上。
     * 实测（未封顶时）：深度 128 配 $..a..a..a..a → 0.96 秒产出 10,668,000 个元素，
     * 再多一段直接跑不完 —— 一份社区数据源配置就能把整个进程打死。
     */
    @Test
    fun `repeated recursive descent is capped instead of exploding`() {
        val depth = 128
        val doc = "{\"a\":".repeat(depth) + "1" + "}".repeat(depth)
        val root = Json.parseToJsonElement(doc)
        val started = System.nanoTime()
        val r = JsonPathEval.evaluate("$"+  "..a".repeat(5), root)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(r == null || r.size <= 20_000, "中间结果没封顶，长到了 ${r?.size}")
        assertTrue(ms < 2_000, "花了 ${ms}ms，工作上限没有生效")
        // 正常选择器不受影响
        assertEquals(1, JsonPathEval.evaluate("$..a", Json.parseToJsonElement("""{"a":{"b":1}}"""))?.size)
    }

    /**
     * `[*]` 之后再取属性会把「中间结果数 × 每个元素的扇出」乘起来。旧实现先 flatMap
     * 出完整列表、再判 MAX_NODES，峰值可以达到 2 万 × 2 万个引用；这里用 300×300
     * 验证有界累加生效（新版在超过 2 万时就返回 null，不会把整张笛卡尔积装进内存）。
     */
    @Test
    fun `a large flatMap fan-out is rejected without materializing the full list`() {
        val row = (0 until 300).joinToString(",", "[", "]") { """{"a":$it}""" }
        val rows = (0 until 300).joinToString(",", "[", "]") { row }
        val root = Json.parseToJsonElement(rows)
        val started = System.nanoTime()
        assertNull(JsonPathEval.evaluate("\$[*].a", root))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(ms < 2_000, "花了 ${ms}ms，扇出限额没有在分配前生效")
    }

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

package com.animeko.web.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 深度闸门本身也容易被写成「错的但看起来对」：把字符串里的括号也算成层级，
 * 一份带大段文本的正常配置就会被判超限。所以正反两个方向都要钉住。
 */
class JsonGuardTest {
    private fun deep(n: Int) = "[".repeat(n) + "]".repeat(n)

    @Test
    fun `accepts the depths real payloads use`() {
        JsonGuard.requireShallow("""{"a":{"b":{"c":[1,2,{"d":"x"}]}}}""")
        JsonGuard.requireShallow(deep(20))
        JsonGuard.requireShallow("")
        JsonGuard.requireShallow("null")
    }

    /**
     * 递归解析器是按**结构**深度吃栈的，所以判据必须只看结构。
     * 一份 30 万字符的签名/说明文本里全是 [ ]，它是合法的浅文档；
     * 按字符数或按括号总数判，会把真实数据源配置全部拒掉。
     */
    @Test
    fun `brackets inside string values are content, not depth`() {
        val text = """{"desc":"${"[".repeat(300_000)}"}"""
        assertTrue(text.length > 300_000)
        JsonGuard.requireShallow(text)
    }

    /**
     * 转义处理错了会往两个相反的方向出错，两个方向都要钉住：
     *  - 把 `\"` 当成字符串结束 → 字符串里的括号被当成结构，正常配置被误拒；
     *  - 把 `\\` 后面的收尾引号吃掉 → 真正的结构括号被忽略，炸弹漏过去。
     */
    @Test
    fun `escaped quotes do not end a string early`() {
        // 40 个 [ 全在字符串值内部：合法且很浅
        JsonGuard.requireShallow("""{"a":"\"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[["}""")
        // 同样写法放大到 5000 个，仍然应当通过（判据是结构深度，不是字符数）
        JsonGuard.requireShallow("""{"a":"\"${"[".repeat(5000)}"}""")
    }

    @Test
    fun `a value ending in an escaped backslash still closes the string`() {
        // "x\\" 的实际内容是 x\，第二个反斜杠是转义对的一部分，收尾引号必须生效；
        // 若状态机把它吞了，后面这 200 个真实结构括号就会被当成字符串内容而漏检
        assertFailsWith<JsonGuard.TooDeep> {
            JsonGuard.requireShallow("""{"a":"x\\"""" + "[".repeat(200) + "]".repeat(200))
        }
    }

    @Test
    fun `genuine nesting beyond the limit is refused`() {
        assertFailsWith<JsonGuard.TooDeep> { JsonGuard.requireShallow("""{"a":${deep(300)}}""") }
    }

    @Test
    fun `refuses beyond the limit and says so by name`() {
        val e = assertFailsWith<JsonGuard.TooDeep> { JsonGuard.requireShallow(deep(5_000)) }
        assertEquals(true, e.message?.contains("${JsonGuard.MAX_DEPTH}"))
        // 刚好在边界内不炸：128 层结构对递归解析器毫无压力
        JsonGuard.requireShallow(deep(JsonGuard.MAX_DEPTH))
        assertFailsWith<JsonGuard.TooDeep> { JsonGuard.requireShallow(deep(JsonGuard.MAX_DEPTH + 1)) }
    }

    /** 它是 IllegalArgumentException，所以会被统一映射成 400，而不是 500 */
    @Test
    fun `too deep is a client error type`() {
        assertFailsWith<IllegalArgumentException> { JsonGuard.requireShallow(deep(1_000)) }
    }

    /** 深度超限时要在**解析之前**抛出，不能让递归先跑起来 */
    @Test
    fun `the bomb never reaches the parser`() {
        val bomb = deep(200_000)
        val started = System.nanoTime()
        assertFailsWith<JsonGuard.TooDeep> { JsonGuard.requireShallow(bomb) }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(ms < 2_000, "扫描用了 ${ms}ms，像是走进了递归解析")
    }
}

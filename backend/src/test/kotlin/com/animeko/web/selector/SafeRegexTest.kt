package com.animeko.web.selector

import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 配置正则的执行预算。输入长度上限挡不住指数级回溯，真正兜底的是这一次次字符读取的预算。
 */
class SafeRegexTest {

    @Test
    fun `the character budget aborts a match instead of hanging on exponential backtracking`() {
        val catastrophic = assertNotNull(SafeRegex.compile("(a+)+$"))
        // 30 个 a 的失败匹配是 2^30 级别的回溯；没有预算时要跑好几秒，有预算时立即放弃
        val text = "a".repeat(30) + "!"
        val started = System.nanoTime()
        assertFalse(SafeRegex.contains(catastrophic, text, maxChecks = 20_000))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(ms < 1_000, "预算没有生效：${ms}ms 才返回")
    }

    @Test
    fun `a budget smaller than the input makes the same pattern report no match`() {
        val pattern = assertNotNull(SafeRegex.compile("abc"))
        assertTrue(SafeRegex.contains(pattern, "abc", maxChecks = 100))
        // 读一个字就要扣一次预算：两次读不可能完成 "abc"
        assertFalse(SafeRegex.contains(pattern, "abc", maxChecks = 2))
    }

    @Test
    fun `group returns null on no match or exhausted budget`() {
        val pattern = assertNotNull(SafeRegex.compile("第\\s*(?<ep>.+)\\s*[话集]"))
        assertEquals("12", SafeRegex.group(pattern, "第12话", "ep")?.trim())
        assertNull(SafeRegex.group(pattern, "没有集号", "ep"))
        assertNull(SafeRegex.group(pattern, "第12话", "ep", maxChecks = 1))
    }

    @Test
    fun `groupOrText distinguishes no match from an empty group`() {
        val pattern = assertNotNull(SafeRegex.compile("^(?<ch>.+?)(\\d+)?$"))
        assertEquals("线路", SafeRegex.groupOrText(pattern, "线路12", "ch", "全文"))
        // 匹配成功但没有 ch 组：退回到全文，而不是丢掉这一项
        val noGroup = assertNotNull(SafeRegex.compile("^.+$"))
        assertEquals("全文", SafeRegex.groupOrText(noGroup, "任意", "ch", "全文"))
        // 完全不匹配：null（调用方据此丢弃）
        val mismatch = assertNotNull(SafeRegex.compile("^z+$"))
        assertNull(SafeRegex.groupOrText(mismatch, "abc", "ch", "全文"))
    }
}

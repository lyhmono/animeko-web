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
        // 注意用三层嵌套：实测 JDK21 对 `(a+)+$` 有优化（30 个 a 也只要 1ms），
        // 拿它写测试会变成一条什么都没证明的绿灯；`((a+)+)+$` 才会真的跑不完
        val catastrophic = assertNotNull(SafeRegex.compile("^((a+)+)+$"))
        val text = "a".repeat(40) + "!"
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

    @Test
    fun `a shared budget is consumed across calls`() {
        // 页面里的候选 URL 有很多条，预算必须是一整次解析共享的；
        // 每条候选各拿 2M 额度，恶意页面就能用几千条 4KB 候选把 CPU 按住几十秒。
        val budget = SafeRegex.Budget(100)
        val pattern = assertNotNull(SafeRegex.compile("^abc$"))
        assertTrue(SafeRegex.contains(pattern, "abc", budget = budget))
        assertTrue(budget.remaining[0] < 100, "第一次匹配没有从共享预算里扣次数")

        // 把剩下的额度烧光后，后续候选必须立刻按「没命中」处理
        assertFalse(
            SafeRegex.contains(
                assertNotNull(SafeRegex.compile("^((a+)+)+$")),
                "a".repeat(40) + "!",
                maxChecks = 10_000,
                budget = budget,
            ),
        )
        assertTrue(budget.exhausted)
        assertFalse(SafeRegex.contains(pattern, "abc", budget = budget))
    }

    @Test
    fun `groupOrText falls back instead of dropping a channel when the budget runs out`() {
        // 线路名如果因为预算耗尽返回 null，mapNotNull 会丢项，lists.zip(channelNames)
        // 就会把后面的剧集挂错线路；这里必须退回到全文保住下标。
        // 同样用三层嵌套，避免 JDK 对 `(a+)+$` 的优化让这条用例空跑。
        val catastrophic = assertNotNull(SafeRegex.compile("^(?<ch>(a+)+)+$"))
        assertEquals(
            "全文",
            SafeRegex.groupOrText(catastrophic, "a".repeat(40) + "!", "ch", "全文", maxChecks = 20_000),
        )
    }
}

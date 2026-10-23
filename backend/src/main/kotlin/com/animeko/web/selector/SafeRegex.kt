package com.animeko.web.selector

import java.util.regex.Pattern

/**
 * 配置正则的安全执行器。
 *
 * 为什么单纯限制输入长度不够：`(a+)+$` 在 100 个 `a` 上回溯约 2^100 步，
 * 4096 的长度上限根本挡不住，而 JVM 的 java.util.regex 没有超时。
 * 这类正则与页面内容都来自第三方（社区订阅 + 源站页面），一个请求就能
 * 把 Ktor 工作线程永久占住。
 *
 * 做法：把输入包一层带预算的 CharSequence。正则引擎每读一个字符都要经过
 * `charAt`，超过预算就抛异常中止这一次匹配，按「没匹配到」处理。
 * 正常正则对 N 字符的输入只做 O(N) 次读取；预算按绝对次数给，远大于任何
 * 真实配置的需要，同时让指数级回溯在毫秒级撞线。
 */
object SafeRegex {
    /**
     * 单次匹配允许的字符读取次数。4096 字符的 URL、512 字符的剧集名
     * 在正常正则下都远低于这个量级；2_000_000 留了两个数量级以上的余量。
     */
    private const val MAX_CHECKS = 2_000_000

    private class BudgetExceeded : RuntimeException()

    /**
     * 一次解析共享的字符预算。
     *
     * 每次 `contains` 都新建预算的话，一份页面里的 N 条候选 URL 就有 N 份 2M 的额度；
     * 恶意页面可以塞几千条 4KB 的候选，每条都烧满预算，单次解析在 CPU 上停几十秒。
     * 调用方在一次解析开始时创建一个 Budget，让所有候选一起花这笔钱。
     * 非线程安全：只在单个请求/解析内使用。
     */
    class Budget(maxChecks: Int = MAX_CHECKS) {
        internal val remaining = intArrayOf(maxChecks)

        val exhausted: Boolean get() = remaining[0] <= 0
    }

    private class BudgetedText(
        private val text: CharSequence,
        private val remaining: IntArray,
    ) : CharSequence {
        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            if (--remaining[0] < 0) throw BudgetExceeded()
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            BudgetedText(text.subSequence(startIndex, endIndex), remaining)

        override fun toString(): String = text.toString()
    }

    /** 编译失败（非法正则）返回 null：运行期与导入校验共用同一条路径 */
    fun compile(pattern: String): Pattern? = runCatching { Pattern.compile(pattern) }.getOrNull()

    /** 等价于 `pattern.matcher(text).find()`，但回溯超出预算时按「没命中」返回 */
    fun contains(
        pattern: Pattern,
        text: String,
        maxChecks: Int = MAX_CHECKS,
        budget: Budget? = null,
    ): Boolean = try {
        pattern.matcher(BudgetedText(text, budget?.remaining ?: intArrayOf(maxChecks))).find()
    } catch (e: BudgetExceeded) {
        false
    }

    /** 取命名组的值；没匹配、组不存在或超出预算都返回 null */
    fun group(
        pattern: Pattern,
        text: String,
        group: String,
        maxChecks: Int = MAX_CHECKS,
        budget: Budget? = null,
    ): String? = try {
        val matcher = pattern.matcher(BudgetedText(text, budget?.remaining ?: intArrayOf(maxChecks)))
        if (!matcher.find()) null else runCatching { matcher.group(group) }.getOrNull()
    } catch (e: BudgetExceeded) {
        null
    }

    /**
     * 与 [group] 的区别：匹配成功但命名组为空时退回 [fallback]。
     * 线路名格式的语义是「匹配不上就丢掉这条元素，匹配上但取不出 ch 就用全文」，
     * 两种 null 不能混为一谈。
     *
     * 预算耗尽也退回 [fallback]：如果这里返回 null，调用方按线路下标对齐时
     * 会以为这条线路不存在，后面整组剧集被挂到别的线路名下 ——
     * 正则没跑完就变成「A 线路的剧集挂到 B 线路名下」，比少一个线路名糟得多。
     */
    fun groupOrText(
        pattern: Pattern,
        text: String,
        group: String,
        fallback: String,
        maxChecks: Int = MAX_CHECKS,
        budget: Budget? = null,
    ): String? = try {
        val matcher = pattern.matcher(BudgetedText(text, budget?.remaining ?: intArrayOf(maxChecks)))
        if (!matcher.find()) null
        else runCatching { matcher.group(group) }.getOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: fallback
    } catch (e: BudgetExceeded) {
        fallback
    }
}

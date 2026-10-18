package com.animeko.web.selector

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * 极简 JsonPath 求值器，覆盖 animeko selector 配置用到的子集：
 *
 * - `$`                        根
 * - `.key` / `['key']`         子属性（对数组自动映射到每个元素，与 Jayway 一致）
 * - `['k1','k2']`              多属性
 * - `[*]` / `.*`               数组全部元素 / 对象全部值
 * - `[0]`                      数组下标
 * - `..key`                    深度扫描（递归查找所有名为 key 的值）
 */
object JsonPathEval {

    fun evaluate(path: String, root: JsonElement): List<JsonElement>? {
        val p = path.trim()
        if (!p.startsWith("$")) return null
        if (p == "$") return listOf(root)
        var current: List<JsonElement> = listOf(root)
        var i = 1
        while (i < p.length) {
            when (p[i]) {
                '.' -> {
                    if (i + 1 < p.length && p[i + 1] == '.') {
                        // 深度扫描: ..key
                        i += 2
                        val (key, next) = readKey(p, i)
                        if (key.isEmpty()) return null
                        current = current.flatMap { deepScan(it, key) }
                        i = next
                        continue
                    }
                    i++
                    if (i < p.length && p[i] == '*') {
                        current = current.flatMap { children(it) }
                        i++
                        continue
                    }
                    val (key, next) = readKey(p, i)
                    if (key.isEmpty()) return null
                    current = current.flatMap { property(it, key) }
                    i = next
                }

                '[' -> {
                    val close = findClosingBracket(p, i) ?: return null
                    val inner = p.substring(i + 1, close).trim()
                    current = applyBracket(current, inner) ?: return null
                    i = close + 1
                }

                else -> return null
            }
        }
        return current
    }

    private fun applyBracket(current: List<JsonElement>, inner: String): List<JsonElement>? {
        if (inner == "*") return current.flatMap { children(it) }
        // 多属性: 'a','b' 或 "a","b"。这里保持 Jayway 语义：一个元素命中几个键就贡献几项、
        // 一个都不命中贡献 0 项。需要「按位置配对」的调用方请用 evaluateAligned()，
        // 不要在通用求值器里偷偷改语义 —— 那会让所有其它路径的行为变得不可预期。
        if (inner.contains("'") || inner.contains("\"")) {
            val keys = multiKeyNames(inner) ?: return null
            return current.flatMap { e -> keys.flatMap { property(e, it) } }
        }
        // 下标
        inner.toIntOrNull()?.let { idx ->
            return current.mapNotNull { e -> (e as? JsonArray)?.getOrNull(idx) }
        }
        // 单 key（无引号）
        return current.flatMap { property(it, inner) }
    }

    private fun multiKeyNames(inner: String): List<String>? {
        if (!(inner.contains("'") || inner.contains("\""))) return null
        val keys = Regex("""['"]([^'"]+)['"]""").findAll(inner).map { it.groupValues[1] }.toList()
        return keys.ifEmpty { null }
    }

    /**
     * 与 [evaluate] 同，但**结尾的多属性选择器按元素对齐**：
     * 每个元素恰好贡献一项（取第一个命中的键，都没有则贡献 JsonNull 占格）。
     *
     * 存在的理由：数据源的 `selectNames` / `selectLinks` 形如
     * `$[*]['title','name']` 与 `$[*]['url','link']`，调用方是**按下标配对**名字与地址的。
     * 按 Jayway 语义，缺键的元素贡献 0 项、同时带两个键的元素贡献 2 项，于是两条列表长度
     * 不同、整体错位 —— 症状不是报错而是「显示 A 的名字，点进去播 B 的地址」这种静默错结果。
     * 补一个占位格让上层按「空名字」丢掉那一条，后面的条目就不会前移。
     */
    fun evaluateAligned(path: String, root: JsonElement): List<JsonElement>? {
        val p = path.trim()
        // 这里以前是 Regex("""^(.+)\[(.+)\]$""").matchEntire(p)。
        // 两个贪婪 .+ 加行尾锚点在最坏情况下是**平方级**回溯：实测长度 20001 要 2.7s、
        // 40001 要 10.5s、80001 要 42s，而选择器字符串来自第三方配置、每次搜索都会重新跑一遍
        // ——一份 `selectNames = "$[[[[[…"` 的配置就能把这台机器的 CPU 按住。
        // 语义与那条正则完全一致（贪婪的 group1 恰好停在**最后一个** `[`），但不需要回溯。
        val close = p.length - 1
        val open = p.lastIndexOf('[')
        if (open <= 0 || close <= open || p[close] != ']') return evaluate(p, root)
        val prefix = p.substring(0, open)
        val keys = multiKeyNames(p.substring(open + 1, close))
        if (keys.isNullOrEmpty()) return evaluate(p, root)
        val elements = evaluate(prefix, root) ?: return null
        return elements.flatMap { alignedFor(it, keys) }
    }

    /**
     * 对一个元素产出「每个叶子一条」的结果：对象贡献 1 条（取第一个命中的键，
     * 全都没有则 JsonNull 占格）；数组递归到它的每个叶子。
     *
     * 必须递归展开数组：默认选择器 `$[*]['title','name']` 作用在
     * `{"code":0,"data":[{...},{...}]}` 这种根上时，`$[*]` 给出的是**数组本身**，
     * 旧实现正是靠 property() 对数组的自动展开才拿到两项。只按「一个元素一条」
     * 处理会把整个数组压成一条，反而丢数据。
     */
    private fun alignedFor(e: JsonElement, keys: List<String>): List<JsonElement> = when (e) {
        is JsonArray -> e.flatMap { alignedFor(it, keys) }
        is JsonObject -> listOf(
            keys.asSequence()
                .mapNotNull { e[it] }
                .firstOrNull() ?: JsonNull,
        )
        else -> emptyList()
    }

    /** 属性访问：对象取键值；数组自动映射到每个元素（Jayway 语义） */
    private fun property(e: JsonElement, key: String): List<JsonElement> = when (e) {
        is JsonObject -> listOfNotNull(e[key])
        is JsonArray -> e.flatMap { property(it, key) }
        else -> emptyList()
    }

    private fun children(e: JsonElement): List<JsonElement> = when (e) {
        is JsonArray -> e.toList()
        is JsonObject -> e.values.toList()
        else -> emptyList()
    }

    private fun deepScan(e: JsonElement, key: String): List<JsonElement> {
        val result = ArrayList<JsonElement>()
        fun walk(el: JsonElement) {
            when (el) {
                is JsonObject -> {
                    el[key]?.let { result.add(it) }
                    el.values.forEach { walk(it) }
                }

                is JsonArray -> el.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(e)
        return result
    }

    private fun readKey(p: String, start: Int): Pair<String, Int> {
        var i = start
        val sb = StringBuilder()
        while (i < p.length && (p[i].isLetterOrDigit() || p[i] == '_' || p[i] == '-')) {
            sb.append(p[i])
            i++
        }
        return sb.toString() to i
    }

    private fun findClosingBracket(p: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < p.length) {
            when (p[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** 取出字符串值（数组取第一个、对象取第一个 value），与 animeko 的取值语义一致 */
    fun singleString(e: JsonElement?): String? = when (e) {
        is JsonArray -> e.firstOrNull()?.let { singleString(it) }
        is JsonObject -> e.values.firstOrNull()?.let { singleString(it) }
        // JSON 的 null 也是 JsonPrimitive，content 会给出字面串 "null"，
        // 于是空字段能被当成有效标题/链接；contentOrNull 才对 null 返回 null
        is JsonPrimitive -> e.contentOrNull
        else -> null
    }
}

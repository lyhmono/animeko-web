package com.animeko.web.selector

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
        // 多属性: 'a','b' 或 "a","b"
        if (inner.contains("'") || inner.contains("\"")) {
            val keys = Regex("""['"]([^'"]+)['"]""").findAll(inner).map { it.groupValues[1] }.toList()
            if (keys.isEmpty()) return null
            return current.flatMap { e -> keys.flatMap { property(e, it) } }
        }
        // 下标
        inner.toIntOrNull()?.let { idx ->
            return current.mapNotNull { e -> (e as? JsonArray)?.getOrNull(idx) }
        }
        // 单 key（无引号）
        return current.flatMap { property(it, inner) }
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
        is JsonPrimitive -> e.content
        else -> null
    }
}

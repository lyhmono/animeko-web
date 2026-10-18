package com.animeko.web.http

/**
 * 入站 JSON 的嵌套深度闸门。
 *
 * kotlinx 的树解析器是**递归**的：`[[[[…]]]]` 递归多深就吃多深的 Java 栈，
 * 20 万层（400KB，正好在本服务 1MB 请求体上限之内）会把栈打穿，抛出
 * StackOverflowError。而 Error 不是 Exception —— 代码里所有
 * `runCatching { parseToJsonElement(...) }` 都接不住它，于是：
 *  - 一个畸形请求体变成 500（正确答案是 400：内容坏的是来访者的请求）；
 *  - 每次都在日志里落下上千行栈。实测一次 PUT 写 1026 行，
 *    批量打这个接口等于用日志把这台机器的磁盘灌满。
 *
 * 所以解析前先扫一遍深度。扫描是单遍 O(n)、不分配，相对解析本身可以忽略。
 */
object JsonGuard {
    /**
     * 128 层对本服务的实际数据（收藏/历史/进度、数据源配置、订阅清单）远远够用
     * —— 那些结构最深也就五六层。留出这么大余量是为了不与任何合法写法过不去，
     * 而不是鼓励深嵌套。
     */
    const val MAX_DEPTH = 128

    class TooDeep(message: String) : IllegalArgumentException(message)

    /** 深度超限抛 [TooDeep]（它是 IllegalArgumentException，会被统一映射成 400）。 */
    fun requireShallow(text: String, maxDepth: Int = MAX_DEPTH) {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                // 字符串里的 [ ] { } 是内容不是结构：`{"a":"[[[[["}` 完全合法且很浅
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '[', '{' -> {
                    depth++
                    if (depth > maxDepth) {
                        throw TooDeep("JSON 嵌套超过 $maxDepth 层，拒绝解析")
                    }
                }
                // 这里刻意不为闭包做配平检查：括号不配对是 JSON 语法错误，
                // 交给真正的解析器去报，本函数只负责「深度」这一件事。
                ']', '}' -> if (depth > 0) depth--
            }
        }
    }
}

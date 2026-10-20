package com.animeko.web.http

/**
 * 入站 JSON 的嵌套深度闸门。
 *
 * kotlinx 的树解析器是**递归**的：`[[[[…]]]]` 递归多深就吃多深的 Java 栈，
 * 20 万层（400KB，正好在本服务 1MB 请求体上限之内）会把栈打穿，抛出
 * StackOverflowError。为什么不能靠下游兜着，两条实测（本轮跑出来的）：
 *  · `catch (e: Exception)` 接不住它 —— 它是 Error。SubscriptionService /
 *    SelectorEngine 里那两处显式 catch Exception 的地方，删掉本闸门后
 *    SOE 会一路穿到 StatusPages；
 *  · `runCatching { … }` **接得住**（它 catch 的是 Throwable），而且接住之后的
 *    行为更糟：UserDataStore.read 会把这份「解析失败的合法 JSON」当坏文件
 *    搬成 *.bad、对外回一份 {}，客户端拿到 200 就以为同步过了，
 *    下一次写入直接把用户的整份存档覆盖掉。
 * 所以「反正有 runCatching / StatusPages 兜底」是不成立的：一个变成 500 +
 * 上千行栈的日志放大，另一个变成静默的数据覆盖。两道兜底都保留，
 * 但闸门必须设在解析之前。
 *
 * 扫描是单遍 O(n)、不分配，相对解析本身可以忽略。
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

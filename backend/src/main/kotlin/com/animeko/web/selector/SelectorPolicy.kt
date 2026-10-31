package com.animeko.web.selector

/** Reject jsoup's unbudgeted regex entry points; never rewrite a user's regex dialect. */
internal object SelectorPolicy {
    private const val MAX_QUERY_LENGTH = 2_048
    private const val MAX_NESTING = 16
    private const val MAX_OPERATORS = 64
    private val regexPseudos = setOf("matches", "matchesown", "matcheswholetext", "matcheswholeowntext")

    fun isXPath(selector: String): Boolean {
        val s = selector.trim()
        return s.startsWith("//") || s.startsWith("xpath:") ||
            (s.startsWith('/') && !s.startsWith("/*")) || s.startsWith('(')
    }

    fun requireSafeQuery(selector: String, css: Boolean = !isXPath(selector)) {
        require(selector.length <= MAX_QUERY_LENGTH) { "CSS/XPath 选择器过长（上限 $MAX_QUERY_LENGTH 字符）" }
        var quote: Char? = null
        var depth = 0
        var operators = 0
        var i = 0
        while (i < selector.length) {
            val ch = selector[i]
            if (quote != null) {
                if (ch == '\\') i++ else if (ch == quote) quote = null
            } else {
                when (ch) {
                    '\'', '"' -> quote = ch
                    '\\' -> require(!css) { "CSS 转义选择器未开放，请改用字面选择器（避免绕过内嵌正则安全检查）" }
                    '(', '[' -> {
                        depth++
                        operators++
                        require(depth <= MAX_NESTING) { "CSS/XPath 选择器嵌套过深（上限 $MAX_NESTING 层）" }
                    }
                    ')', ']' -> depth--
                    '>', '+', ',', '|', '/' -> operators++
                    '~' -> {
                        require(!css || selector.getOrNull(i + 1) != '=') { cssRegexMessage }
                        operators++
                    }
                    ':' -> {
                        operators++
                        var end = i + 1
                        while (end < selector.length && selector[end].isLetter()) end++
                        require(!css || selector.substring(i + 1, end).lowercase() !in regexPseudos) { cssRegexMessage }
                    }
                }
                if (ch.isWhitespace() && i > 0 && !selector[i - 1].isWhitespace()) operators++
                require(operators <= MAX_OPERATORS) { "CSS/XPath 选择器过于复杂（上限 $MAX_OPERATORS 个操作）" }
            }
            i++
        }
    }

    fun validate(cfg: SelectorSearchConfig) {
        listOf(
            cfg.selectorSubjectFormatA.selectLists,
            cfg.selectorSubjectFormatIndexed.selectNames,
            cfg.selectorSubjectFormatIndexed.selectLinks,
            cfg.selectorChannelFormatNoChannel.selectEpisodes,
            cfg.selectorChannelFormatNoChannel.selectEpisodeLinks,
            cfg.selectorChannelFormatFlattened.selectChannelNames,
            cfg.selectorChannelFormatFlattened.selectEpisodeLists,
            cfg.selectorChannelFormatFlattened.selectEpisodesFromList,
            cfg.selectorChannelFormatFlattened.selectEpisodeLinksFromList,
        ).forEach { requireSafeQuery(it) }
    }

    private const val cssRegexMessage =
        "CSS 内嵌正则（:matches* / [attr~=regex]）没有安全工作预算，已禁用；请使用字面 CSS 选择器，配置正则的环视语义不变"
}

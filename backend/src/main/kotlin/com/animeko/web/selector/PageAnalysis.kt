package com.animeko.web.selector

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node

/** One page-local DOM and bounded candidate scan, shared by matching/fallback/nested resolution. */
internal class PageAnalysis(html: String, pageUrl: String, checkCancelled: () -> Unit = {}) {
    data class Metrics(var parses: Int = 0, var rawPasses: Int = 0, var domVisits: Int = 0, var rawMatches: Int = 0)
    val metrics = Metrics()
    val videoCandidates: List<String>
    val nestedCandidates: List<String>

    init {
        checkCancelled()
        val doc = Jsoup.parse(html, pageUrl)
        metrics.parses++
        val frames = LinkedHashSet<String>()
        val links = LinkedHashSet<String>()
        val media = LinkedHashSet<String>()
        var node: Node? = doc
        while (node != null && metrics.domVisits < MAX_DOM_VISITS && frames.size < MAX_CANDIDATES) {
            if (metrics.domVisits++ % 128 == 0) checkCancelled()
            if (node is Element) {
                val attrAndBucket = when (node.normalName()) {
                    "iframe" -> "src" to frames
                    "a" -> "href" to links
                    "video", "source" -> "src" to media
                    else -> null
                }
                if (attrAndBucket != null && attrAndBucket.second.size < MAX_CANDIDATES) {
                    val raw = node.attr(attrAndBucket.first)
                    if (raw.isNotBlank() && raw.length <= MAX_URL_LENGTH) {
                        val absolute = node.absUrl(attrAndBucket.first)
                        if (valid(absolute)) attrAndBucket.second.add(absolute)
                    }
                }
            }
            node = nextNode(node, doc)
        }
        val video = LinkedHashSet<String>()
        val nested = LinkedHashSet<String>()
        fun add(value: String, includeNested: Boolean = true) {
            if (!valid(value)) return
            if (video.size < MAX_CANDIDATES) video.add(value)
            if (includeNested && nested.size < MAX_CANDIDATES) nested.add(value)
        }
        frames.forEach { add(it) }
        links.forEach { add(it) }
        media.forEach { add(it, includeNested = false) }

        fun full() = video.size >= MAX_CANDIDATES && nested.size >= MAX_CANDIDATES
        fun scan(text: String) {
            if (full() || metrics.rawMatches >= MAX_RAW_MATCHES) return
            metrics.rawPasses++
            for (match in URL_PATTERN.findAll(text)) {
                if (metrics.rawMatches >= MAX_RAW_MATCHES) break
                if (metrics.rawMatches++ % 128 == 0) checkCancelled()
                // Avoid copying a gigantic match just to discover that it is not usable.
                if (match.range.last - match.range.first + 1 <= MAX_URL_LENGTH) {
                    add(match.value.trim().trimEnd('`', '}', '{', '(', ')', '.', ',', ';', '!'))
                }
                if (full()) break
            }
        }
        scan(html)
        if (!full() && metrics.rawMatches < MAX_RAW_MATCHES && '\\' in html) {
            checkCancelled()
            scan(unescape(html))
        }
        videoCandidates = video.toList()
        nestedCandidates = nested.toList()
    }

    private fun nextNode(node: Node, root: Node): Node? {
        if (node.childNodeSize() > 0) return node.childNode(0)
        var current = node
        while (current !== root) {
            val sibling: Node? = current.nextSibling()
            if (sibling != null) return sibling
            current = current.parentNode() ?: return null
        }
        return null
    }

    companion object {
        internal const val MAX_CANDIDATES = 5_000
        internal const val MAX_DOM_VISITS = 100_000
        private const val MAX_RAW_MATCHES = 10_000
        private const val MAX_URL_LENGTH = 4_096
        private val URL_PATTERN = Regex("""https?://[^\s"'<>`{}\\)\]]+""")
        private val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")
        private val HEX_ESCAPE = Regex("""\\x([0-9a-fA-F]{2})""")
        private fun valid(value: String): Boolean = value.length in 11..MAX_URL_LENGTH &&
            (value.startsWith("http://") || value.startsWith("https://"))
        private fun unescape(text: String): String = text.replace(UNICODE_ESCAPE) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }.replace(HEX_ESCAPE) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }.replace("\\/", "/")
    }
}

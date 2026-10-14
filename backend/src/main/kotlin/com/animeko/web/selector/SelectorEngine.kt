package com.animeko.web.selector

import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

// 以下模型会经 Ktor call.respond(...) 做 JSON 序列化，必须标注 @Serializable，
// 否则运行时报 "Serializer for class ... is not found"（成功路径 500）
@Serializable
data class SubjectHit(
    val name: String,
    val url: String,
    val cover: String? = null,
)

@Serializable
data class EpisodeHit(
    val channel: String?,
    val name: String,
    /** 集号文本（如 "12"） */
    val sort: String,
    /** 可解析为数字的集号；电影/正片类记为 1 */
    val sortNum: Int?,
    /** 播放页地址 */
    val playUrl: String,
)

@Serializable
data class ChannelGroupHit(
    val name: String,
    val episodes: List<EpisodeHit>,
)

@Serializable
data class EpisodesResult(
    val channels: List<String>,
    val episodes: List<EpisodeHit>,
    val groups: List<ChannelGroupHit>,
)

@Serializable
data class ResolvedVideo(
    val url: String,
    val referer: String,
    val userAgent: String,
    val cookies: String,
)

class SelectorException(message: String) : Exception(message)

/**
 * animeko Selector 数据源引擎的服务端实现。
 *
 * 五步流程：searchSubjects → selectSubjects → searchEpisodes → selectEpisodes → selectMedia。
 * 与 animeko 的差异：animeko 在 App 内用 WebView 拦截网络请求提取视频地址，
 * 服务端版本改为抓取播放页 HTML 后用 matchVideo 正则从候选 URL 中匹配（对绝大多数源有效；
 * 强依赖 JS 执行的源需在播放页手动粘贴视频地址）。
 */
class SelectorEngine(private val fetcher: Fetcher) {

    private val json = Json { ignoreUnknownKeys = true }
    private val lastRequestAt = mutableMapOf<String, Long>()

    // ---- Phase 1 + 2: 搜索并列出条目 ----

    suspend fun search(cfg: SelectorSearchConfig, keyword: String): List<SubjectHit> {
        require(cfg.searchUrl.isNotBlank()) { "searchUrl 未配置" }
        val kw = cfg.resolvedAutoMatch().processKeyword(keyword)
        val url = cfg.searchUrl.replace("{keyword}", URLEncoder.encode(kw, "UTF-8"))
        val body = throttledFetch(cfg, url).text
        val base = baseOf(cfg, url)
        val hits = when (cfg.subjectFormatId) {
            SubjectFormatJsonPathIndexed.ID -> selectJsonPathIndexed(body, cfg, base)
            SubjectFormatIndexed.ID -> selectIndexed(Jsoup.parse(body, base), cfg, base)
            else -> selectA(Jsoup.parse(body, base), cfg, base)
        }
        return if (cfg.resolvedAutoMatch().preferShorterName) {
            hits.sortedBy { it.name.length }
        } else {
            hits
        }
    }

    // ---- Phase 3 + 4: 详情页列出线路与剧集 ----

    suspend fun episodes(cfg: SelectorSearchConfig, subjectUrl: String): EpisodesResult {
        require(subjectUrl.startsWith("http")) { "subjectUrl 必须是绝对地址" }
        val body = fetcher.fetch(subjectUrl, mapOf("User-Agent" to cfg.userAgentOrDefault())).text
        val base = baseOf(cfg, subjectUrl)
        val doc = Jsoup.parse(body, base)
        return when (cfg.channelFormatId) {
            ChannelFormatIndexGrouped.ID -> selectIndexGrouped(doc, cfg, base)
            else -> selectNoChannel(doc, cfg, base)
        }
    }

    // ---- Phase 5: 从播放页提取视频地址 ----

    suspend fun resolveVideo(cfg: SelectorSearchConfig, playUrl: String): ResolvedVideo {
        require(playUrl.startsWith("http")) { "playUrl 必须是绝对地址" }
        val mv = cfg.matchVideo
        val ua = mv.addHeadersToVideo.userAgent.ifBlank { cfg.userAgentOrDefault() }
        val referer = mv.addHeadersToVideo.referer.ifBlank { baseOf(cfg, playUrl) }
        val cookies = mv.cookies.lines()
            .map { it.trim() }
            .filter { it.contains("=") }
            .joinToString("; ")
        val headers = buildMap {
            put("User-Agent", ua)
            put("Referer", referer)
            if (cookies.isNotBlank()) put("Cookie", cookies)
        }
        val videoRegex = runCatching { Regex(mv.matchVideoUrl) }.getOrNull()
            ?: throw SelectorException("matchVideoUrl 正则无效")
        val nestedRegex = runCatching { Regex(mv.matchNestedUrl) }.getOrNull()

        val html = fetcher.fetch(playUrl, headers).text
        findVideoUrl(html, playUrl, videoRegex)?.let {
            return ResolvedVideo(it, referer, ua, cookies)
        }
        if (mv.enableNestedUrl && nestedRegex != null) {
            // 嵌套候选：DOM 中的 iframe/a/video/source 绝对地址 + 原文中的绝对 URL
            val doc = Jsoup.parse(html, playUrl)
            val candidates = LinkedHashSet<String>()
            doc.select("iframe[src]").forEach { candidates.add(it.absUrl("src")) }
            doc.select("a[href]").forEach { candidates.add(it.absUrl("href")) }
            candidateUrls(html).forEach { candidates.add(it) }
            val nested = candidates.filter { it.startsWith("http") && nestedRegex.containsMatchIn(it) }
                .take(10)
            for (n in nested) {
                val sub = runCatching { fetcher.fetch(n, headers).text }.getOrNull() ?: continue
                findVideoUrl(sub, n, videoRegex)?.let {
                    return ResolvedVideo(it, referer, ua, cookies)
                }
            }
        }
        throw SelectorException("未能从播放页提取视频地址（NO_VIDEO_URL_FOUND）")
    }

    // ---------- 条目格式实现 ----------

    private fun selectA(doc: Document, cfg: SelectorSearchConfig, base: String): List<SubjectHit> {
        val c = cfg.selectorSubjectFormatA
        val elements = queryAll(doc, c.selectLists) ?: return emptyList()
        return elements.mapNotNull { e ->
            val name = e.attr("title").ifBlank { e.text().trim() }
            val href = e.attr("href")
            if (name.isBlank() || href.isBlank()) null
            else SubjectHit(name, applyLink(c.linkTemplate, href, base))
        }
    }

    private fun selectIndexed(doc: Document, cfg: SelectorSearchConfig, base: String): List<SubjectHit> {
        val c = cfg.selectorSubjectFormatIndexed
        val names = queryAll(doc, c.selectNames)?.map { it.text().trim() }?.filter { it.isNotBlank() }
            ?: return emptyList()
        val links = queryAll(doc, c.selectLinks)?.map { it.attr("href") }?.filter { it.isNotBlank() }
            ?: return emptyList()
        return names.zip(links) { name, href -> SubjectHit(name, applyLink(c.linkTemplate, href, base)) }
    }

    private fun selectJsonPathIndexed(body: String, cfg: SelectorSearchConfig, base: String): List<SubjectHit> {
        val c = cfg.selectorSubjectFormatJsonPathIndexed
        val root: JsonElement = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            return emptyList()
        }
        val urls = JsonPathEval.evaluate(c.selectLinks, root)
            ?.mapNotNull { JsonPathEval.singleString(it) }
            ?.filter { it.isNotBlank() }
            ?: return emptyList()
        val names = JsonPathEval.evaluate(c.selectNames, root)
            ?.mapNotNull { JsonPathEval.singleString(it) }
            ?.filter { it.isNotBlank() }
            ?: return emptyList()
        return names.zip(urls) { name, href -> SubjectHit(name, applyLink(c.linkTemplate, href, base)) }
    }

    // ---------- 线路/剧集格式实现 ----------

    private fun selectNoChannel(doc: Document, cfg: SelectorSearchConfig, base: String): EpisodesResult {
        val c = cfg.selectorChannelFormatNoChannel
        val regex = runCatching { Regex(c.matchEpisodeSortFromName) }.getOrNull()
            ?: return EpisodesResult(emptyList(), emptyList(), emptyList())
        val elements = queryAll(doc, c.selectEpisodes) ?: return EpisodesResult(emptyList(), emptyList(), emptyList())
        val links = if (c.selectEpisodeLinks.isNotBlank()) {
            queryAll(doc, c.selectEpisodeLinks)?.map { it.attr("href") }
        } else {
            null
        }
        val eps = elements.mapIndexedNotNull { index, e ->
            val name = e.text().trim()
            if (name.isBlank()) return@mapIndexedNotNull null
            val href = links?.getOrNull(index) ?: e.attr("href")
            if (href.isBlank()) return@mapIndexedNotNull null
            EpisodeHit(
                channel = null,
                name = name,
                sort = sortText(name, regex),
                sortNum = sortNum(name, regex),
                playUrl = absUrl(base, href),
            )
        }
        return EpisodesResult(emptyList(), eps, emptyList())
    }

    private fun selectIndexGrouped(doc: Document, cfg: SelectorSearchConfig, base: String): EpisodesResult {
        val c = cfg.selectorChannelFormatFlattened
        val channelRegex = if (c.matchChannelName.isBlank()) {
            null
        } else {
            runCatching { Regex(c.matchChannelName) }.getOrNull()
        }
        val epRegex = runCatching { Regex(c.matchEpisodeSortFromName) }.getOrNull()
            ?: return EpisodesResult(emptyList(), emptyList(), emptyList())
        val channelNames = queryAll(doc, c.selectChannelNames)
            ?.mapNotNull { e ->
                val text = e.text().trim().ifBlank { return@mapNotNull null }
                if (channelRegex == null) text else channelRegex.findGroupOrNull(text, "ch")
            }
            ?: emptyList()
        val lists = queryAll(doc, c.selectEpisodeLists)
            ?: return EpisodesResult(channelNames, emptyList(), emptyList())
        val groups = lists.zip(channelNames).mapNotNull { (list, channelName) ->
            val links = if (c.selectEpisodeLinksFromList.isNotBlank()) {
                queryAll(list, c.selectEpisodeLinksFromList)?.map { it.attr("href") }
            } else {
                null
            }
            val eps = (queryAll(list, c.selectEpisodesFromList) ?: emptyList()).mapIndexedNotNull { index, e ->
                val name = e.text().trim()
                if (name.isBlank()) return@mapIndexedNotNull null
                val href = links?.getOrNull(index) ?: e.attr("href")
                if (href.isBlank()) return@mapIndexedNotNull null
                EpisodeHit(
                    channel = channelName,
                    name = name,
                    sort = sortText(name, epRegex),
                    sortNum = sortNum(name, epRegex),
                    playUrl = absUrl(base, href),
                )
            }
            ChannelGroupHit(channelName, eps)
        }
        return EpisodesResult(channelNames, groups.flatMap { it.episodes }, groups)
    }

    // ---------- 视频地址提取 ----------

    private fun findVideoUrl(html: String, pageUrl: String, videoRegex: Regex): String? {
        val doc = Jsoup.parse(html, pageUrl)
        val candidates = LinkedHashSet<String>()
        doc.select("iframe[src]").forEach { candidates.add(it.absUrl("src")) }
        doc.select("a[href]").forEach { candidates.add(it.absUrl("href")) }
        doc.select("video[src], source[src]").forEach { candidates.add(it.absUrl("src")) }
        candidateUrls(html).forEach { candidates.add(it) }
        return candidates.firstOrNull { it.startsWith("http") && videoRegex.containsMatchIn(it) }
    }

    private fun candidateUrls(html: String): List<String> {
        val regex = Regex("""https?://[^\s"'<>\\)\]]+""")
        return regex.findAll(html)
            .map { it.value.trimEnd('.', ',', ';', '!') }
            .filter { it.length > 10 }
            .distinct()
            .toList()
    }

    // ---------- 工具 ----------

    private suspend fun throttledFetch(cfg: SelectorSearchConfig, url: String): HttpResult {
        val key = cfg.searchUrl
        val interval = cfg.requestInterval.coerceAtLeast(0)
        if (interval > 0) {
            synchronized(lastRequestAt) {
                val last = lastRequestAt[key] ?: 0L
                val wait = interval - (System.currentTimeMillis() - last)
                if (wait > 0) Thread.sleep(wait)
                lastRequestAt[key] = System.currentTimeMillis()
            }
        }
        return fetcher.fetch(url, mapOf("User-Agent" to cfg.userAgentOrDefault()))
    }

    private fun applyLink(template: String?, raw: String, base: String): String {
        val link = if (template.isNullOrBlank()) raw else template.replace("{value}", raw)
        return absUrl(base, link)
    }

    private fun sortText(name: String, regex: Regex): String {
        val m = regex.find(name) ?: return name
        val group = runCatching { m.groups["ep"]?.value }.getOrNull()
        return group?.trim()?.takeIf { it.isNotBlank() } ?: name
    }

    private fun sortNum(name: String, regex: Regex): Int? {
        val raw = sortText(name, regex)
        raw.toIntOrNull()?.let { return it }
        if (isPossiblyMovie(name)) return 1
        return null
    }

    companion object {
        fun isPossiblyMovie(name: String): Boolean {
            if (name == "正片" || name == "高清版") return true
            return listOf("2160P", "1440P", "2K", "4K", "1080P", "720P").any { name.contains(it) }
        }

        fun baseOf(cfg: SelectorSearchConfig, requestUrl: String): String {
            if (cfg.rawBaseUrl.isNotBlank()) return cfg.rawBaseUrl
            return try {
                val u = URI(requestUrl)
                "${u.scheme}://${u.authority}"
            } catch (e: Exception) {
                requestUrl
            }
        }

        fun absUrl(base: String, path: String): String {
            if (path.isBlank()) return path
            if (path.startsWith("http://") || path.startsWith("https://")) return path
            if (path.startsWith("//")) return "https:$path"
            return try {
                URI(base).resolve(path).toString()
            } catch (e: Exception) {
                path
            }
        }

        private fun looksLikeXPath(selector: String): Boolean {
            val s = selector.trim()
            return s.startsWith("//") || s.startsWith("xpath:") ||
                (s.startsWith("/") && !s.startsWith("/*")) || s.startsWith("(")
        }

        /** CSS / XPath 自适应查询；JSoup 1.18+ 原生支持 selectXpath */
        fun queryAll(root: Element, selector: String): List<Element>? {
            if (selector.isBlank()) return null
            val xpath = looksLikeXPath(selector)
            return try {
                if (xpath) root.selectXpath(selector).toList() else root.select(selector).toList()
            } catch (e: Exception) {
                try {
                    if (xpath) root.select(selector).toList() else root.selectXpath(selector).toList()
                } catch (e2: Exception) {
                    null
                }
            }
        }

        private fun Regex.findGroupOrNull(text: String, group: String): String? {
            val m = find(text) ?: return null
            val value = runCatching { m.groups[group]?.value }.getOrNull()
            return value?.trim()?.takeIf { it.isNotBlank() } ?: text
        }
    }
}

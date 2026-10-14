package com.animeko.web.selector

import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.UrlGuard
import com.animeko.web.http.UpstreamException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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

/**
 * 播放页解析结果，仅服务端内部使用。
 * referer/userAgent/cookie 交给 ProxySessionStore 换成会话号，
 * 因此这个类型不再出现在任何 API 响应里，也不再标注 @Serializable。
 */
data class ResolvedTarget(
    val url: String,
    val referer: String,
    val userAgent: String,
    val cookie: String,
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
class SelectorEngine(
    private val fetcher: Fetcher,
    private val allowPrivateTargets: Boolean = false,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val lastRequestAt = mutableMapOf<String, Long>()

    // ---- Phase 1 + 2: 搜索并列出条目 ----

    suspend fun search(cfg: SelectorSearchConfig, keyword: String): List<SubjectHit> {
        require(cfg.searchUrl.isNotBlank()) { "searchUrl 未配置" }
        val kw = cfg.resolvedAutoMatch().processKeyword(keyword)
        val url = cfg.searchUrl.replace("{keyword}", URLEncoder.encode(kw, "UTF-8"))
        val body = throttledPage(cfg, url)
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
        val target = UrlGuard.requireNotLiteralInternal(subjectUrl, allowPrivateTargets)
        val body = fetchPage(target, mapOf("User-Agent" to cfg.userAgentOrDefault()), "数据源详情页")
        val base = baseOf(cfg, target)
        val doc = Jsoup.parse(body, base)
        return when (cfg.channelFormatId) {
            ChannelFormatIndexGrouped.ID -> selectIndexGrouped(doc, cfg, base)
            else -> selectNoChannel(doc, cfg, base)
        }
    }

    // ---- Phase 5: 从播放页提取视频地址 ----

    suspend fun resolveVideo(cfg: SelectorSearchConfig, playUrl: String): ResolvedTarget {
        require(playUrl.startsWith("http")) { "playUrl 必须是绝对地址" }
        val target = UrlGuard.requireNotLiteralInternal(playUrl, allowPrivateTargets)
        val mv = cfg.matchVideo
        val ua = mv.addHeadersToVideo.userAgent.ifBlank { cfg.userAgentOrDefault() }
        val referer = mv.addHeadersToVideo.referer.ifBlank { baseOf(cfg, target) }
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

        val html = fetchPage(target, headers, "播放页")
        findVideoUrl(html, target, videoRegex)?.let {
            return ResolvedTarget(it, referer, ua, cookies)
        }
        if (mv.enableNestedUrl && nestedRegex != null) {
            // 嵌套候选：DOM 中的 iframe/a/video/source 绝对地址 + 原文中的绝对 URL
            val doc = Jsoup.parse(html, target)
            val candidates = LinkedHashSet<String>()
            doc.select("iframe[src]").forEach { candidates.add(it.absUrl("src")) }
            doc.select("a[href]").forEach { candidates.add(it.absUrl("href")) }
            candidateUrls(html).forEach { candidates.add(it) }
            val nested = candidates.filter { it.startsWith("http") && nestedRegex.containsMatchIn(it) }
                .take(MAX_NESTED_PAGES)
            val deadline = System.currentTimeMillis() + NESTED_BUDGET_MS
            for (n in nested) {
                // 每个嵌套页都要一次完整抓取（单次超时 20s），不设总预算的话
                // 一次解析能拖到一分多钟，前端早已断开而服务端还在跑。
                if (System.currentTimeMillis() > deadline) break
                val sub = runCatching {
                    // 嵌套地址来自页面内容，同样要过校验：否则播放页里塞一个
                    // iframe="http://127.0.0.1:..." 就能借服务端去敲内网。非法候选直接跳过，
                    // 不让它把整次解析打断。
                    fetchPage(
                        UrlGuard.requireNotLiteralInternal(n, allowPrivateTargets),
                        headers,
                        "嵌套播放页",
                    )
                }.getOrNull() ?: continue
                findVideoUrl(sub, n, videoRegex)?.let {
                    return ResolvedTarget(it, referer, ua, cookies)
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

    private suspend fun throttledPage(cfg: SelectorSearchConfig, url: String): String {
        UrlGuard.requireNotLiteralInternal(url, allowPrivateTargets)
        val interval = cfg.requestInterval.coerceAtLeast(0)
        if (interval > 0) {
            // 锁内只推进「下一次允许请求的时间戳」，等待用 delay 放到锁外。
            // 之前是持锁 Thread.sleep：既阻塞协程所在的 IO 线程，
            // 又让所有数据源的搜索互相排队（A 源的 3 秒间隔会拖住 B 源）。
            val wait = synchronized(lastRequestAt) {
                val now = System.currentTimeMillis()
                val last = lastRequestAt[cfg.searchUrl] ?: 0L
                val planned = maxOf(now, last + interval)
                lastRequestAt[cfg.searchUrl] = planned
                planned - now
            }
            if (wait > 0) delay(wait)
        }
        return fetchPage(url, mapOf("User-Agent" to cfg.userAgentOrDefault()), "数据源搜索页")
    }

    /**
     * 抓取并取文本。连接超时/拒绝这类异常必须包住：否则一路掉进兜底 handler，
     * 前端只看到一个没有上下文的 500，而不是「上游不可用」的 502。
     */
    private suspend fun fetchPage(url: String, headers: Map<String, String>, source: String): String =
        try {
            fetcher.fetch(url, headers).textCapped(source)
        } catch (e: UpstreamException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw UpstreamException("$source 请求失败: ${e.message ?: e::class.simpleName}")
        }

    /**
     * 上游状态码 + 体积双重检查：
     * 非 2xx 时 body 往往是错误页，直接送去正则/选择器既慢又只会得到「没找到」的误导结论；
     * 体积上限则约束住用户自定义正则的输入规模（JVM 正则没有超时，恶意构造的长文本能挂死线程）。
     */
    private fun HttpResult.textCapped(source: String): String {
        if (status !in 200..299) throw UpstreamException("$source 返回 $status")
        if (body.size > MAX_PAGE_BYTES) {
            throw UpstreamException("$source 返回体 ${body.size} 字节，超过 ${MAX_PAGE_BYTES / 1024 / 1024}MB 上限")
        }
        return String(body, Charsets.UTF_8)
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
        /** 抓取页面体积上限：同时约束「用户自定义正则」的输入规模（JVM 正则没有超时） */
        const val MAX_PAGE_BYTES: Long = 8L * 1024 * 1024

        private const val MAX_NESTED_PAGES = 5
        private const val NESTED_BUDGET_MS = 30_000L

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

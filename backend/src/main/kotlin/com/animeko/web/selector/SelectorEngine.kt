package com.animeko.web.selector

import com.animeko.web.appLog
import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.UrlGuard
import com.animeko.web.http.UpstreamException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val names = queryAll(doc, c.selectNames)?.map { it.text().trim() } ?: return emptyList()
        val links = queryAll(doc, c.selectLinks)?.map { it.attr("href") } ?: return emptyList()
        return pairIndexed(names, links, c.linkTemplate, base)
    }

    /**
     * 两条列表按下标配对，且**先配对再丢空**。
     * 之前是各自先 filter 再 zip：图片卡、广告位、「更多」链接常常只有链接没有文字，
     * 少掉一个名字会让后面所有条目整体错位一位 —— 列表显示 A 番，点进去播的是 B，
     * 而且全程不报错，是比失败更糟的静默错误结果。
     */
    private fun pairIndexed(
        names: List<String>,
        links: List<String>,
        linkTemplate: String?,
        base: String,
    ): List<SubjectHit> {
        // 两条列表长度不一致说明有一侧的选择器没匹配到全部条目。zip 会静默截断，
        // 用户只会看到「结果比页面上少几个」且无从下手，这里至少留一条日志。
        if (names.size != links.size) {
            appLog.warn(
                "indexed 条目格式两条列表长度不一致（names={} links={}），多余条目已被丢弃，请检查两条选择器",
                names.size,
                links.size,
            )
        }
        return names.zip(links) { name, href ->
            if (name.isBlank() || href.isBlank()) null
            else SubjectHit(name, applyLink(linkTemplate, href, base))
        }.filterNotNull()
    }

    private fun selectJsonPathIndexed(body: String, cfg: SelectorSearchConfig, base: String): List<SubjectHit> {
        val c = cfg.selectorSubjectFormatJsonPathIndexed
        val root: JsonElement = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            return emptyList()
        }
        // 保留位置（取不到字符串就置空串）再配对，理由同 pairIndexed
        val urls = JsonPathEval.evaluate(c.selectLinks, root)
            ?.map { JsonPathEval.singleString(it).orEmpty() }
            ?: return emptyList()
        val names = JsonPathEval.evaluate(c.selectNames, root)
            ?.map { JsonPathEval.singleString(it).orEmpty() }
            ?: return emptyList()
        return pairIndexed(names, urls, c.linkTemplate, base)
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
     * 抓取并取文本，并逐跳重新校验重定向。
     *
     * 两件事必须自己做，不能交给 HTTP 客户端：
     *  - 体积上限要在读取过程中生效（见 Fetcher.fetchBounded），
     *    否则「上限」只是把整个响应读进堆之后量一下；
     *  - 每一跳都过 UrlGuard。源站 302 指向 169.254.169.254 或 127.0.0.1 时，
     *    自动跟随重定向等于绕过入口校验 —— 社区数据源及其页面上的链接都是别人写的内容。
     *
     * 连接超时/拒绝这类异常也要包住：否则一路掉进兜底 handler，
     * 前端只看到一个没有上下文的 500，而不是「上游不可用」的 502。
     */
    private suspend fun fetchPage(url: String, headers: Map<String, String>, source: String): String {
        var target = guard(url)
        var hops = 0
        while (true) {
            val result = try {
                fetcher.fetchBounded(target, headers, MAX_PAGE_BYTES)
            } catch (e: UpstreamException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw UpstreamException("$source 请求失败: ${e.message ?: e::class.simpleName}")
            }
            if (result.status !in 301..399) {
                if (result.status !in 200..299) throw UpstreamException("$source 返回 ${result.status}")
                return String(result.body, Charsets.UTF_8)
            }
            val location = result.header("location")
                ?: throw UpstreamException("$source 返回 ${result.status} 但没有 Location")
            if (hops >= MAX_PAGE_REDIRECTS) throw UpstreamException("$source 重定向次数过多")
            hops++
            target = guard(absUrl(target, location))
        }
    }

    /**
     * 出站点校验：会真的解析 DNS。
     * 之前用的是不解析的 requireNotLiteralInternal，一个「公网域名 -> 内网 IP」
     * 的解析记录就能绕过，而数据源与它页面里的链接都是社区内容，不可信。
     * 解析不出来时 UrlGuard 放行（交给真实请求去失败），所以测试里的 .test 保留域名不受影响。
     * 阻塞的解析放在 IO 线程上做。
     */
    private suspend fun guard(url: String): String =
        withContext(Dispatchers.IO) {
            // 原样抛 Blocked：StatusPages 会对外回 403 + 一句通用文案。
            // 在这里换成 SelectorException 会把拒绝原因（含「主机名 -> 内网 IP」）
            // 回显给任何能连到这台机器的人，那是网络拓扑泄露。
            UrlGuard.requireAllowed(url, allowPrivateTargets)
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
        private const val MAX_PAGE_REDIRECTS = 5

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

package com.animeko.web.selector

import com.animeko.web.appLog
import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.JsonGuard
import com.animeko.web.http.UrlGuard
import com.animeko.web.http.UpstreamException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.regex.Pattern

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
    /** 数据源显式声明的、允许接收 Cookie 的额外主机（不含媒体主机本身） */
    val cookieHosts: Set<String> = emptySet(),
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
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val guardUrl: suspend (String, Boolean) -> String = { url, allowPrivate ->
        SelectorUrlGuard.shared.requireAllowed(url, allowPrivate)
    },
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val sourceThrottle = SourceThrottle(monotonicMillis)
    private data class SearchKey(val config: SelectorSearchConfig, val keyword: String)
    private val searches = SingleFlight<SearchKey, List<SubjectHit>>()

    // ---- Phase 1 + 2: 搜索并列出条目 ----

    suspend fun search(cfg: SelectorSearchConfig, keyword: String): List<SubjectHit> {
        require(cfg.searchUrl.isNotBlank()) { "searchUrl 未配置" }
        currentCoroutineContext().ensureActive()
        val kw = cfg.resolvedAutoMatch().processKeyword(keyword)
        return searches.run(SearchKey(cfg, kw)) { searchProcessed(cfg, kw) }
    }

    /** Explicit internal refresh bypass; it still observes the source's admission interval. */
    internal suspend fun searchFresh(cfg: SelectorSearchConfig, keyword: String): List<SubjectHit> {
        require(cfg.searchUrl.isNotBlank()) { "searchUrl 未配置" }
        currentCoroutineContext().ensureActive()
        return searchProcessed(cfg, cfg.resolvedAutoMatch().processKeyword(keyword))
    }

    private suspend fun searchProcessed(cfg: SelectorSearchConfig, kw: String): List<SubjectHit> {
        val url = cfg.searchUrl.replace("{keyword}", URLEncoder.encode(kw, "UTF-8"))
        val page = fetchPage(cfg, url, mapOf("User-Agent" to cfg.userAgentOrDefault()), "数据源搜索页")
        val body = page.body
        val base = baseOf(cfg, page.finalUrl)
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
        val page = fetchPage(cfg, target, mapOf("User-Agent" to cfg.userAgentOrDefault()), "数据源详情页")
        val base = baseOf(cfg, page.finalUrl)
        val doc = Jsoup.parse(page.body, base)
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
        val cookieHosts = mv.cookieHosts.mapNotNull { raw ->
            val text = raw.trim()
            if (text.isEmpty() || text.contains('/') || text.contains(' ') || text.contains('@')) {
                null
            } else {
                UrlGuard.hostOf("https://$text")
            }
        }.toSet()
        val headers = buildMap {
            put("User-Agent", ua)
            put("Referer", referer)
            if (cookies.isNotBlank()) put("Cookie", cookies)
        }
        val videoRegex = SafeRegex.compile(mv.matchVideoUrl)
            ?: throw SelectorException("matchVideoUrl 正则无效")
        val nestedRegex = SafeRegex.compile(mv.matchNestedUrl)

        val page = fetchPage(cfg, target, headers, "播放页")
        val resultReferer = mv.addHeadersToVideo.referer.ifBlank { baseOf(cfg, page.finalUrl) }
        fun resolved(url: String) = ResolvedTarget(url, resultReferer, ua, cookies, cookieHosts)
        val budget = SafeRegex.Budget()
        val context = currentCoroutineContext()
        val analysis = PageAnalysis(page.body, page.finalUrl) { context.ensureActive() }
        findVideoUrl(analysis.videoCandidates, videoRegex, budget)?.let { return resolved(it) }
        var fallback = genericVideoUrl(analysis.videoCandidates)
        if (mv.enableNestedUrl && nestedRegex != null) {
            val visited = mutableSetOf(target.substringBefore('#'), page.finalUrl.substringBefore('#'))
            // One real coroutine deadline covers throttling, guards and the complete body read.
            val found = withTimeoutOrNull(NESTED_BUDGET_MS) {
                val nestedContext = currentCoroutineContext()
                val nested = analysis.nestedCandidates.asSequence().filter {
                    nestedContext.ensureActive()
                    it.substringBefore('#') !in visited && !budget.exhausted &&
                        SafeRegex.contains(nestedRegex, it, budget = budget)
                }.take(MAX_NESTED_PAGES)
                for (n in nested) {
                    nestedContext.ensureActive()
                    visited.add(n.substringBefore('#'))
                    val sub = try {
                        // A new DOM base never grants the redirected host the original source Cookie.
                        val sameCookieHost = UrlGuard.hostOf(n) == UrlGuard.hostOf(target) &&
                            !UrlGuard.isInsecureDowngrade(target, n)
                        val nestedHeaders = if (sameCookieHost) headers else headers - "Cookie"
                        fetchPage(cfg, UrlGuard.requireNotLiteralInternal(n, allowPrivateTargets), nestedHeaders, "嵌套播放页")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        continue
                    }
                    visited.add(sub.finalUrl.substringBefore('#'))
                    val subAnalysis = PageAnalysis(sub.body, sub.finalUrl) { nestedContext.ensureActive() }
                    findVideoUrl(subAnalysis.videoCandidates, videoRegex, budget)?.let { return@withTimeoutOrNull it }
                    if (fallback == null) fallback = genericVideoUrl(subAnalysis.videoCandidates)
                }
                null
            }
            if (found != null) return resolved(found)
        }
        fallback?.let {
            // 说清楚走了兜底：这等于「配置里的 CDN 白名单过期了」，
            // 不记下来的话下次源站换域名时只能看到一堆莫名的失败。
            // 但只记到 path：这类地址的 query 里通常就是 sign/token/时间戳，
            // 原样进容器日志等于把可用凭据抄进一份谁都能读的文件
            // （同一个理由已经用在 /api/proxy 的日志上）。
            appLog.info("matchVideoUrl 没有命中，改用通用扩展名兜底：{}", urlForLog(it))
            return resolved(it)
        }
        throw SelectorException("未能从播放页提取视频地址（NO_VIDEO_URL_FOUND）")
    }

    // ---------- 条目格式实现 ----------

    private fun selectA(doc: Document, cfg: SelectorSearchConfig, base: String): List<SubjectHit> {
        val c = cfg.selectorSubjectFormatA
        val elements = queryAll(doc, c.selectLists) ?: return emptyList()
        val siblings = siblingSet(elements)
        return elements.mapNotNull { e ->
            val name = e.attr("title").ifBlank { e.text().trim() }
            val href = e.attr("href")
            if (name.isBlank() || href.isBlank()) null
            else SubjectHit(name, applyLink(c.linkTemplate, href, base), coverOf(e, base, siblings))
        }
    }

    private fun selectIndexed(doc: Document, cfg: SelectorSearchConfig, base: String): List<SubjectHit> {
        val c = cfg.selectorSubjectFormatIndexed
        val names = queryAll(doc, c.selectNames)?.map { it.text().trim() } ?: return emptyList()
        val linkEls = queryAll(doc, c.selectLinks) ?: return emptyList()
        val siblings = siblingSet(linkEls)
        return pairIndexed(
            names,
            linkEls.map { it.attr("href") },
            linkEls.map { coverOf(it, base, siblings) },
            c.linkTemplate,
            base,
        )
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
        covers: List<String?> = emptyList(),
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
        return names.mapIndexedNotNull { i, name ->
            val href = links.getOrNull(i) ?: return@mapIndexedNotNull null
            if (name.isBlank() || href.isBlank()) null
            else SubjectHit(name, applyLink(linkTemplate, href, base), covers.getOrNull(i))
        }
    }

    private suspend fun selectJsonPathIndexed(body: String, cfg: SelectorSearchConfig, base: String): List<SubjectHit> {
        val context = currentCoroutineContext()
        context.ensureActive()
        val c = cfg.selectorSubjectFormatJsonPathIndexed
        val root: JsonElement = try {
            JsonGuard.requireShallow(body)
            json.parseToJsonElement(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyList()
        }
        val budget = JsonPathEval.Budget(checkCancelled = { context.ensureActive() })
        val records = JsonPathEval.indexedValues(c.selectNames, c.selectLinks, root, budget)
            ?: throw SelectorException("JsonPath 选择器无效、记录范围不一致或工作预算耗尽，请检查名称与地址路径")
        return records.mapIndexedNotNull { index, row ->
            ensureAliveEvery(index)
            val name = JsonPathEval.singleString(row.name).orEmpty()
            val href = JsonPathEval.singleString(row.link).orEmpty()
            if (name.isBlank() || href.isBlank()) return@mapIndexedNotNull null
            // Keys fall back within each record, not across the whole result list or root metadata.
            val objectRecord = row.record as? kotlinx.serialization.json.JsonObject
            val cover = COVER_KEYS.asSequence().mapNotNull { key ->
                JsonPathEval.singleString(objectRecord?.get(key))?.trim()?.takeIf { it.isNotEmpty() }
            }.map { absUrl(base, it) }.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
            SubjectHit(name, applyLink(c.linkTemplate, href, base), cover)
        }
    }

    /**
     * 条目封面。三种形态都要覆盖（都是真实社区源里的写法）：
     *  1. 海报地址挂在条目链接自己身上：stui 模板的
     *     `<a class=stui-vodlist__thumb data-original=/upload/x.jpg>`，里面没有 <img>；
     *  2. 图在条目子树里的 `<img>`，且常是懒加载（src 是占位 gif，真图在 data-src）；
     *  3. 「缩略图」和「标题」是同一个 <li> 里两个平级 `<a>`（myui / AppleCMS）。
     *
     * 为 3 往上找容器时，必须确认这个容器**只包住当前这一条结果**：列表项的公共父节点
     * 是整个列表，在那里面取第一张图会把邻居甚至第一行的海报发给每一条 ——
     * 错图比没图更糟，用户会以为点进去能播另一部番。
     * 取不到就返回 null，界面走占位图：封面是锦上添花，绝不能因为取图失败丢掉整条结果。
     */
    private fun coverOf(el: org.jsoup.nodes.Element, base: String, siblings: SiblingIndex): String? {
        coverUrl(el, base)?.let { return it }
        coverIn(el, base)?.let { return it }
        if (siblings.total == 0) return null
        var node = el.parent()
        var hops = 0
        while (node != null && hops < MAX_COVER_HOPS) {
            // 这一层已经罩住了别的条目 → 再往上只会更共享，不必也不能继续找。
            // 问的是预计算的祖先计数，不是 node.getAllElements()：后者要整棵子树扫一遍，
            // 每个条目扫一次就是 O(条目数 × 文档规模)，4000 条目的列表页实测秒级起步，
            // 而那正是「这个源搜索特别慢」的来源（计数本身 O(1)）。
            if ((siblings.ancestorCounts[node] ?: 0) > 1) break
            coverIn(node, base)?.let { return it }
            node = node.parent()
            hops++
        }
        return null
    }

    /**
     * 一次查询的条目身份索引。
     *
     * ancestorCounts[node] = 有多少个条目落在 node 的子树里（含 node 自身）。
     * 构建是 O(条目数 × 祖先链长度)，与文档大小无关；判定时 O(1)。
     * 用身份语义（IdentityHashMap）而不是 Element.equals：jsoup 的 equals 是
     * 结构比较，会把两个不同的节点判成相等，那样「这条的容器里还有别条」就数不准了。
     */
    class SiblingIndex internal constructor(
        val total: Int,
        val ancestorCounts: Map<org.jsoup.nodes.Element, Int>,
    )

    /**
     * 一组结果条目的「同一份集合」身份视图。
     *
     * 每个条目都要判断「我的祖先里有没有别的条目」，用的是对象身份而不是 equals
     * （jsoup 的 Element.equals 是结构比较，会把两个不同节点判成相等）。
     * 这个集合**每个查询建一次**，不是每个条目建一次：N 个条目各建一遍就是 O(N²) 的
     * 分配与插入，8000 条目的列表页（8MB 上限内完全可能出现）实测要几十秒，
     * 而这个开销和封面本身没有任何关系。
     */
    private fun siblingSet(elements: List<org.jsoup.nodes.Element>): SiblingIndex {
        val counts = java.util.IdentityHashMap<org.jsoup.nodes.Element, Int>()
        for (el in elements) {
            var node: org.jsoup.nodes.Element? = el
            while (node != null) {
                counts.merge(node, 1, Int::plus)
                node = node.parent()
            }
        }
        return SiblingIndex(elements.size, counts)
    }

    private fun coverIn(
        scope: org.jsoup.nodes.Element,
        base: String,
    ): String? = scope.select(COVER_QUERY).firstNotNullOfOrNull { coverUrl(it, base) }

    private fun coverUrl(
        el: org.jsoup.nodes.Element,
        base: String,
    ): String? {
        for (attr in COVER_ATTRS) {
            val raw = el.attr(attr)
            if (raw.isBlank()) continue
            val abs = absUrl(base, raw)
            if (abs.startsWith("http")) return abs
        }
        return null
    }

    // ---------- 线路/剧集格式实现 ----------

    private suspend fun selectNoChannel(doc: Document, cfg: SelectorSearchConfig, base: String): EpisodesResult {
        val c = cfg.selectorChannelFormatNoChannel
        val regex = SafeRegex.compile(c.matchEpisodeSortFromName)
            ?: return EpisodesResult(emptyList(), emptyList(), emptyList())
        val elements = queryAll(doc, c.selectEpisodes) ?: return EpisodesResult(emptyList(), emptyList(), emptyList())
        val links = if (c.selectEpisodeLinks.isNotBlank()) {
            queryAll(doc, c.selectEpisodeLinks)?.map { it.attr("href") }
        } else {
            null
        }
        // 一次解析共享一份预算：否则每集各拿 2M，几千集就能把一次请求按在 CPU 上几十秒。
        val budget = SafeRegex.Budget()
        val eps = elements.mapIndexedNotNull { index, e ->
            ensureAliveEvery(index)
            val name = e.text().trim()
            if (name.isBlank()) return@mapIndexedNotNull null
            val href = links?.getOrNull(index) ?: e.attr("href")
            if (href.isBlank()) return@mapIndexedNotNull null
            val parsed = parseEpisodeSort(name, regex, budget)
            EpisodeHit(
                channel = null,
                name = name,
                sort = parsed.text,
                sortNum = parsed.num,
                playUrl = absUrl(base, href),
            )
        }
        return EpisodesResult(emptyList(), eps, emptyList())
    }

    private suspend fun selectIndexGrouped(doc: Document, cfg: SelectorSearchConfig, base: String): EpisodesResult {
        val c = cfg.selectorChannelFormatFlattened
        val channelRegex = if (c.matchChannelName.isBlank()) {
            null
        } else {
            SafeRegex.compile(c.matchChannelName)
        }
        val epRegex = SafeRegex.compile(c.matchEpisodeSortFromName)
            ?: return EpisodesResult(emptyList(), emptyList(), emptyList())
        // 线路名与剧集名共享同一份预算：都属于「一次剧集页解析」。
        val budget = SafeRegex.Budget()
        val channelNames = queryAll(doc, c.selectChannelNames)
            ?.mapIndexed { index, e ->
                val text = e.text().trim()
                when {
                    // 空线路名不能丢：丢了之后 lists.zip(channelNames) 会让后面所有线路左移，
                    // 剧集挂到别人的线路名下（连播跟着串线）。占位名保住下标。
                    text.isBlank() -> "线路 ${index + 1}"
                    channelRegex == null || text.length > MAX_REGEX_TEXT_LEN -> text
                    else -> SafeRegex.groupOrText(channelRegex, text, "ch", text, budget = budget) ?: text
                }
            }
            ?: emptyList()
        val lists = queryAll(doc, c.selectEpisodeLists)
            ?: return EpisodesResult(channelNames, emptyList(), emptyList())
        if (channelNames.size != lists.size) {
            appLog.warn(
                "index-grouped 线路名与播放列表数量不一致（names={} lists={}），按下标对齐，缺失的一侧用占位名补齐",
                channelNames.size,
                lists.size,
            )
        }
        // 用 lists 驱动而不是 zip：列表多于名字时 zip 会静默丢掉多出来的整条线路
        val groups = lists.mapIndexed { index, list ->
            val channelName = channelNames.getOrNull(index) ?: "线路 ${index + 1}"
            val links = if (c.selectEpisodeLinksFromList.isNotBlank()) {
                queryAll(list, c.selectEpisodeLinksFromList)?.map { it.attr("href") }
            } else {
                null
            }
            val eps = (queryAll(list, c.selectEpisodesFromList) ?: emptyList()).mapIndexedNotNull { index, e ->
                ensureAliveEvery(index)
                val name = e.text().trim()
                if (name.isBlank()) return@mapIndexedNotNull null
                val href = links?.getOrNull(index) ?: e.attr("href")
                if (href.isBlank()) return@mapIndexedNotNull null
                val parsed = parseEpisodeSort(name, epRegex, budget)
                EpisodeHit(
                    channel = channelName,
                    name = name,
                    sort = parsed.text,
                    sortNum = parsed.num,
                    playUrl = absUrl(base, href),
                )
            }
            ChannelGroupHit(channelName, eps)
        }
        return EpisodesResult(groups.map { it.name }, groups.flatMap { it.episodes }, groups)
    }

    // ---------- 视频地址提取 ----------

    private suspend fun findVideoUrl(
        candidates: List<String>,
        videoRegex: Pattern,
        budget: SafeRegex.Budget,
    ): String? {
        for ((index, candidate) in candidates.withIndex()) {
            ensureAliveEvery(index)
            if (budget.exhausted) return null
            if (SafeRegex.contains(videoRegex, candidate, budget = budget)) return candidate
        }
        return null
    }

    /**
     * 通用兜底：源配置里的 matchVideoUrl 是一份 **CDN 域名白名单**，而源站换 CDN 比
     * 改配置勤得多（实测社区订阅里就有「页面明写着 index.m3u8、正则只认三个旧域名」
     * 的源，表现是一律 NO_VIDEO_URL_FOUND）。所以在配置彻底没命中时，
     * 按扩展名再认一次 —— 只认 m3u8/mp4/mkv/flv 这几个明确是视频后缀的，
     * 并且永远排在配置正则之后，不抢配置已经选好的线路。
     *
     * 这里只用本项目自己的正则，不再花第三方配置的预算，所以配置正则被
     * 恶意页面耗尽预算后，通用后缀兜底仍然有效。
     */
    private fun genericVideoUrl(candidates: List<String>): String? =
        candidates.firstOrNull { GENERIC_M3U8.containsMatchIn(it) }
            ?: candidates.firstOrNull { GENERIC_VIDEO.containsMatchIn(it) }

    // ---------- 工具 ----------

    /** Source-local cancellable waiting; only admitted requests commit a monotonic start. */
    private suspend fun throttle(cfg: SelectorSearchConfig) {
        val interval = effectiveInterval(cfg.requestInterval)
        sourceThrottle.await(cfg.searchUrl, interval)
    }

    /**
     * `requestInterval` 来自第三方配置，必须封顶。
     *
     * 原样接受的话，一份 `{"requestInterval": 86400000}` 的配置会把「下一次允许请求的时间」
     * 推到一天之后，并且每次调用都再往后推一天：这个源从此永远停在「搜索中」，
     * 而每个用户的每次搜索都会留下一个挂在 delay 上的协程。上限取 15 秒 ——
     * 比这更慢的源不该被放进搜索链路；真要限流是源站自己的事。
     */
    internal fun effectiveInterval(raw: Long): Long = raw.coerceIn(0, MAX_REQUEST_INTERVAL_MS)

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
    private data class FetchedPage(val body: String, val finalUrl: String)

    private suspend fun fetchPage(
        cfg: SelectorSearchConfig?,
        url: String,
        headers: Map<String, String>,
        source: String,
    ): FetchedPage {
        var target = guard(url)
        // 实际发出去的头。跨主机跳转时要从中剥掉凭据，见下面重定向处。
        var sent = headers
        cfg?.let { throttle(it) }
        var hops = 0
        while (true) {
            val result = try {
                fetcher.fetchBounded(target, sent, MAX_PAGE_BYTES)
            } catch (e: UpstreamException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw UpstreamException("$source 请求失败: ${e.message ?: e::class.simpleName}")
            }
            if (result.status !in 301..399) {
                if (result.status !in 200..299) throw UpstreamException("$source 返回 ${result.status}")
                return FetchedPage(decodePage(result), target)
            }
            val location = result.header("location")
                ?: throw UpstreamException("$source 返回 ${result.status} 但没有 Location")
            if (hops >= MAX_PAGE_REDIRECTS) throw UpstreamException("$source 重定向次数过多")
            hops++
            val next = guard(absUrl(target, location))
            // 换了主机的这一跳不再带源站 Cookie：那是**上一个主机**给我们的，
            // 302 把它送给第三个域名既不是用户的授权，也让源站凭据跟着页面里
            // 随便一个跳转目标走（代理链路早就这么做，见 ApiRoutes 的重定向循环）。
            // 只剥 Cookie：Referer 常是防盗链的必要信息且不含凭据，UA 同理。
            if (UrlGuard.hostOf(next) != UrlGuard.hostOf(target) ||
                UrlGuard.isInsecureDowngrade(target, next)
            ) {
                sent = sent - "Cookie"
            }
            target = next
        }
    }

    /**
     * 出站点校验：会真的解析 DNS。
     * 之前用的是不解析的 requireNotLiteralInternal，一个「公网域名 -> 内网 IP」
     * 的解析记录就能绕过，而数据源与它页面里的链接都是社区内容，不可信。
     * 解析不出来时 UrlGuard 放行（交给真实请求去失败），所以测试里的 .test 保留域名不受影响。
     * 阻塞 DNS 在专用有界工作队列执行；取消不等待不可中断的原生解析，Blocked 类型保持不变。
     */
    private suspend fun guard(url: String): String = guardUrl(url, allowPrivateTargets)

    /**
     * 按 BOM / Content-Type / `<meta charset>` 解码源站页面。
     *
     * 一律按 UTF-8 解是错的：国内仍有不少 GBK/GB2312 源站，标题与链接会整片乱码，
     * 表现为「搜索能返回条目但名字是乱码、详情链路一个都对不上」。三层判据与浏览器一致：
     * BOM 最高，其次响应头，最后页面里的 meta（只看前 4KB，避免大页面白扫）。
     */
    private fun decodePage(result: HttpResult): String {
        val bytes = result.body
        val bom = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                Charsets.UTF_8 to 3
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                Charsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                Charsets.UTF_16BE to 2
            else -> null to 0
        }
        val charset = bom.first
            ?: charsetNamed(result.contentType?.let { CHARSET_PARAM.find(it)?.groupValues?.getOrNull(1) })
            ?: charsetFromHtmlMeta(bytes)
            ?: Charsets.UTF_8
        return String(bytes, bom.second, bytes.size - bom.second, charset)
    }

    private fun charsetFromHtmlMeta(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        return charsetNamed(META_CHARSET.find(head)?.groupValues?.getOrNull(1))
    }

    private fun charsetNamed(name: String?): Charset? =
        name?.takeIf { it.isNotBlank() }?.let { runCatching { Charset.forName(it) }.getOrNull() }

    private fun applyLink(template: String?, raw: String, base: String): String {
        val link = if (template.isNullOrBlank()) raw else template.replace("{value}", raw)
        return absUrl(base, link)
    }

    private data class ParsedEpisodeSort(val text: String, val num: Int?)

    /**
     * 每集只解析一次。旧实现 sortText + sortNum 各调一次正则，等于把一次解析
     * 的预算翻倍；现在文本与数字来自同一次匹配，并共用调用方的 budget。
     */
    private fun parseEpisodeSort(name: String, regex: Pattern, budget: SafeRegex.Budget): ParsedEpisodeSort {
        // 历史长度上限保留（正常剧集名远短于此），真正的回溯保护在 SafeRegex：
        // `(a+)+$` 即使只有 100 个字符也能跑 2^100 步，单靠长度上限救不了。
        if (name.length > MAX_REGEX_TEXT_LEN) {
            return ParsedEpisodeSort(name, name.toIntOrNull() ?: if (isPossiblyMovie(name)) 1 else null)
        }
        val group = SafeRegex.group(regex, name, "ep", budget = budget)
        val text = group?.trim()?.takeIf { it.isNotBlank() } ?: name
        val num = text.toIntOrNull() ?: if (isPossiblyMovie(name)) 1 else null
        return ParsedEpisodeSort(text, num)
    }

    private suspend fun ensureAliveEvery(index: Int) {
        if (index % 128 == 0) currentCoroutineContext().ensureActive()
    }

    companion object {
        /** 抓取页面体积上限：同时约束「用户自定义正则」的输入规模（JVM 正则没有超时） */
        const val MAX_PAGE_BYTES: Long = 8L * 1024 * 1024

        private val CHARSET_PARAM = Regex("""charset\s*=\s*["']?([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)
        private val META_CHARSET = Regex(
            """<meta[^>]+charset\s*=\s*["']?\s*([A-Za-z0-9._-]+)""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * 第三方正则（matchEpisodeSortFromName / matchChannelName）能看到的文本上限。
         * 剧集名/线路名远短于此；超过就按「没匹配到」处理，不让回溯型正则卡住采集。
         */
        private const val MAX_REGEX_TEXT_LEN = 512

        /** 第三方配置里 requestInterval 的可用上限，见 effectiveInterval */
        const val MAX_REQUEST_INTERVAL_MS: Long = 15_000

        /**
         * 日志用的地址形态：只留 scheme://host/path，query 与 fragment 一律丢掉。
         *
         * 这类地址的查询串就是凭据本身（`?sign=…&time=…`、`?token=…`），
         * 记进容器日志等于把一份可重放的播放地址抄给所有能读日志的人。
         * 解析不出来时也不能回落到原文：宁可少记，截断到没有查询串为止。
         */
        internal fun urlForLog(raw: String): String = runCatching {
            val u = URI(raw)
            // userinfo 也可能是凭据（订阅地址常写成 https://user:token@host/…），一并去掉
            val authority = u.rawAuthority?.substringAfterLast('@')?.let { "://$it" } ?: ""
            "${u.scheme}$authority${u.rawPath.orEmpty()}"
        }.getOrElse { raw.substringBefore('?').take(120) }
        private const val MAX_PAGE_REDIRECTS = 5

        /** 找条目封面时最多向上找几层容器。再往上就已经是整个列表了 */
        private const val MAX_COVER_HOPS = 4

        /** 兜底识别视频地址用：后缀后面必须真的是结尾（或 query），不能是 `x.m3u8.png` 这种 */
        private val GENERIC_M3U8 = Regex("""\.m3u8(?:[?#]|$)""")
        private val GENERIC_VIDEO = Regex("""\.(?:m3u8|mp4|mkv|flv)(?:[?#]|$)""")

        /** 懒加载站点的真实图常在这些属性里，src 反而是占位图 */
        private val COVER_ATTRS = listOf("data-src", "data-original", "data-lazy-src", "src")

        /** stui 模板把海报挂在 <a> 自己的 data-original 上（里面根本没有 <img>），所以两种都要查 */
        private val COVER_QUERY = (listOf("img") + COVER_ATTRS.map { "[$it]" }).joinToString(", ")

        /** 社区 JSON 接口里封面字段名不统一，按常见写法依次探测 */
        private val COVER_KEYS = listOf("pic", "cover", "img", "image", "vod_pic", "listthumb_20")

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
                // authority 为 null 时（`http:///x`、`mailto:x` 这类）不能拼出 base：
                // "${u.scheme}://${u.authority}" 会把 null 直接拼进字符串，
                // 得到 http://null 这种看着像地址的东西，产出一堆点不开的链接而不是报错
                val authority = u.rawAuthority ?: return requestUrl
                "${u.scheme}://$authority"
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

        /** CSS / XPath 自适应查询；不允许 jsoup 的无预算内嵌正则。 */
        fun queryAll(root: Element, selector: String): List<Element>? {
            if (selector.isBlank()) return null
            val raw = selector.trim()
            val xpath = SelectorPolicy.isXPath(raw)
            if (runCatching { SelectorPolicy.requireSafeQuery(selector) }.isFailure) return null
            val xpathExpr = if (raw.startsWith("xpath:")) raw.removePrefix("xpath:").trim() else raw
            return try {
                if (xpath) root.selectXpath(xpathExpr).toList() else root.select(raw).toList()
            } catch (e: Exception) {
                try {
                    if (xpath) {
                        // The CSS fallback must pass its own policy, including escaped names.
                        SelectorPolicy.requireSafeQuery(raw, css = true)
                        root.select(raw).toList()
                    } else root.selectXpath(raw).toList()
                } catch (e2: Exception) {
                    null
                }
            }
        }

    }
}

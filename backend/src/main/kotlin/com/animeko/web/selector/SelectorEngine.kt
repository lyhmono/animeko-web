package com.animeko.web.selector

import com.animeko.web.appLog
import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.JsonGuard
import com.animeko.web.http.UrlGuard
import com.animeko.web.http.UpstreamException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val lastRequestAt = mutableMapOf<String, Long>()

    // ---- Phase 1 + 2: 搜索并列出条目 ----

    suspend fun search(cfg: SelectorSearchConfig, keyword: String): List<SubjectHit> {
        require(cfg.searchUrl.isNotBlank()) { "searchUrl 未配置" }
        val kw = cfg.resolvedAutoMatch().processKeyword(keyword)
        val url = cfg.searchUrl.replace("{keyword}", URLEncoder.encode(kw, "UTF-8"))
        val body = fetchPage(cfg, url, mapOf("User-Agent" to cfg.userAgentOrDefault()), "数据源搜索页")
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
        val body = fetchPage(cfg, target, mapOf("User-Agent" to cfg.userAgentOrDefault()), "数据源详情页")
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

        val html = fetchPage(cfg, target, headers, "播放页")
        // 源自己的 matchVideoUrl 始终优先；兜底要等所有页面都试完才采用，
        // 否则一个通用扩展名会把配置里更精确的线路抢掉
        var fallback: String? = null
        // 所有候选共用同一份字符预算：预算是「一次解析」的额度，不是每条 URL 的额度。
        // 后者会让几千条畸形候选各自烧满 2M 次回溯，把一次解析按在 CPU 上几十秒。
        val budget = SafeRegex.Budget()
        // 候选只解析一次：findVideoUrl 与 genericVideoUrl 原先各自 Jsoup.parse + 扫 URL，
        // 8MB 页面等于白做两遍解析与两遍正则。
        val pageCandidates = videoCandidates(html, target)
        findVideoUrl(pageCandidates, videoRegex, budget)?.let {
            return ResolvedTarget(it, referer, ua, cookies, cookieHosts)
        }
        genericVideoUrl(pageCandidates)?.let { fallback = it }
        if (mv.enableNestedUrl && nestedRegex != null) {
            // 嵌套候选：DOM 中的 iframe/a/video/source 绝对地址 + 原文中的绝对 URL
            val doc = Jsoup.parse(html, target)
            val nestedCandidates = LinkedHashSet<String>()
            doc.select("iframe[src]").forEach { nestedCandidates.add(it.absUrl("src")) }
            doc.select("a[href]").forEach { nestedCandidates.add(it.absUrl("href")) }
            candidateUrls(html).forEach { nestedCandidates.add(it) }
            // 与 videoCandidates 同一条规则：长度先判，正则后判，顺序不能颠倒
            // （`&&` 是短路的，反过来就等于没挡）。
            val nested = nestedCandidates.filter {
                it.length in 11..MAX_CANDIDATE_URL_LEN && it.startsWith("http") &&
                    SafeRegex.contains(nestedRegex, it, budget = budget)
            }.take(MAX_NESTED_PAGES)
            val deadline = System.currentTimeMillis() + NESTED_BUDGET_MS
            for (n in nested) {
                // 每个嵌套页都要一次完整抓取（单次超时 20s），不设总预算的话
                // 一次解析能拖到一分多钟，前端早已断开而服务端还在跑。
                if (System.currentTimeMillis() > deadline) break
                val sub = runCatching {
                    // 嵌套地址来自页面内容，同样要过校验：否则播放页里塞一个
                    // iframe="http://127.0.0.1:..." 就能借服务端去敲内网。非法候选直接跳过，
                    // 不让它把整次解析打断。
                    // Cookie 只在「与播放页同主机」时才带：iframe 可以指向任意第三方，
                    // 那等于把源站 Cookie 送给页面作者指定的地方。
                    val nestedHost = UrlGuard.hostOf(n)
                    val nestedHeaders = if (nestedHost != null && nestedHost == UrlGuard.hostOf(target)) {
                        headers
                    } else {
                        headers - "Cookie"
                    }
                    fetchPage(
                        cfg,
                        UrlGuard.requireNotLiteralInternal(n, allowPrivateTargets),
                        nestedHeaders,
                        "嵌套播放页",
                    )
                }.getOrNull() ?: continue
                val subCandidates = videoCandidates(sub, n)
                findVideoUrl(subCandidates, videoRegex, budget)?.let {
                    return ResolvedTarget(it, referer, ua, cookies, cookieHosts)
                }
                if (fallback == null) fallback = genericVideoUrl(subCandidates)
            }
        }
        fallback?.let {
            // 说清楚走了兜底：这等于「配置里的 CDN 白名单过期了」，
            // 不记下来的话下次源站换域名时只能看到一堆莫名的失败。
            // 但只记到 path：这类地址的 query 里通常就是 sign/token/时间戳，
            // 原样进容器日志等于把可用凭据抄进一份谁都能读的文件
            // （同一个理由已经用在 /api/proxy 的日志上）。
            appLog.info("matchVideoUrl 没有命中，改用通用扩展名兜底：{}", urlForLog(it))
            return ResolvedTarget(it, referer, ua, cookies, cookieHosts)
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

    private fun selectJsonPathIndexed(body: String, cfg: SelectorSearchConfig, base: String): List<SubjectHit> {
        val c = cfg.selectorSubjectFormatJsonPathIndexed
        val root: JsonElement = try {
            // 源站返回什么不由我们决定：先用深度闸门挡住「一把左括号」那种
            // 会打穿栈的载荷（StackOverflowError 不是 Exception，下面的 catch 接不住）
            JsonGuard.requireShallow(body)
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            return emptyList()
        }
        // 保留位置（取不到字符串就置空串）再配对，理由同 pairIndexed
        // 名字、地址、封面都是**按下标配对**的，所以必须用对齐版求值：
        // 通用求值器按 Jayway 语义让缺键元素贡献 0 项、双键元素贡献 2 项，
        // 结果不是报错而是整列表错位 —— 显示 A 的名字、点进去播 B 的地址
        val urls = JsonPathEval.evaluateAligned(c.selectLinks, root)
            ?.map { JsonPathEval.singleString(it).orEmpty() }
            ?: return emptyList()
        val names = JsonPathEval.evaluateAligned(c.selectNames, root)
            ?.map { JsonPathEval.singleString(it).orEmpty() }
            ?: return emptyList()
        val covers = COVER_KEYS
            .asSequence()
            .mapNotNull { key ->
                // `$[*]['key']` 而不是 `$[*].['key']`：后者在 `.` 后面接 `[`，
                // 求值器会当成空键而整条返回 null（封面于是永远取不到）
                JsonPathEval.evaluateAligned("$[*]['$key']", root)
                    ?.map { JsonPathEval.singleString(it).orEmpty() }
            }
            .firstOrNull { it.any { v -> v.isNotBlank() } }
            ?: emptyList()
        return pairIndexed(names, urls, covers, c.linkTemplate, base)
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

    private fun findVideoUrl(
        candidates: List<String>,
        videoRegex: Pattern,
        budget: SafeRegex.Budget,
    ): String? = candidates.firstOrNull {
        !budget.exhausted && SafeRegex.contains(videoRegex, it, budget = budget)
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

    private fun videoCandidates(html: String, pageUrl: String): List<String> {
        val doc = Jsoup.parse(html, pageUrl)
        val candidates = LinkedHashSet<String>()
        doc.select("iframe[src]").forEach { candidates.add(it.absUrl("src")) }
        doc.select("a[href]").forEach { candidates.add(it.absUrl("href")) }
        doc.select("video[src], source[src]").forEach { candidates.add(it.absUrl("src")) }
        candidateUrls(html).forEach { candidates.add(it) }
        // 长度上限要挡在**所有**候选上，而不是只挡在 candidateUrls 那条扫描路径上：
        // DOM 里抽出来的 href 是原样进集合的（`<a href="https://x/aaaa…30 万个 a">`），
        // 少这一格，配置里一个 (a+)+$ 就会在那种串上跑 —— JVM 的 java.util.regex 没有超时，
        // 而这条链路的正则与页面内容都是别人写的。实测代价：一次解析 6 秒起步，
        // 且完全取决于机器忙不忙（这类只会抖出来说明它本来就挡不住）。
        return candidates.filter { it.length in 11..MAX_CANDIDATE_URL_LEN && it.startsWith("http") }
            .take(MAX_VIDEO_CANDIDATES)
    }

    private fun candidateUrls(html: String): List<String> {
        // 排除集要覆盖 JS 模板串与对象字面量：反引号字符串是现在最主流的写法，
        // `}` 紧随对象字面量。漏一个都会让带垃圾尾缀的 URL 命中 containsMatchIn
        // 并被整串返回（表现为「解析成功但播放 404」）。
        val regex = Regex("""https?://[^\s"'<>`{}\\)\]]+""")
        // 必须再扫一遍「解过转义」的副本：AppleCMS 的 player_aaaa 把地址写成
        //   {"url":"https:\/\/v6.xx.com\/a\/index.m3u8"}
        // 而上面那个正则把 \ 当作 URL 的终止符 —— 于是「页面里明明就写着 m3u8，
        // 却报 NO_VIDEO_URL_FOUND」。实测这类源占社区订阅的一大半，
        // 是「视频播放不了」的头号成因，不是源站真的藏了地址。
        return listOf(html, unescapeJsStrings(html))
            .flatMap { regex.findAll(it) }
            .map { it.value.trim().trimEnd('`', '}', '{', '(', ')', '.', ',', ';', '!') }
            // 上限是给**后面那些来自配置的字符串正则**用的：JVM 的 java.util.regex 没有超时，
        // 一个 (a+)+$ 这样的模式配上一个 8MB 不换行的 token 就永远跑不完，
        // 而这条链路的输入是第三方页面内容。超过这个长度的地址也不可能是可播地址。
        .filter { it.length in 11..MAX_CANDIDATE_URL_LEN }
            .distinct()
            .toList()
    }

    /**
     * 只解 URL 抽取用得到的三种转义（\uXXXX、\xNN、\/）。
     * 不去动原始 HTML —— 那是 Jsoup 要解析的东西，这里只服务于一份候选副本。
     */
    private fun unescapeJsStrings(text: String): String =
        text.replace(Regex("""\\u([0-9a-fA-F]{4})""")) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }.replace(Regex("""\\x([0-9a-fA-F]{2})""")) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }.replace("\\/", "/")

    // ---------- 工具 ----------

    /**
     * 源级限速：三个采集 phase（搜索/详情/播放页）都过这里。
     * 锁内只推进「下一次允许请求的时间戳」，等待用 delay 放到锁外。
     * 之前是持锁 Thread.sleep：既阻塞协程所在的 IO 线程，
     * 又让所有数据源的搜索互相排队（A 源的 3 秒间隔会拖住 B 源）。
     * 更早的版本只在搜索 phase 限速，连点播放仍然能瞬间打满详情页与播放页。
     */
    private suspend fun throttle(cfg: SelectorSearchConfig) {
        val interval = effectiveInterval(cfg.requestInterval)
        if (interval <= 0) return
        val wait = synchronized(lastRequestAt) {
            val now = System.currentTimeMillis()
            val last = lastRequestAt[cfg.searchUrl] ?: 0L
            val planned = maxOf(now, last + interval)
            lastRequestAt[cfg.searchUrl] = planned
            planned - now
        }
        if (wait > 0) delay(wait)
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
    private suspend fun fetchPage(
        cfg: SelectorSearchConfig?,
        url: String,
        headers: Map<String, String>,
        source: String,
    ): String {
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
                return decodePage(result)
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
     * 阻塞的解析放在 IO 线程上做。
     */
    private suspend fun guard(url: String): String =
        withContext(Dispatchers.IO) {
            // 原样抛 Blocked：StatusPages 会对外回 403 + 一句通用文案。
            // 在这里换成 SelectorException 会把拒绝原因（含「主机名 -> 内网 IP」）
            // 回显给任何能连到这台机器的人，那是网络拓扑泄露。
            UrlGuard.requireAllowed(url, allowPrivateTargets)
        }

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

        /** 单个候选 URL 的长度上限，见 candidateUrls */
        private const val MAX_CANDIDATE_URL_LEN = 4096

        /** 单页参与正则匹配的候选数上限：8MB 页面能塞几十万条链接，没必要全扫 */
        private const val MAX_VIDEO_CANDIDATES = 5_000

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

        private fun looksLikeXPath(selector: String): Boolean {
            val s = selector.trim()
            return s.startsWith("//") || s.startsWith("xpath:") ||
                (s.startsWith("/") && !s.startsWith("/*")) || s.startsWith("(")
        }

        /** CSS / XPath 自适应查询；JSoup 1.18+ 原生支持 selectXpath */
        fun queryAll(root: Element, selector: String): List<Element>? {
            if (selector.isBlank()) return null
            val raw = selector.trim()
            val xpath = looksLikeXPath(raw)
            // `xpath:` 是配置里常见的显式前缀，但 Jsoup 的 selectXpath 只接受裸 XPath。
            // 以前把它原样传进去，带前缀的写法必然失败，README 宣称的 XPath 支持形同虚设。
            val xpathExpr = if (raw.startsWith("xpath:")) raw.removePrefix("xpath:").trim() else raw
            return try {
                if (xpath) root.selectXpath(xpathExpr).toList() else root.select(raw).toList()
            } catch (e: Exception) {
                try {
                    if (xpath) root.select(raw).toList() else root.selectXpath(raw).toList()
                } catch (e2: Exception) {
                    null
                }
            }
        }

    }
}

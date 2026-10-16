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
        val headers = buildMap {
            put("User-Agent", ua)
            put("Referer", referer)
            if (cookies.isNotBlank()) put("Cookie", cookies)
        }
        val videoRegex = runCatching { Regex(mv.matchVideoUrl) }.getOrNull()
            ?: throw SelectorException("matchVideoUrl 正则无效")
        val nestedRegex = runCatching { Regex(mv.matchNestedUrl) }.getOrNull()

        val html = fetchPage(cfg, target, headers, "播放页")
        // 源自己的 matchVideoUrl 始终优先；兜底要等所有页面都试完才采用，
        // 否则一个通用扩展名会把配置里更精确的线路抢掉
        var fallback: String? = null
        findVideoUrl(html, target, videoRegex)?.let {
            return ResolvedTarget(it, referer, ua, cookies)
        }
        genericVideoUrl(html, target)?.let { fallback = it }
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
                        cfg,
                        UrlGuard.requireNotLiteralInternal(n, allowPrivateTargets),
                        headers,
                        "嵌套播放页",
                    )
                }.getOrNull() ?: continue
                findVideoUrl(sub, n, videoRegex)?.let {
                    return ResolvedTarget(it, referer, ua, cookies)
                }
                if (fallback == null) fallback = genericVideoUrl(sub, n)
            }
        }
        fallback?.let {
            // 说清楚走了兜底：这等于「配置里的 CDN 白名单过期了」，
            // 不记下来的话下次源站换域名时只能看到一堆莫名的失败
            appLog.info("matchVideoUrl 没有命中，改用通用扩展名兜底：{}", it)
            return ResolvedTarget(it, referer, ua, cookies)
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
            else SubjectHit(name, applyLink(c.linkTemplate, href, base), coverOf(e, base, elements))
        }
    }

    private fun selectIndexed(doc: Document, cfg: SelectorSearchConfig, base: String): List<SubjectHit> {
        val c = cfg.selectorSubjectFormatIndexed
        val names = queryAll(doc, c.selectNames)?.map { it.text().trim() } ?: return emptyList()
        val linkEls = queryAll(doc, c.selectLinks) ?: return emptyList()
        return pairIndexed(
            names,
            linkEls.map { it.attr("href") },
            linkEls.map { coverOf(it, base, linkEls) },
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
        val covers = COVER_KEYS
            .asSequence()
            .mapNotNull { key -> JsonPathEval.evaluate("$[*].$key", root)?.map { JsonPathEval.singleString(it).orEmpty() } }
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
    private fun coverOf(
        el: org.jsoup.nodes.Element,
        base: String,
        siblings: List<org.jsoup.nodes.Element> = emptyList(),
    ): String? {
        coverUrl(el, base)?.let { return it }
        coverIn(el, base)?.let { return it }
        if (siblings.isEmpty()) return null
        val all = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<org.jsoup.nodes.Element, Boolean>(),
        )
        all += siblings
        var node = el.parent()
        var hops = 0
        while (node != null && hops < MAX_COVER_HOPS) {
            // 这一层已经罩住了别的条目 → 再往上只会更共享，不必也不能继续找
            if (node.getAllElements().count { it in all } > 1) break
            coverIn(node, base)?.let { return it }
            node = node.parent()
            hops++
        }
        return null
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

    private fun findVideoUrl(html: String, pageUrl: String, videoRegex: Regex): String? =
        videoCandidates(html, pageUrl).firstOrNull { videoRegex.containsMatchIn(it) }

    /**
     * 通用兜底：源配置里的 matchVideoUrl 是一份 **CDN 域名白名单**，而源站换 CDN 比
     * 改配置勤得多（实测社区订阅里就有「页面明写着 index.m3u8、正则只认三个旧域名」
     * 的源，表现是一律 NO_VIDEO_URL_FOUND）。所以在配置彻底没命中时，
     * 按扩展名再认一次 —— 只认 m3u8/mp4/mkv/flv 这几个明确是视频后缀的，
     * 并且永远排在配置正则之后，不抢配置已经选好的线路。
     */
    private fun genericVideoUrl(html: String, pageUrl: String): String? {
        val candidates = videoCandidates(html, pageUrl)
        // m3u8 优先：同页常同时挂着一条 mp4 预览小样和真正的分片清单
        return candidates.firstOrNull { GENERIC_M3U8.containsMatchIn(it) }
            ?: candidates.firstOrNull { GENERIC_VIDEO.containsMatchIn(it) }
    }

    private fun videoCandidates(html: String, pageUrl: String): List<String> {
        val doc = Jsoup.parse(html, pageUrl)
        val candidates = LinkedHashSet<String>()
        doc.select("iframe[src]").forEach { candidates.add(it.absUrl("src")) }
        doc.select("a[href]").forEach { candidates.add(it.absUrl("href")) }
        doc.select("video[src], source[src]").forEach { candidates.add(it.absUrl("src")) }
        candidateUrls(html).forEach { candidates.add(it) }
        return candidates.filter { it.startsWith("http") }
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
            .filter { it.length > 10 }
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
        val interval = cfg.requestInterval.coerceAtLeast(0)
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
        cfg?.let { throttle(it) }
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

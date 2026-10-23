package com.animeko.web.bangumi

import com.animeko.web.appLog
import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.JsonGuard
import com.animeko.web.http.UpstreamException
import com.animeko.web.http.UrlGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** 条目在 Bangumi 上不存在/已删除（上游 404），映射为对客户端回 404。 */
class SubjectNotFoundException(source: String) : Exception("Bangumi 条目不存在: $source")

@Serializable
data class BgmSearchResponse(
    val total: Int = 0,
    val data: List<BgmSubject> = emptyList(),
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
data class BgmRating(
    val rank: Int? = null,
    val total: Int? = null,
    val score: Double? = null,
)

@Serializable
data class BgmSubject(
    val id: Int,
    val name: String = "",
    val name_cn: String = "",
    val summary: String = "",
    val date: String? = null,
    val platform: String? = null,
    val type: Int? = null,
    val rating: BgmRating? = null,
    /**
     * v0 搜索/详情结果在顶层也给了评分与排名（详情接口的 responseGroup=large 同样如此）。
     * 之前没落到字段上被 ignoreUnknownKeys 丢掉，前端 `rating?.score ?? score` 的兜底于是恒为 undefined。
     */
    val score: Double? = null,
    val rank: Int? = null,
    val images: BgmImages? = null,
    /**
     * v0 API 中该字段类型不固定：搜索结果为 int（总集数），详情可能为数组。
     * 剧集统一通过 /v0/episodes 获取，这里仅做容错解析。
     */
    val eps: JsonElement? = null,
)

@Serializable
data class BgmImages(
    val large: String? = null,
    val common: String? = null,
    val medium: String? = null,
    val small: String? = null,
    val grid: String? = null,
)

@Serializable
data class BgmEpisodesResponse(
    val total: Int = 0,
    val data: List<BgmEpisode> = emptyList(),
)

@Serializable
data class BgmCalendarResponse(
    val weekday: BgmWeekday = BgmWeekday(),
    val items: List<BgmCalendarItem> = emptyList(),
)

@Serializable
data class BgmWeekday(
    val id: Int = 0,
    val en: String = "",
    val cn: String = "",
)

/**
 * 每日放送的一项。与 [BgmSubject] 同构度很高但是两个结构：
 * calendar 的 item 没有 summary/date，而多一个 `collection`（当前用户收藏状态），
 * 且 eps 是纯整数（已放送集数思路）。另立模型而不是复用，是为了让两边的
 * 「字段一定存在」的假设各自成立，不会因为一边 API 变动影响另一边解析。
 */
@Serializable
data class BgmCalendarItem(
    val id: Int,
    val name: String = "",
    val name_cn: String = "",
    val rating: BgmRating? = null,
    val score: Double? = null,
    val images: BgmImages? = null,
    /** 已放送/总集数（calendar 接口里是数字） */
    val eps: Int? = null,
    /** 当前用户是否已收藏（无鉴权的开放请求里通常为 null） */
    val collection: JsonElement? = null,
)

@Serializable
data class BgmEpisode(
    val id: Int,
    val name: String = "",
    val name_cn: String = "",
    val sort: Double? = null,
    val duration: String? = null,
    val airdate: String? = null,
    /** 0=本篇 1=SP 2=OP 3=ED 4=预告 5=MAD 6=其他；前端据此区分非正片剧集 */
    val type: Int = 0,
)

/**
 * Bangumi 开放 API 客户端（v0，https://github.com/bangumi/api）。
 *
 * 官方 api.bgm.tv 在部分地区不可达，因此支持多个 base URL 按顺序故障转移
 * （ANIMEKO_BANGUMI_BASE_URLS，逗号分隔；首个可用的会被记住并优先使用）。
 */
class BangumiClient(
    private val fetcher: Fetcher,
    baseUrls: List<String> = DEFAULT_BASE_URLS,
    private val userAgent: String = "animeko-web/0.1 (self-hosted; +https://github.com/open-ani/animeko)",
    private val channelStore: BgmChannelStore? = null,
    /**
     * 是否允许通道指向内网地址。与 ANIMEKO_ALLOW_PRIVATE_TARGETS 同义：
     * 通道地址会被服务端拿去发请求，等于一个「让服务器访问任意 URL」的入口，
     * 所以必须和 proxy / 订阅走同一条内网拦截规则。
     */
    private val allowPrivateTargets: Boolean = false,
    /**
     * 有多个候选时，首选地址最多独占多久；超时后同时竞速其余地址。
     * 社区镜像实测 13~25 秒才返回，若一直「先试首选」，稳态下每次搜索都要等它。
     */
    private val preferredHedgeMs: Long = PREFERRED_HEDGE_MS,
) {

    /** 来自 ANIMEKO_BANGUMI_BASE_URLS（或默认值）的候选地址，AUTO 通道用的就是这一份 */
    private val envBases: List<String> = baseUrls.filter { it.isNotBlank() }.ifEmpty { DEFAULT_BASE_URLS }

    @Volatile
    private var channel: BgmChannelConfig = channelStore?.load() ?: BgmChannelConfig()

    init {
        // 配置文件能被手工改，绕过 setChannel 的校验。启动时再验一次：
        // 非法就退回 AUTO 并落盘 —— 宁可让站长重新选一次，
        // 也不能让服务端带着一个内网地址跑起来。
        val loaded = channel
        if (loaded.channel == BgmChannel.CUSTOM) {
            // 数量超限时**不要**先跑 firstOrNull：那会对几万条地址逐条做 DNS，
            // 正好是这条检查要避免的启动卡死
            val tooMany = loaded.customUrls.size > MAX_CUSTOM_URLS
            val bad = if (tooMany) null else loaded.customUrls.firstOrNull { !isAllowedChannelTarget(it) }
            if (tooMany || bad != null) {
                appLog.warn(
                    "Bangumi 通道配置里的地址不被允许（数量 {}，首个非法 {}），已退回自动",
                    loaded.customUrls.size,
                    bad ?: "无",
                )
                channel = BgmChannelConfig()
                runCatching { channelStore?.save(channel) }
            }
        }
    }

    /**
     * 通道地址是否允许（公网 http/https；allowPrivateTargets 时放开内网）。
     *
     * 只对**设置页写入**与**启动时读回的配置**做这层校验，不校验
     * ANIMEKO_BANGUMI_BASE_URLS 里的地址：环境变量要能改容器才改得动，
     * 那是站长本人的权限；而设置页在默认部署（鉴权关闭）下任何能连上的人都能写，
     * 两者信任级别不同，不能一刀切。
     */
    private fun isAllowedChannelTarget(url: String): Boolean =
        runCatching { UrlGuard.requireAllowed("$url/v0/calendar", allowPrivateTargets) }.isSuccess

    /** 当前通道实际生效的地址列表（公开给设置页展示）。每次调用重读，改完立即生效、不用重启。 */
    /** 给设置页展示用：不校验。这里若抛错，站长打开设置页就看到 403，
     *  反而**没法**把已经变成内网的那个地址改回来——把自己锁在门外了。 */
    fun effectiveBases(): List<String> = resolveBases(channel, enforce = false)

    /** 真正要发请求的地址列表：逐次校验（见 resolveBases 的注释） */
    private fun requestBases(): List<String> = resolveBases(channel, enforce = true)

    /**
     * 把一个通道配置解析成候选地址。探测未保存的目标时走这里，与保存后走同一套规则。
     *
     * CUSTOM 空列表**不兜底到 envBases**：保存路径已强制非空（见 setChannel），
     * 能走到这里的只剩「探测一个还没填地址的自定义通道」。
     * 那时若悄悄去探自动通道的地址，结果会挂在 CUSTOM 名下显示 ——
     * 站长会以为「我的中转填错了但其实是能用的」，比直接说「没有地址」更误导。
     */
    private fun resolveBases(config: BgmChannelConfig, enforce: Boolean): List<String> = when (config.channel) {
        BgmChannel.AUTO -> envBases
        BgmChannel.OFFICIAL -> listOf(OFFICIAL_BASE)
        BgmChannel.MIRROR -> listOf(MIRROR_BASE)
        BgmChannel.CUSTOM -> config.customUrls
            .map { it.trim().removeSuffix("/") }
            .filter { it.isNotBlank() }
            // **每次解析都验**，而不是只在保存与启动时验。这里同时堵住两个洞：
            //  1) 探测路径（设置页那个「测一下」按钮）拿的是**还没保存、也就从没被
            //     setChannel 校验过**的目标。实测默认部署（不配凭据 = 无鉴权）下
            //     POST /api/bangumi/channel/probe {"customUrls":["http://127.0.0.1:8299/…"]}
            //     真的让服务器去打了一针内网端口，蜜罐收到 GET /bangumi-mirror/v0/subjects/290980，
            //     而回包的 detail/latencyMs 直接把「这个端口开着、它这么回答」告诉对方。
            //     allowPrivateTargets 是 false 也照样发出去了——那道闸只在保存时路过。
            //  2) 保存时验过一次，与真正发请求之间隔着的不只是几行代码，还可能是几天和
            //     一次 DNS TTL：社区「镜像」地址常由别人提供，域名今天指公网、明天指内网
            //     是做得到的。闸要设在出门口，不是设在登记处。
            // 校验口径与代理/抓取一致：requireAllowed 会真做 DNS 解析并拒绝内网/保留段。
            // 只约束 CUSTOM：AUTO 走环境变量（要能改容器才改得动，是站长本人的权限），
            // OFFICIAL/MIRROR 是写死的公网常量。
            .let { list -> if (!enforce) list else {
                // 探测接口的 target 来自请求体，没有经过 setChannel：上限必须在这里也挡一次，
                // 否则一份带几万条地址的请求会让服务端逐条做阻塞 DNS（鉴权默认关闭时人人可发）
                require(list.size <= MAX_CUSTOM_URLS) {
                    "自定义通道最多支持 $MAX_CUSTOM_URLS 个地址（当前 ${list.size} 个）"
                }
                list.map { url ->
                    runCatching { UrlGuard.requireAllowed("$url/v0/calendar", allowPrivateTargets) }
                        .getOrElse {
                            // 不回显 Blocked 原文（里面是「主机名 -> 内网 IP」的映射），
                            // 细节只进日志；调用方拿到的是 403 + 一句通用原因
                            appLog.warn("Bangumi 自定义通道地址不被允许: {} ({})", url, it.message)
                            throw UrlGuard.Blocked("自定义通道地址不被允许")
                        }
                    url
                }
            } }
    }

    /** 当前通道是否支持每日放送：只有纯镜像通道明确不支持 */
    val calendarSupported: Boolean
        get() = channel.channel != BgmChannel.MIRROR

    fun channelState(): BgmChannelConfig = channel

    /**
     * 切换通道。CUSTOM 必须带至少一个合法的 http(s) 地址 —— 存进去一个空列表或垃圾串，
     * 之后每一次元数据请求都会失败，而现象是「搜索没结果」，没人会想到是设置页填坏了。
     * 校验放在写入前，非法直接拒绝（IllegalArgumentException → 400）。
     */
    fun setChannel(next: BgmChannelConfig): BgmChannelConfig {
        if (next.channel == BgmChannel.CUSTOM) {
            val urls = next.customUrls.map { it.trim().removeSuffix("/") }.filter { it.isNotBlank() }
            require(urls.isNotEmpty()) { "自定义通道至少需要一个 Bangumi API 地址" }
            // 每个地址都会做一次真实 DNS 校验；不封顶的话一个请求就能让调用线程
            // 对着几万条地址逐条解析（体积极限 1MB，塞得下）。
            require(urls.size <= MAX_CUSTOM_URLS) {
                "自定义通道最多支持 $MAX_CUSTOM_URLS 个地址（当前 ${urls.size} 个）"
            }
            // 形参必须显式命名成 url：下面 onFailure 的 lambda 参数是 Throwable
            // 且默认名也叫 it，会**遮蔽**外层 forEach 的 it —— 之前写成 $it 时，
            // 拼进错误消息的是 Blocked 异常原文（含「主机名 -> 内网 IP」映射），
            // 等于把想藏起来的拓扑信息直接回给了客户端。
            urls.forEach { url ->
                require(url.startsWith("http://") || url.startsWith("https://")) {
                    "地址必须以 http/https 开头：$url"
                }
                val uri = runCatching { java.net.URI(url) }.getOrNull()
                    ?: throw IllegalArgumentException("地址不合法：$url")
                require(uri.host != null) { "地址无法解析出主机名：$url" }
                // 内网/保留地址一律拒绝：这个地址会被服务端拿去发请求，
                // 不拦就等于给了任何能打开设置页的人一个 SSRF 跳板
                // （默认部署 ANIMEKO_AUTH_ENABLED 是关的）。
                runCatching { UrlGuard.requireAllowed("$url/v0/calendar", allowPrivateTargets) }
                    .onFailure {
                        // 只回一句通用原因，不透出 Blocked 原文（Main.kt 里 Blocked 的
                        // StatusPages 处理也是只记日志、只回通用文案）
                        throw IllegalArgumentException("地址不被允许：$url（仅支持公网 http/https 地址）")
                    }
            }
            val saved = next.copy(customUrls = urls)
            // 校验通过才清「记住的赢家」：被拒的配置不该有任何副作用 ——
            // 否则填错一次地址就把当前通道的缓存赢家也清了，下一次请求白跑一轮竞速
            preferredBase = null
            channel = saved
            channelStore?.save(saved)
            return saved
        }
        // 非 CUSTOM 时把 customUrls 清空存盘，避免留下「看起来还配着」的困惑
        val saved = next.copy(customUrls = emptyList())
        // 换通道 = 换一批候选，旧的赢家不再适用（withFallback 里还有 preferred in list
        // 的兜底，这里显式清是为了不让一个已不属于任何通道的地址留在字段里）
        preferredBase = null
        channel = saved
        channelStore?.save(saved)
        return saved
    }

    /**
     * 探测候选地址的连通性（拿 /v0/calendar 试，它响应体小）。
     * 返回每个地址的成功与否、延迟与一句原因，供设置页展示 ——
     * 让站长在保存之前就看见「这个镜像不支持每日放送」这类事实，
     * 而不是保存完去猜。
     *
     * @param target 要探测的通道；为 null 时探测**当前已保存**的通道。
     *   设置页的正确流程是「先探测目标、再保存」，所以探测必须能回答
     *   「如果我切到镜像会怎样」—— 而不是只能看当前状态。
     */
    /**
     * 探测候选地址的连通性，分**两件事**回答，因为它们对同一个地址经常是不同答案：
     *  - [BgmProbeResult.ok]：核心能力是否可用（搜索/条目/剧集，即 /v0/subjects 探针）
     *  - [BgmProbeResult.calendarOk]：每日放送端点是否可用（/v0/calendar 探针）
     *
     * 用 calendar 单点当唯一判据是错的：实测社区镜像的 subjects 是 200、
     * calendar 是 404 —— 拿放送表当探针会把它报成「不可用」，
     * 而它其实完全能支撑搜索与追番。两件事必须分开显示，
     * 否则站长会为了「探测全红」去换一个更差的通道。
     *
     * @param target 要探测的通道；为 null 时探测**当前已保存**的通道。
     */
    suspend fun probe(target: BgmChannelConfig? = null): List<BgmProbeResult> {
        val list = if (target == null) requestBases() else resolveBases(target, enforce = true)
        if (list.isEmpty()) return emptyList()
        // 并发探测：串行的话官方挂起 + 镜像又慢时，设置页那个按钮要转两分钟
        return coroutineScope {
            list.map { async { probeOne(base = it) } }.map { it.await() }
        }
    }

    private suspend fun probeOne(base: String): BgmProbeResult {
        val started = System.currentTimeMillis()
        // 核心能力探针：取一个必定存在的条目（凉宫春日的忧郁）。只关心状态码，
        // 不解析响应体，避免把「能连上」和「字段变了」混为一谈。
        val core = runCatching {
            // 必须带上限：探测地址可以由任何能打开设置页的人填写（默认部署无鉴权），
            // 指向一个高带宽的大文件时，ofByteArray 会按 45 秒超时把整份响应读进堆
            fetcher.fetchBounded("$base/v0/subjects/$PROBE_SUBJECT_ID", headers(), MAX_METADATA_BYTES).status
        }
        val coreOk = core.getOrNull()?.let { it in 200..299 } == true
        // 放送表探针：只在核心可用时才值得问（否则已经知道整体不通了）。
        // 注意保留「没问到」（异常/超时）与「问了但 404」的区别：
        // 前者是这个地址慢或抖，后者才是它真的没有这个端点。混成一个 null
        // 会把超时报成「不提供每日放送」，方向就指错了。
        val calendarProbe = if (!coreOk) {
            null
        } else {
            runCatching {
                fetcher.fetchBounded("$base/v0/calendar", headers(), MAX_METADATA_BYTES).status
            }
        }
        val calendarStatus = calendarProbe?.getOrNull()
        val calendarOk = calendarStatus != null && calendarStatus in 200..299
        val elapsed = System.currentTimeMillis() - started

        val detail = when {
            !coreOk && core.exceptionOrNull() != null ->
                "连接失败：${core.exceptionOrNull()?.message?.take(60) ?: "网络错误"}"
            !coreOk -> "搜索端点返回 HTTP ${core.getOrNull()}"
            calendarOk -> "可用（含每日放送）"
            calendarStatus == 404 -> "搜索可用，但不提供每日放送"
            calendarStatus != null -> "搜索可用；每日放送返回 HTTP $calendarStatus"
            // 核心通、放送这次没问到：别断言「不支持」，如实说是探测失败
            else -> "搜索可用；每日放送探测失败（超时或网络错误），可重试"
        }
        return BgmProbeResult(
            base = base,
            ok = coreOk,
            latencyMs = elapsed,
            detail = detail,
            calendarOk = calendarOk,
        )
    }

    @Volatile
    private var preferredBase: String? = null

    private val json = Json { ignoreUnknownKeys = true }

    /** 搜索番剧条目（type=2 动画） */
    suspend fun search(keywords: String, type: Int = TYPE_ANIME, limit: Int = 24): List<BgmSubject> {
        require(keywords.isNotBlank()) { "keywords must not be blank" }
        val body = """{"keyword":${json.encodeToString(keywords)},"filter":{"type":[$type]},"limit":$limit}"""
        return withFallback { base ->
            decode(
                text("$base/v0/search/subjects") {
                    fetcher.post("$base/v0/search/subjects", body, headers())
                },
                BgmSearchResponse.serializer(),
                "Bangumi 搜索",
            ).data
        }
    }

    /**
     * 排行榜（按 rank 升序 = 评分榜）。发现页用：用户不知道看什么时，
     * 「高分动画」比「让他想关键词」自然得多。
     *
     * 复用 [BgmSearchResponse] 作为响应模型：这个端点的返回结构与搜索完全一致
     * （total/data/limit/offset），没必要为同一形状再建一个类。
     */
    suspend fun ranking(limit: Int = 24, offset: Int = 0): BgmSearchResponse = withFallback { base ->
        val path = "/v0/subjects?type=$TYPE_ANIME&sort=rank&limit=$limit&offset=$offset"
        decode(
            text("$base$path") { fetcher.fetchBounded("$base$path", headers(), MAX_METADATA_BYTES) },
            BgmSearchResponse.serializer(),
            "Bangumi 排行榜",
        )
    }

    /**
     * 每日放送表（周一到周日）。
     *
     * 注意这是**官方端点**：社区镜像（bgmapi.anibt.net）只镜像了搜索/条目/剧集，
     * 镜像上这个路径是 404。所以放送表在「官方不可达、只有镜像能用」的部署上
     * 必然不可用 —— 调用方（API 层与前端）必须把它当可选降级处理，
     * 不许因为它把首页其他功能拖垮。
     *
     * 这条链路单独走一份候选与一份「赢家」记录，不能复用搜索那条的 preferredBase：
     * 搜索在境外 VPS 上多半由镜像胜出并被记住，若放送表也先试这个赢家，
     * 就会只撞镜像的 404 —— 明明官方通、放送表却永远不可用。
     * 所以这里把「不支持放送端点」的已知地址（镜像）排到最后，且不共享偏好。
     */
    suspend fun calendar(): List<BgmCalendarResponse> {
        if (!calendarSupported) {
            throw UpstreamException("当前 Bangumi 通道（社区镜像）不提供每日放送端点，可在设置页切换通道")
        }
        // 官方优先：镜像确定没有这个端点，排后面只作为「万一它是中转」的兜底
        val candidates = requestBases().sortedBy { if (it == MIRROR_BASE) 1 else 0 }
        // 首页附属内容不能让人等满 45 秒的 Bangumi 超时（官方在某些网络里是
        // 连接层静默丢包，实测 8 秒仍无响应）。给自己一个更短的预算：
        // 超时就放弃整块放送表 —— 首页的主体是搜索，不该被一个锦上添花的区块拖住。
        return withTimeoutOrNull(CALENDAR_BUDGET_MS) {
            // 注意不能写成 raceAll(candidates, rememberWinner = false) { … }：
            // 具名实参之后不能再跟尾随 lambda，Kotlin 会把它当成传给下一个
            // 形参的位置实参，于是报 "No value passed for parameter 'block'"。
            // 显式传 lambda 就没有歧义了。
            raceAll(candidates, block = { base ->
                // 不能复用普通的 text()：它把 404 解释成「条目不存在」
                // （那是 /v0/subjects/{id} 的语义）。放送表上的 404 只有一个含义 ——
                // 这个地址没有该端点。分开处理，日志与前端提示才不会指错方向。
                val result = fetcher.fetchBounded("$base/v0/calendar", headers(), MAX_METADATA_BYTES)
                when {
                    result.status in 200..299 ->
                        decode(result.text, BgmCalendarResponseList, "每日放送")
                    result.status == 404 ->
                        throw UpstreamException("$base 不提供每日放送端点（HTTP 404）")
                    else ->
                        throw UpstreamException("每日放送请求返回 HTTP ${result.status}（$base）")
                }
            }, rememberWinner = false)
        } ?: throw UpstreamException("每日放送获取超时（${CALENDAR_BUDGET_MS / 1000} 秒），可稍后刷新")
    }

    /** 条目详情（含剧集列表） */
    suspend fun subject(id: Int): BgmSubject {
        val subject = withFallback { base ->
            decode(
                text("$base/v0/subjects/$id") {
                    fetcher.fetchBounded("$base/v0/subjects/$id?responseGroup=large", headers(), MAX_METADATA_BYTES)
                },
                BgmSubject.serializer(),
                "Bangumi 条目",
            )
        }
        val episodes = withFallback { base -> episodesOf(base, id) }
        return subject.copy(
            eps = JsonArray(episodes.map { json.encodeToJsonElement(BgmEpisode.serializer(), it) }),
        )
    }

    /**
     * 分页拉取剧集：v0 的 limit 上限 200，单次请求会把长篇番剧（海贼王等）
     * 第 200 集之后静默截断掉。
     */
    private suspend fun episodesOf(base: String, id: Int): List<BgmEpisode> {
        val collected = ArrayList<BgmEpisode>()
        var offset = 0
        while (collected.size < MAX_EPISODES) {
            val pageUrl = "$base/v0/episodes?subject_id=$id&limit=$EPISODES_PAGE&offset=$offset"
            val resp = decode(
                text(pageUrl) { fetcher.fetchBounded(pageUrl, headers(), MAX_METADATA_BYTES) },
                BgmEpisodesResponse.serializer(),
                "Bangumi 剧集列表",
            )
            if (resp.data.isEmpty()) break
            collected.addAll(resp.data)
            // total 缺省为 0：镜像省略该字段时不能把首页 200 条当成「已经到结尾」。
            // 有 total 按 total，没有就看这一页是否满页。
            if (resp.total > 0) {
                if (collected.size >= resp.total) break
            } else if (resp.data.size < EPISODES_PAGE) {
                break
            }
            offset += resp.data.size
        }
        return collected
    }

    private fun headers() = mapOf("User-Agent" to userAgent)

    /** 上游非 2xx / 连接失败都归一成 502；404 单独转成 [SubjectNotFoundException] 便于回 404。 */
    private suspend fun text(source: String, request: suspend () -> HttpResult): String =
        try {
            val result = request()
            when {
                result.status == 404 -> throw SubjectNotFoundException(source)
                result.status in 200..299 -> result.text
                // 3xx 单独说清楚：这一路刻意不自动跳转（见 search() 上的注释），
                // 只报「返回 302」会让人以为是网络问题，而实际要做的是把 base 改成跳转后的地址。
                result.status in 300..399 -> throw UpstreamException(
                    "上游 $source 返回 ${result.status}（Bangumi 请求不自动跟随重定向："+
                        "请把 ANIMEKO_BANGUMI_BASE_URLS 配成重定向后的最终地址）",
                )
                else -> throw UpstreamException("上游 $source 返回 ${result.status}")
            }
        } catch (e: SubjectNotFoundException) {
            throw e
        } catch (e: UpstreamException) {
            throw e
        } catch (e: CancellationException) {
            // 取消信号必须原样上抛：包成业务异常会让结构化取消失效，
            // 客户端早就断开而服务端还把这条当成一次上游失败
            throw e
        } catch (e: Exception) {
            // 连接超时/拒绝之类如果不包住，会一路掉进兜底 handler 变成 500
            throw UpstreamException("请求 $source 失败: ${e.message ?: e::class.simpleName}")
        }

    private fun <T> decode(text: String, serializer: KSerializer<T>, source: String): T =
        try {
            // 镜像是第三方：递归解析被深度炸弹打穿栈时抛的是 StackOverflowError，
            // 下面的 `catch (e: Exception)` 接不住，会一路变成 500
            JsonGuard.requireShallow(text)
            json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            throw UpstreamException("$source 返回的不是预期 JSON")
        }

    /**
     * 按「记住的可用地址 → 全部地址并发竞速」的顺序取一个能用的结果。
     *
     * 以前是**串行**按顺序试。在「官方域名不可达 + 镜像要十几到二十几秒」这种真实部署上
     * （实测就是用户那台 VPS），一次搜索要先白等官方满 20 秒超时，再轮到镜像，
     * 42 秒之后两个都失败。而且只有**成功**才会被记住，所以只要镜像从没在超时内
     * 回来过，每一次搜索都要重新把那个死域名等满一遍 —— 慢不是偶发，是常态。
     *
     * 现在：有记录就先只试它（稳态下不给已知死掉的域名开连接）；它失败就把记录清掉，
     * 剩下所有候选**同时发**，第一个成功的赢、其余立刻取消。总耗时从「各超时之和」
     * 变成「最慢的那个」，并且第一次成功之后就走快路径。
     *
     * 地址在本轮开始时取一次快照：站长可能在一次请求进行到一半时改通道，
     * 拿同一份列表发完这一轮，比中途换地址撕裂一次请求更好解释。
     */
    private suspend fun <T> withFallback(block: suspend (base: String) -> T): T {
        val list = requestBases()
        val preferred = preferredBase
        if (preferred != null && preferred in list) {
            try {
                // 有多个候选时给首选一个很短的独占窗口：它慢但能成功时，
                // 不能把每次请求都拖到 13~45 秒。超时不清除首选，交给竞速选新的赢家。
                val hedged = if (list.size > 1 && preferredHedgeMs > 0) {
                    withTimeoutOrNull(preferredHedgeMs) { block(preferred) }
                } else {
                    block(preferred)
                }
                if (hedged != null) return hedged
            } catch (e: SubjectNotFoundException) {
                // 404 是确定性的「条目不存在」，不是地址坏了：不要清 preferred，
                // 也不要让所有 base 再打一遍（同一台镜像会被请求两次）。
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 记下来的这个已经不好用了：清掉，别让它一直挡在真正能用的前面
                preferredBase = null
                if (list.size == 1) throw e
            }
        }
        return raceAll(list, block)
    }

    private suspend fun <T> raceAll(
        list: List<String>,
        block: suspend (base: String) -> T,
        /** 是否把赢家记为全局首选。放送表这条必须传 false（见 calendar 的注释） */
        rememberWinner: Boolean = true,
    ): T = coroutineScope {
        // 空候选必须在这里拦：Channel(0) 是 rendezvous 通道，repeat(0) 不循环，
        // 最后会抛掉那句 IllegalStateException —— 那是 500，而真实原因是
        // 「这个通道一个地址都没有」（配置问题，应该是 502 级的可解释失败）。
        if (list.isEmpty()) throw UpstreamException("当前 Bangumi 通道没有任何可用地址")
        val results = Channel<Pair<String, Result<T>>>(list.size)
        val jobs = list.map { base ->
            launch {
                val outcome = try {
                    Result.success(block(base))
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                results.send(base to outcome)
            }
        }
        try {
            var lastError: Throwable? = null
            var firstNotFound: SubjectNotFoundException? = null
            repeat(list.size) {
                val (base, outcome) = results.receive()
                if (outcome.isSuccess) {
                    if (rememberWinner) preferredBase = base
                    return@coroutineScope outcome.getOrThrow()
                }
                val error = outcome.exceptionOrNull()
                lastError = error
                // 404 是确定性的结果：不能因为另一个 base 更晚回了一个 500
                // 就把「条目不存在」变成 502。最终错误优先保留第一个 404。
                if (error is SubjectNotFoundException && firstNotFound == null) firstNotFound = error
            }
            throw firstNotFound ?: lastError ?: IllegalStateException("没有可用的 Bangumi API 地址")
        } finally {
            // 赢家出现后必须掐掉其余的：否则 coroutineScope 要等最慢那条（可能是 30 秒超时）
            jobs.forEach { it.cancel() }
        }
    }

    companion object {
        val DEFAULT_BASE_URLS = listOf("https://api.bgm.tv", "https://bgmapi.anibt.net")
        const val OFFICIAL_BASE = "https://api.bgm.tv"
        const val MIRROR_BASE = "https://bgmapi.anibt.net"

        /** 首选地址的独占窗口；有多个候选时超时即开始竞速。 */
        private const val PREFERRED_HEDGE_MS = 2_500L
        const val TYPE_ANIME = 2

        /** /v0/calendar 的顶层就是数组 */
        internal val BgmCalendarResponseList: KSerializer<List<BgmCalendarResponse>> =
            ListSerializer(BgmCalendarResponse.serializer())

        /** v0 episodes 接口单次上限 200 条 */
        private const val EPISODES_PAGE = 200

        /** 自定义通道的候选地址上限：超过这个数没有真实用途，只会变成 DNS 放大器 */
        private const val MAX_CUSTOM_URLS = 8

        /**
         * 元数据响应体上限。真实响应（搜索/条目/剧集/放送表）都是几十 KB 量级；
         * 8MB 只为挡住「第三方地址回了一个大文件」这种把堆吃光的情况，
         * 尤其是探测接口的目标可以由任何能打开设置页的人填写。
         */
        private const val MAX_METADATA_BYTES = 8L * 1024 * 1024

        /** 拉取总上限，避免 total 字段异常时翻页翻不停 */
        private const val MAX_EPISODES = 2000

        /**
         * 每日放送的总预算。远小于 ANIMEKO_BANGUMI_TIMEOUT_MS（45 秒）：
         * 那个上限是为「镜像慢但会回」的搜索设的，而放送表是首页附属区块，
         * 官方在部分网络里是连接层静默丢包（实测 8 秒无响应），等满 45 秒
         * 等于首页每次都要挂一条连接近一分钟。
         */
        private const val CALENDAR_BUDGET_MS = 12_000L

        /** 探测核心能力用的条目（凉宫春日的忧郁），社区镜像与官方都一定收录 */
        private const val PROBE_SUBJECT_ID = 290980
    }
}

/** 设置页探测一个候选地址的结果。 */
@Serializable
data class BgmProbeResult(
    val base: String,
    /** 核心能力（搜索/条目/剧集）是否可用 —— 这决定这个地址能不能用 */
    val ok: Boolean,
    val latencyMs: Long,
    val detail: String,
    /** 每日放送端点是否可用。与 [ok] 分离：镜像满足 ok 但不满足它 */
    val calendarOk: Boolean = false,
)

@Serializable
data class BgmChannelResponse(
    val channel: BgmChannel,
    val customUrls: List<String> = emptyList(),
    /** AUTO/CUSTOM 的实际生效地址列表，展示用 */
    val effectiveBases: List<String> = emptyList(),
    /** 官方默认候选（AUTO 的初始来源），展示用 */
    val defaultBases: List<String> = BangumiClient.DEFAULT_BASE_URLS,
    val calendarSupported: Boolean = true,
)

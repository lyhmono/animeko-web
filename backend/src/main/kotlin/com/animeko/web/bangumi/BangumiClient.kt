package com.animeko.web.bangumi

import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.JsonGuard
import com.animeko.web.http.UpstreamException
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
) {
    /** 来自 ANIMEKO_BANGUMI_BASE_URLS（或默认值）的候选地址，AUTO 通道用的就是这一份 */
    private val envBases: List<String> = baseUrls.filter { it.isNotBlank() }.ifEmpty { DEFAULT_BASE_URLS }

    @Volatile
    private var channel: BgmChannelConfig = channelStore?.load() ?: BgmChannelConfig()

    /** 当前通道实际生效的地址列表（公开给设置页展示）。每次调用重读，改完立即生效、不用重启。 */
    fun effectiveBases(): List<String> = resolveBases(channel)

    /**
     * 把一个通道配置解析成候选地址。探测未保存的目标时走这里，与保存后走同一套规则。
     *
     * CUSTOM 空列表**不兜底到 envBases**：保存路径已强制非空（见 setChannel），
     * 能走到这里的只剩「探测一个还没填地址的自定义通道」。
     * 那时若悄悄去探自动通道的地址，结果会挂在 CUSTOM 名下显示 ——
     * 站长会以为「我的中转填错了但其实是能用的」，比直接说「没有地址」更误导。
     */
    private fun resolveBases(config: BgmChannelConfig): List<String> = when (config.channel) {
        BgmChannel.AUTO -> envBases
        BgmChannel.OFFICIAL -> listOf(OFFICIAL_BASE)
        BgmChannel.MIRROR -> listOf(MIRROR_BASE)
        BgmChannel.CUSTOM -> config.customUrls.map { it.trim().removeSuffix("/") }.filter { it.isNotBlank() }
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
        // 换通道 = 换一批候选，旧的「记住的赢家」不再适用：清掉，下一轮重新竞速选出。
        // （withFallback 里有 preferred in list 的兜底，这里显式清是为了语义清楚，
        //  不让一个已经不属于任何通道的地址留在字段里）
        preferredBase = null
        if (next.channel == BgmChannel.CUSTOM) {
            val urls = next.customUrls.map { it.trim().removeSuffix("/") }.filter { it.isNotBlank() }
            require(urls.isNotEmpty()) { "自定义通道至少需要一个 Bangumi API 地址" }
            urls.forEach {
                require(it.startsWith("http://") || it.startsWith("https://")) {
                    "地址必须以 http/https 开头：$it"
                }
                runCatching { java.net.URI(it) }.getOrNull()?.let { u ->
                    require(u.host != null) { "地址无法解析出主机名：$it" }
                } ?: throw IllegalArgumentException("地址不合法：$it")
            }
            val saved = next.copy(customUrls = urls)
            channel = saved
            channelStore?.save(saved)
            return saved
        }
        // 非 CUSTOM 时把 customUrls 清空存盘，避免留下「看起来还配着」的困惑
        val saved = next.copy(customUrls = emptyList())
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
        val list = if (target == null) effectiveBases() else resolveBases(target)
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
            fetcher.fetch("$base/v0/subjects/$PROBE_SUBJECT_ID", headers(), followRedirects = false).status
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
                fetcher.fetch("$base/v0/calendar", headers(), followRedirects = false).status
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
        val candidates = effectiveBases().sortedBy { if (it == MIRROR_BASE) 1 else 0 }
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
                val result = fetcher.fetch("$base/v0/calendar", headers(), followRedirects = false)
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
                    fetcher.fetch("$base/v0/subjects/$id?responseGroup=large", headers(), followRedirects = false)
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
                text(pageUrl) { fetcher.fetch(pageUrl, headers(), followRedirects = false) },
                BgmEpisodesResponse.serializer(),
                "Bangumi 剧集列表",
            )
            if (resp.data.isEmpty()) break
            collected.addAll(resp.data)
            if (collected.size >= resp.total) break
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
        val list = effectiveBases()
        val preferred = preferredBase
        if (preferred != null && preferred in list) {
            try {
                return block(preferred)
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
            repeat(list.size) {
                val (base, outcome) = results.receive()
                if (outcome.isSuccess) {
                    if (rememberWinner) preferredBase = base
                    return@coroutineScope outcome.getOrThrow()
                }
                // 全失败时抛最后一个错：404 要能一路传到 API 层变成 404 而不是 502
                lastError = outcome.exceptionOrNull()
            }
            throw lastError ?: IllegalStateException("没有可用的 Bangumi API 地址")
        } finally {
            // 赢家出现后必须掐掉其余的：否则 coroutineScope 要等最慢那条（可能是 30 秒超时）
            jobs.forEach { it.cancel() }
        }
    }

    companion object {
        val DEFAULT_BASE_URLS = listOf("https://api.bgm.tv", "https://bgmapi.anibt.net")
        const val OFFICIAL_BASE = "https://api.bgm.tv"
        const val MIRROR_BASE = "https://bgmapi.anibt.net"
        const val TYPE_ANIME = 2

        /** /v0/calendar 的顶层就是数组 */
        internal val BgmCalendarResponseList: KSerializer<List<BgmCalendarResponse>> =
            ListSerializer(BgmCalendarResponse.serializer())

        /** v0 episodes 接口单次上限 200 条 */
        private const val EPISODES_PAGE = 200

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

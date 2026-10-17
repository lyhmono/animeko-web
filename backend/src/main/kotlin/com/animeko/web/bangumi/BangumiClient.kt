package com.animeko.web.bangumi

import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.UpstreamException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
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
) {
    private val bases: List<String> = baseUrls.filter { it.isNotBlank() }.ifEmpty { DEFAULT_BASE_URLS }

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

    /** 条目详情（含剧集列表） */
    suspend fun subject(id: Int): BgmSubject {
        val subject = withFallback { base ->
            decode(
                text("$base/v0/subjects/$id") {
                    fetcher.fetch("$base/v0/subjects/$id?responseGroup=large", headers())
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
                text(pageUrl) { fetcher.fetch(pageUrl, headers()) },
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
     */
    private suspend fun <T> withFallback(block: suspend (base: String) -> T): T {
        val preferred = preferredBase
        if (preferred != null) {
            try {
                return block(preferred)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 记下来的这个已经不好用了：清掉，别让它一直挡在真正能用的前面
                preferredBase = null
                if (bases.size == 1) throw e
            }
        }
        return raceAll(block)
    }

    private suspend fun <T> raceAll(block: suspend (base: String) -> T): T = coroutineScope {
        val results = Channel<Pair<String, Result<T>>>(bases.size)
        val jobs = bases.map { base ->
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
            repeat(bases.size) {
                val (base, outcome) = results.receive()
                if (outcome.isSuccess) {
                    preferredBase = base
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
        const val TYPE_ANIME = 2

        /** v0 episodes 接口单次上限 200 条 */
        private const val EPISODES_PAGE = 200

        /** 拉取总上限，避免 total 字段异常时翻页翻不停 */
        private const val MAX_EPISODES = 2000
    }
}

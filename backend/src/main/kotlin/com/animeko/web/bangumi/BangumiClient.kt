package com.animeko.web.bangumi

import com.animeko.web.http.Fetcher
import com.animeko.web.http.HttpResult
import com.animeko.web.http.UpstreamException
import kotlinx.coroutines.CancellationException
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

    /** 按顺序尝试各 base URL，记住第一个可用的 */
    private suspend fun <T> withFallback(block: suspend (base: String) -> T): T {
        val order = buildList {
            preferredBase?.let { add(it) }
            bases.forEach { if (it != preferredBase) add(it) }
        }
        var lastError: Exception? = null
        for (base in order) {
            try {
                val result = block(base)
                preferredBase = base
                return result
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("no bangumi base url configured")
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

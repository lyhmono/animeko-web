package com.animeko.web.bangumi

import com.animeko.web.http.Fetcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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
            json.decodeFromString<BgmSearchResponse>(
                fetcher.post("$base/v0/search/subjects", body, headers()).text,
            ).data
        }
    }

    /** 条目详情（含剧集列表） */
    suspend fun subject(id: Int): BgmSubject {
        val subject = withFallback { base ->
            json.decodeFromString<BgmSubject>(
                fetcher.fetch("$base/v0/subjects/$id?responseGroup=large", headers()).text,
            )
        }
        val episodes = withFallback { base ->
            json.decodeFromString<BgmEpisodesResponse>(
                fetcher.fetch("$base/v0/episodes?subject_id=$id&limit=200", headers()).text,
            ).data
        }
        return subject.copy(eps = kotlinx.serialization.json.JsonArray(episodes.map { json.encodeToJsonElement(BgmEpisode.serializer(), it) }))
    }

    private fun headers() = mapOf("User-Agent" to userAgent)

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
    }
}

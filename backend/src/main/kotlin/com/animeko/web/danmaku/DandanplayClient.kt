package com.animeko.web.danmaku

import com.animeko.web.http.Fetcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.util.Base64

@Serializable
data class DdSearchAnimesResponse(
    val success: Boolean = false,
    val animes: List<DdAnime> = emptyList(),
    val errorCode: Int = 0,
    val errorMessage: String = "",
)

@Serializable
data class DdAnime(
    val animeId: Int = 0,
    val animeTitle: String = "",
    val type: String = "",
    val episodes: List<DdEpisode> = emptyList(),
)

@Serializable
data class DdEpisode(
    val episodeId: Long = 0,
    val episodeTitle: String = "",
)

/** 弹弹play 评论接口的原始返回结构 */
@Serializable
data class DdCommentResponse(
    val count: Int = 0,
    val comments: List<DdCommentRaw> = emptyList(),
)

/** 原始弹幕：p = "时间,类型,颜色,[用户],[来源]"，m = 文本 */
@Serializable
data class DdCommentRaw(
    val cid: Long = 0,
    val p: String = "",
    val m: String = "",
)

/** 解析后的弹幕（供前端渲染，经 API 响应序列化） */
@Serializable
data class DdComment(
    val cid: Long,
    /** 出现时间（秒） */
    val time: Double,
    val text: String,
    /** 十进制 RGB 颜色 */
    val color: Int,
    /** 模式：1 滚动 / 5 顶部 / 4 底部 */
    val mode: Int,
) {
    companion object {
        fun parse(raw: DdCommentRaw): DdComment? {
            val parts = raw.p.split(",")
            if (parts.size < 3) return null
            val time = parts[0].trim().toDoubleOrNull() ?: return null
            val mode = parts[1].trim().toIntOrNull() ?: 1
            val color = parts[2].trim().toIntOrNull() ?: 16777215
            return DdComment(
                cid = raw.cid,
                time = time,
                text = raw.m,
                color = color,
                mode = mode,
            )
        }
    }
}

/**
 * 弹弹play 开放平台客户端（https://doc.dandanplay.com/open/）。
 *
 * 签名算法：X-Signature = Base64(SHA-256(AppId + Timestamp + Path + AppSecret))
 * Path 为 API 路径（小写、不含域名与查询参数），请求头携带 X-AppId / X-Timestamp / X-Signature。
 */
class DandanplayClient(
    private val fetcher: Fetcher,
    private val appId: String,
    private val appSecret: String,
    private val baseUrl: String = "https://api.dandanplay.net",
) {
    private val json = Json { ignoreUnknownKeys = true }

    val configured: Boolean get() = appId.isNotBlank() && appSecret.isNotBlank()

    /** 计算签名，抽出为纯函数便于测试 */
    fun signature(path: String, timestamp: Long): String {
        val raw = appId + timestamp + path + appSecret
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun authHeaders(path: String): Map<String, String> {
        val ts = System.currentTimeMillis() / 1000
        return mapOf(
            "X-AppId" to appId,
            "X-Timestamp" to ts.toString(),
            "X-Signature" to signature(path, ts),
        )
    }

    /** 搜索番剧/剧集，用于把 Bangumi 剧集匹配到弹弹play 的 episodeId */
    suspend fun searchEpisodes(anime: String, episode: String? = null): List<DdAnime> {
        requireConfigured()
        val path = "/api/v2/search/episodes"
        val query = buildString {
            append("anime=").append(URLEncoder.encode(anime, "UTF-8"))
            if (!episode.isNullOrBlank()) append("&episode=").append(URLEncoder.encode(episode, "UTF-8"))
        }
        val text = fetcher.fetch("$baseUrl$path?$query", authHeaders(path)).text
        return json.decodeFromString<DdSearchAnimesResponse>(text).animes
    }

    /** 获取某集的全部弹幕 */
    suspend fun comments(episodeId: Long, withRelated: Boolean = true): List<DdComment> {
        requireConfigured()
        val path = "/api/v2/comment/$episodeId"
        val query = if (withRelated) "?withRelated=true" else ""
        val text = fetcher.fetch("$baseUrl$path$query", authHeaders(path)).text
        val resp = json.decodeFromString<DdCommentResponse>(text)
        return resp.comments.mapNotNull { DdComment.parse(it) }
    }

    private fun requireConfigured() {
        check(configured) { "DANMAKU_NOT_CONFIGURED" }
    }
}

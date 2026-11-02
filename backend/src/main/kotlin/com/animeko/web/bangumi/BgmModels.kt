package com.animeko.web.bangumi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Bangumi v0 API 的数据契约。
 *
 * 与客户端逻辑分开放：这些是上游返回什么形状的描述，改它们等于改对外契约；
 * 而 BangumiClient 是怎么把请求打到哪个地址、怎么竞速回退，是另一件事。
 * 所有字段都给默认值 —— 官方与社区镜像谁都可能少给字段，缺一个就让解码整个失败，
 * 而用户看到的只是「条目打不开」。
 */
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

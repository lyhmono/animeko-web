package com.animeko.web.selector

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * animeko (open-ani) Selector 数据源配置模型。
 *
 * 与 animeko `SelectorMediaSourceArguments` / `SelectorSearchConfig` 的 JSON Schema 保持一致
 * （factoryId = "web-selector", version = 2），因此 animeko 的数据源配置 JSON 可直接导入使用。
 *
 * @see <a href="https://github.com/open-ani/animeko">animeko</a> (AGPL-3.0)
 */

/** 顶层包装：animeko 数据源文件的标准结构 */
@Serializable
data class SelectorConfigFile(
    val factoryId: String = "web-selector",
    val version: Int = 2,
    val arguments: SelectorSourceArguments,
)

@Serializable
data class SelectorSourceArguments(
    val name: String,
    val description: String = "",
    val iconUrl: String = "",
    val searchConfig: SelectorSearchConfig = SelectorSearchConfig(),
    /**
     * animeko 侧的源优先级：社区配置里是整数（t0..t4），内置示例里是字符串（"Fallback"），
     * 两种都出现过。本服务不做源优先级仲裁，这里只保证「能解析」，因此用 JsonElement 兼容任意写法。
     */
    val tier: JsonElement? = null,

    /** 线路 -> 优先级，值同样是整数或字符串，见 [tier] */
    val channelTiers: JsonElement? = null,
)

@Serializable
data class SelectorSearchConfig(
    // ---- Phase 1: 搜索 ----
    /** 搜索地址模板，{keyword} 为占位符 */
    val searchUrl: String = "",
    /** 相对链接解析基址；为空时取请求 URL 的 origin */
    val rawBaseUrl: String = "",
    /** 请求间隔（毫秒） */
    val requestInterval: Long = 3000,
    /** 搜索缓存 TTL（毫秒） */
    val searchCacheTtl: Long = 7_200_000,
    val userAgent: String = "",

    // ---- Phase 2: 条目格式 ----
    /** a / indexed / json-path-indexed */
    val subjectFormatId: String = SubjectFormatA.ID,
    val selectorSubjectFormatA: SubjectFormatA.Config = SubjectFormatA.Config(),
    val selectorSubjectFormatIndexed: SubjectFormatIndexed.Config = SubjectFormatIndexed.Config(),
    val selectorSubjectFormatJsonPathIndexed: SubjectFormatJsonPathIndexed.Config =
        SubjectFormatJsonPathIndexed.Config(),

    // ---- Phase 3: 线路/剧集格式 ----
    /** no-channel / index-grouped */
    val channelFormatId: String = ChannelFormatNoChannel.ID,
    val selectorChannelFormatFlattened: ChannelFormatIndexGrouped.Config = ChannelFormatIndexGrouped.Config(),
    val selectorChannelFormatNoChannel: ChannelFormatNoChannel.Config = ChannelFormatNoChannel.Config(),

    val defaultResolution: String = "1080P",
    val defaultSubtitleLanguage: String = "CHS",
    val onlySupportsPlayers: List<String> = emptyList(),

    // ---- 播放期 ----
    val selectMedia: SelectMediaConfig = SelectMediaConfig(),
    val matchVideo: MatchVideoConfig = MatchVideoConfig(),
    val autoMatch: SelectorAutoMatchConfig = SelectorAutoMatchConfig(),

    // ---- 旧版平铺字段（兼容旧 JSON；解析时折叠进 autoMatch）----
    val searchUseOnlyFirstWord: Boolean? = null,
    val searchRemoveSpecial: Boolean? = null,
    val searchUseSubjectNamesCount: Int? = null,
    val filterByEpisodeSort: Boolean? = null,
) {
    /** 折叠旧版平铺字段后的 autoMatch 配置 */
    fun resolvedAutoMatch(): SelectorAutoMatchConfig = SelectorAutoMatchConfig(
        enabled = autoMatch.enabled,
        searchUseOnlyFirstWord = searchUseOnlyFirstWord ?: autoMatch.searchUseOnlyFirstWord,
        searchRemoveSpecial = searchRemoveSpecial ?: autoMatch.searchRemoveSpecial,
        searchUseSubjectNamesCount = searchUseSubjectNamesCount ?: autoMatch.searchUseSubjectNamesCount,
        preferShorterName = autoMatch.preferShorterName,
        filterByEpisodeSort = filterByEpisodeSort ?: autoMatch.filterByEpisodeSort,
    )

    fun userAgentOrDefault(): String = userAgent.ifBlank {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3"
    }
}

// ---------- Phase 2: 条目（搜索结果）格式 ----------

object SubjectFormatA {
    const val ID = "a"

    /** 一个 CSS 语句 select 出 <a>：title/text 作名称，href 作详情页链接 */
    @Serializable
    data class Config(
        val selectLists: String = "div.video-info-header > a",
        /** 详情页链接模板，{value} 为抽出的原始链接；为空直接用原链接 */
        val linkTemplate: String? = null,
    )
}

object SubjectFormatIndexed {
    const val ID = "indexed"

    /** 分别 select 出名字列表与链接列表，按下标一一对应 */
    @Serializable
    data class Config(
        val selectNames: String = ".search-box .thumb-content > .thumb-txt",
        val selectLinks: String = ".search-box .thumb-menu > a",
        val linkTemplate: String? = null,
    )
}

object SubjectFormatJsonPathIndexed {
    const val ID = "json-path-indexed"

    /** 搜索结果页是 JSON：用 JsonPath 分别取名字与链接，按下标一一对应 */
    @Serializable
    data class Config(
        val selectLinks: String = "$[*]['url', 'link']",
        val selectNames: String = "$[*]['title','name']",
        val linkTemplate: String? = null,
    )
}

// ---------- Phase 3: 线路/剧集格式 ----------

object ChannelFormatIndexGrouped {
    const val ID = "index-grouped"

    /** tab 行 + 多个播放列表：按页面播放列表分组，顺序与页面一致 */
    @Serializable
    data class Config(
        val selectChannelNames: String = ".anthology-tab > .swiper-wrapper a",
        /** 从线路文本中提取线路名（命名组 ch）；为空则用完整文本 */
        val matchChannelName: String = "^(?<ch>.+?)(\\d+)?$",
        val selectEpisodeLists: String = ".anthology-list-box",
        val selectEpisodesFromList: String = "a",
        /** 当 selectEpisodesFromList 选出的元素不是 <a> 时，用此项单独选链接 */
        val selectEpisodeLinksFromList: String = "",
        /** 从剧集名中提取集号（命名组 ep）；为空则整个名称即集号文本 */
        val matchEpisodeSortFromName: String = "第\\s*(?<ep>.+)\\s*[话集]",
    )
}

object ChannelFormatNoChannel {
    const val ID = "no-channel"

    /** 无线路（或只有一个线路）：一个列表 */
    @Serializable
    data class Config(
        val selectEpisodes: String = "#glist-1 > div.module-blocklist.scroll-box.scroll-box-y > div > a",
        val selectEpisodeLinks: String = "",
        val matchEpisodeSortFromName: String = "第\\s*(?<ep>.+)\\s*[话集]",
    )
}

// ---------- 播放期 ----------

@Serializable
data class SelectMediaConfig(
    val distinguishSubjectName: Boolean = true,
    val distinguishChannelName: Boolean = true,
)

@Serializable
data class MatchVideoConfig(
    /** 是否继续抓取嵌套页面（如 iframe）寻找视频地址 */
    val enableNestedUrl: Boolean = true,
    /** 判断是否为嵌套页面 URL 的正则 */
    val matchNestedUrl: String = """^.+(m3u8|vip|xigua\.php).+\?""",
    /** 判断是否为视频地址的正则（对每个候选 URL 单独匹配） */
    val matchVideoUrl: String =
        """(^http(s)?:\/\/(?!.*http(s)?:\/\/).+((\.mp4)|(\.mkv)|(m3u8)).*(\?.+)?)|(akamaized)|(bilivideo.com)""",
    /** 访问播放页时携带的 Cookie（每行一条 k=v） */
    val cookies: String = "quality=1080",
    val addHeadersToVideo: VideoHeaders = VideoHeaders(),
)

@Serializable
data class VideoHeaders(
    val referer: String = "",
    val userAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3",
)

@Serializable
data class SelectorAutoMatchConfig(
    val enabled: Boolean = true,
    val searchUseOnlyFirstWord: Boolean = true,
    val searchRemoveSpecial: Boolean = true,
    val searchUseSubjectNamesCount: Int = 1,
    val preferShorterName: Boolean = true,
    val filterByEpisodeSort: Boolean = true,
) {
    /** 关键词预处理：只取第一个词 + 去特殊字符 */
    fun processKeyword(keyword: String): String {
        var kw = keyword.trim()
        if (searchUseOnlyFirstWord) {
            kw = kw.split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() } ?: kw
        }
        if (searchRemoveSpecial) {
            kw = kw.replace(Regex("[!-/:-@\\[-`{-~！？，。、；：「」『』（）【】《》…—]"), "")
        }
        return kw
    }
}

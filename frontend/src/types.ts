// 与后端 API 对应的类型定义

export interface BgmImages {
  large?: string | null
  common?: string | null
  medium?: string | null
  small?: string | null
  grid?: string | null
}

export interface BgmRating {
  rank?: number | null
  total?: number | null
  score?: number | null
}

export interface BgmEpisode {
  id: number
  name: string
  name_cn: string
  sort?: number | null
  duration?: string | null
  airdate?: string | null
}

export interface BgmSubject {
  id: number
  name: string
  name_cn: string
  summary: string
  date?: string | null
  score?: number | null
  rating?: BgmRating | null
  rank?: number | null
  images?: BgmImages | null
  /** 详情接口回填为数组；/api/bangumi/search 原样透传 bangumi 的集数整数 */
  eps?: BgmEpisode[] | number
}

export interface SubjectHit {
  name: string
  url: string
  cover?: string | null
}

export interface EpisodeHit {
  channel: string | null
  name: string
  sort: string
  sortNum: number | null
  playUrl: string
}

export interface ChannelGroupHit {
  name: string
  episodes: EpisodeHit[]
}

export interface EpisodesResult {
  channels: string[]
  episodes: EpisodeHit[]
  groups: ChannelGroupHit[]
}

/**
 * 数据源解析结果。服务端只给出可直接播放的代理地址：
 * UA/Referer/Cookie 换成一个短期会话号留在服务端，不再下发给浏览器。
 */
export interface VideoSource {
  url: string
  proxyPath: string
}

export interface SelectorSearchConfig {
  searchUrl: string
  rawBaseUrl?: string
  requestInterval: number
  searchCacheTtl?: number
  userAgent?: string
  /** 取值：a / indexed / json-path-indexed */
  subjectFormatId: string
  /** subjectFormatId=a：CSS 选出的 <a>，title/text 作名称、href 作链接 */
  selectorSubjectFormatA?: { selectLists: string; linkTemplate?: string | null }
  /** subjectFormatId=indexed：名字列表与链接列表两条 CSS 按下标对应 */
  selectorSubjectFormatIndexed?: {
    selectNames: string
    selectLinks: string
    linkTemplate?: string | null
  }
  /** subjectFormatId=json-path-indexed：JsonPath 分别取名称与链接 */
  selectorSubjectFormatJsonPathIndexed?: {
    selectNames: string
    selectLinks: string
    linkTemplate?: string | null
  }
  /** 取值：no-channel / index-grouped */
  channelFormatId: string
  /** channelFormatId=no-channel */
  selectorChannelFormatNoChannel?: {
    selectEpisodes: string
    selectEpisodeLinks?: string
    matchEpisodeSortFromName?: string
  }
  /** channelFormatId=index-grouped：线路 tab + 多个播放列表（后端字段名就是 Flattened） */
  selectorChannelFormatFlattened?: {
    selectChannelNames?: string
    matchChannelName?: string
    selectEpisodeLists?: string
    selectEpisodesFromList?: string
    selectEpisodeLinksFromList?: string
    matchEpisodeSortFromName?: string
  }
  matchVideo?: {
    matchVideoUrl: string
    matchNestedUrl?: string
    enableNestedUrl?: boolean
    cookies?: string
    addHeadersToVideo?: { referer?: string; userAgent?: string }
  }
  autoMatch?: {
    enabled?: boolean
    searchUseOnlyFirstWord?: boolean
    searchRemoveSpecial?: boolean
    searchUseSubjectNamesCount?: number
    preferShorterName?: boolean
    filterByEpisodeSort?: boolean
  }
  selectMedia?: { distinguishSubjectName?: boolean; distinguishChannelName?: boolean }
  defaultResolution?: string
  defaultSubtitleLanguage?: string
  onlySupportsPlayers?: string[]
  /** 旧版平铺字段，后端解析时会折叠进 autoMatch */
  searchUseOnlyFirstWord?: boolean | null
  searchRemoveSpecial?: boolean | null
  searchUseSubjectNamesCount?: number | null
  filterByEpisodeSort?: boolean | null
}

export interface SelectorSourceArguments {
  name: string
  description: string
  iconUrl: string
  searchConfig: SelectorSearchConfig
  /** animeko 侧源优先级：社区配置里是数字（0..4），内置示例里是字符串，服务端不使用 */
  tier?: number | string | null
  /** 线路名 -> 优先级，值同上 */
  channelTiers?: Record<string, number | string> | null
}

export interface StoredSource {
  factoryId: string
  version: number
  enabled: boolean
  arguments: SelectorSourceArguments
}

/** 聚合订阅里的一条可导入源（后端只回展示需要的字段） */
export interface SubscriptionCandidate {
  name: string
  description: string
  /** 订阅里可能是数字分级，也可能是字符串，后端已统一成文本 */
  tier: string
  /** 站点主机名，便于一眼看出来源 */
  site: string
  /** 本机已有同名源，导入会覆盖它 */
  exists: boolean
  enabled: boolean
}

export interface SubscriptionPreview {
  subscription: string
  candidates: SubscriptionCandidate[]
  /** 结构不完整、导入后会变成死源的条目及原因 */
  rejected: string[]
  /** 正文内容指纹：导入时回传，防止预览之后订阅被更新、按旧清单导入 */
  fingerprint: string
}

export interface SubscriptionImport {
  added: string[]
  updated: string[]
  failed: string[]
  rejected: string[]
}

/**
 * 服务端个人数据文档。字段由前端定义、后端原样存取，
 * 因此这里全部可选：老文档缺字段、新字段先上线都不能让页面崩。
 */
export interface UserData {
  favorites?: unknown
  recent?: unknown
  searchHistory?: unknown
  progress?: Record<string, unknown>
  [key: string]: unknown
}

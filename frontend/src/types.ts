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

export interface ResolvedVideo {
  url: string
  referer: string
  userAgent: string
  cookies: string
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
  tier?: string
  channelTiers?: Record<string, string>
}

export interface StoredSource {
  factoryId: string
  version: number
  enabled: boolean
  arguments: SelectorSourceArguments
}

export interface DdEpisode {
  episodeId: number
  episodeTitle: string
}

export interface DdAnime {
  animeId: number
  animeTitle: string
  type: string
  episodes: DdEpisode[]
}

export interface DdComment {
  cid: number
  /** 出现时间（秒） */
  time: number
  text: string
  /** 十进制 RGB */
  color: number
  /** 1 滚动 / 5 顶部 / 4 底部 */
  mode: number
}

export interface DanmakuOptions {
  enabled: boolean
  fontSize: number
  opacity: number
  speed: number
  density: number
  blockWords: string
}

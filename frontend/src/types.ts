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
  eps?: BgmEpisode[]
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
  subjectFormatId: string
  /** subjectFormatId=selector-subject-format-a（CSS 列表页） */
  selectorSubjectFormatA?: { selectLists: string; linkTemplate?: string | null }
  /** subjectFormatId=selector-subject-format-indexed（CSS 索引页） */
  selectorSubjectFormatIndexed?: { selectNames: string; selectLinks: string }
  /** subjectFormatId=json-path-indexed（JSON Path 索引） */
  selectorSubjectFormatJsonPathIndexed?: { selectNames: string; selectLinks: string }
  channelFormatId: string
  /** channelFormatId=selector-channel-format-no-channel */
  selectorChannelFormatNoChannel?: { selectEpisodes: string; matchEpisodeSortFromName?: string | null }
  matchVideo?: { matchVideoUrl: string; enableNestedUrl?: boolean; cookies?: string }
  autoMatch?: { enabled?: boolean; searchUseOnlyFirstWord?: boolean; searchRemoveSpecial?: boolean }
}

export interface SelectorSourceArguments {
  name: string
  description: string
  iconUrl: string
  searchConfig: SelectorSearchConfig
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

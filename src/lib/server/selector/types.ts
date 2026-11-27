// 数据源（web-selector）配置模型，对齐原 Kotlin SelectorConfig。
export interface SelectorSourceArguments {
  name: string;
  description?: string;
  iconUrl?: string;
  searchConfig: SearchConfig;
  tier?: unknown;
  channelTiers?: unknown;
  [key: string]: unknown;
}

export interface SearchConfig {
  searchUrl: string;
  rawBaseUrl?: string;
  requestInterval?: number;
  searchCacheTtl?: number;
  userAgent?: string;
  subjectFormatId?: 'a' | 'indexed' | 'json-path-indexed';
  channelFormatId?: 'no-channel' | 'index-grouped';
  selectorSubjectFormatA?: { selectLists?: string; linkTemplate?: string | null };
  selectorSubjectFormatIndexed?: { selectNames?: string; selectLinks?: string; linkTemplate?: string | null };
  selectorSubjectFormatJsonPathIndexed?: { selectNames?: string; selectLinks?: string; linkTemplate?: string | null };
  selectorChannelFormatNoChannel?: {
    selectEpisodes?: string;
    selectEpisodeLinks?: string;
    matchEpisodeSortFromName?: string;
  };
  selectorChannelFormatFlattened?: {
    selectChannelNames?: string;
    matchChannelName?: string;
    selectEpisodeLists?: string;
    selectEpisodesFromList?: string;
    selectEpisodeLinksFromList?: string;
    matchEpisodeSortFromName?: string;
  };
  matchVideo?: {
    enableNestedUrl?: boolean;
    matchNestedUrl?: string;
    matchVideoUrl?: string;
    cookies?: string;
    cookieHosts?: string[];
    addHeadersToVideo?: { referer?: string; userAgent?: string };
  };
  autoMatch?: {
    enabled?: boolean;
    searchUseOnlyFirstWord?: boolean;
    searchRemoveSpecial?: boolean;
    searchUseSubjectNamesCount?: number;
    preferShorterName?: boolean;
    filterByEpisodeSort?: boolean;
  };
  [key: string]: unknown;
}

export interface StoredSource {
  factoryId: string;
  version: number;
  enabled: boolean;
  arguments: SelectorSourceArguments;
}

export interface SubjectHit {
  name: string;
  url: string;
  cover: string | null;
}

export interface EpisodeHit {
  channel: string | null;
  name: string;
  sort: string;
  sortNum: number | null;
  playUrl: string;
}

export interface EpisodesResult {
  channels: string[];
  episodes: EpisodeHit[];
  groups: Array<{ name: string; episodes: EpisodeHit[] }>;
}

export interface ResolvedVideo {
  url: string;
  referer: string;
  userAgent: string;
  cookie: string;
  cookieHosts: string[];
}

export const DEFAULT_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

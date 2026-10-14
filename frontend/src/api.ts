import type {
  BgmSubject,
  DdAnime,
  DdComment,
  EpisodesResult,
  ResolvedVideo,
  StoredSource,
  SubjectHit,
} from './types'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const resp = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!resp.ok) {
    let message = `${resp.status} ${resp.statusText}`
    try {
      const body = await resp.json()
      // 服务端对未预期异常只回 INTERNAL_ERROR（细节进服务端日志），
      // 所以这里必须保留状态码，否则用户报障时没有任何可定位信息
      if (body?.error) message = `${resp.status} ${body.error}`
    } catch {
      // 非 JSON 错误响应
    }
    throw new Error(message)
  }
  return (await resp.json()) as T
}

export const api = {
  health: () =>
    request<{ status: string; version: string; danmakuConfigured: boolean; authEnabled: boolean }>(
      '/api/health',
    ),

  // ---- Bangumi ----
  searchBangumi: (q: string) =>
    request<BgmSubject[]>(`/api/bangumi/search?q=${encodeURIComponent(q)}`),
  bangumiSubject: (id: number) => request<BgmSubject>(`/api/bangumi/subjects/${id}`),

  // ---- 数据源 ----
  sources: () => request<StoredSource[]>('/api/selector/sources'),
  importSource: (json: string) =>
    request<StoredSource['arguments']>('/api/selector/sources', { method: 'POST', body: json }),
  deleteSource: (name: string) =>
    request<{ deleted: string }>(`/api/selector/sources/${encodeURIComponent(name)}`, {
      method: 'DELETE',
    }),
  setSourceEnabled: (name: string, enabled: boolean) =>
    request<{ name: string; enabled: boolean }>(
      `/api/selector/sources/${encodeURIComponent(name)}/enable`,
      { method: 'POST', body: JSON.stringify({ enabled }) },
    ),
  sourceSearch: (name: string, keyword: string) =>
    request<SubjectHit[]>(`/api/selector/sources/${encodeURIComponent(name)}/search`, {
      method: 'POST',
      body: JSON.stringify({ keyword }),
    }),
  sourceEpisodes: (name: string, subjectUrl: string) =>
    request<EpisodesResult>(`/api/selector/sources/${encodeURIComponent(name)}/episodes`, {
      method: 'POST',
      body: JSON.stringify({ subjectUrl }),
    }),
  resolveVideo: (name: string, playUrl: string) =>
    request<ResolvedVideo>(`/api/selector/sources/${encodeURIComponent(name)}/video`, {
      method: 'POST',
      body: JSON.stringify({ playUrl }),
    }),

  // ---- 弹幕 ----
  danmakuSearch: (anime: string, episode?: string) =>
    request<DdAnime[]>(
      `/api/danmaku/search?anime=${encodeURIComponent(anime)}` +
        (episode ? `&episode=${encodeURIComponent(episode)}` : ''),
    ),
  danmakuComments: (episodeId: number) =>
    request<DdComment[]>(`/api/danmaku/comments?episodeId=${episodeId}`),
}

/** 统一错误消息提取（catch 的 e 为 unknown） */
export function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/** 把解析出的视频地址改写为经过服务端代理的地址（携带 UA/Referer/Cookie） */
export function proxyUrl(video: ResolvedVideo): string {
  const params = new URLSearchParams({ url: video.url })
  if (video.userAgent) params.set('ua', video.userAgent)
  if (video.referer) params.set('referer', video.referer)
  if (video.cookies) params.set('cookie', video.cookies)
  return `/api/proxy?${params.toString()}`
}

/** 是否可直接播放（mp4/webm/ogg 直链，无需 hls.js） */
export function isDirectPlayable(url: string): boolean {
  // 注意：播放地址通常是 /api/proxy?url=<encoded>&ua=...&referer=... 形式，
  // url 参数后面还带有 ua/referer/cookie，媒体后缀不在字符串末尾。
  // 必须先取出 url 参数的值再判断后缀，否则直链视频会被误判为 HLS
  // （hls.js 把 WebM 字节流当 m3u8 解析 → manifestParsingError → 播放失败）
  let target = url
  if (url.includes('/api/proxy?')) {
    try {
      const inner = new URLSearchParams(url.slice(url.indexOf('?') + 1)).get('url')
      if (inner) target = inner
    } catch {
      // 查询串解析失败时退回原始字符串判断
    }
  }
  return /\.(mp4|webm|ogg)($|\?)/i.test(target)
}

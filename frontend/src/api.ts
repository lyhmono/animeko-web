import type {
  BgmCalendarDay,
  BgmChannelInfo,
  BgmChannelKind,
  BgmProbeResult,
  BgmSubject,
  BgmSubjectPage,
  EpisodesResult,
  VideoSource,
  StoredSource,
  SubjectHit,
  SubscriptionPreview,
  SubscriptionImport,
  UserData,
} from './types'

/** 带 HTTP 状态的错误：数据层要区分 401（会话没了）/409（版本不符）/400（超限或格式错）。 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, message: string, code: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

/**
 * API 请求的总超时。Bangumi 后端超时是 45 秒，前端留出余量；
 * 没有它，一个半开连接会让数据层的 PUT 永远停在「保存中」，后续写入全部排队。
 */
const REQUEST_TIMEOUT_MS = 60_000
const activeRequests = new Set<AbortController>()

/** 确认切换或撤销会话时中止旧请求，迟到的 401 不得踢掉新会话。 */
export function invalidateApiSession(): void {
  const previous = [...activeRequests]
  previous.forEach((controller) => activeRequests.delete(controller))
  previous.forEach((controller) => controller.abort(new DOMException('会话已切换', 'AbortError')))
}

/** fetch、错误正文和成功正文共用一个期限；读完正文才释放取消监听。 */
async function send<T>(path: string, consume: (resp: Response) => Promise<T>, init?: RequestInit): Promise<T> {
  const controller = new AbortController()
  activeRequests.add(controller)
  const outer = init?.signal ?? undefined
  const onOuterAbort = () => controller.abort(outer?.reason)
  if (outer) {
    if (outer.aborted) onOuterAbort()
    else outer.addEventListener('abort', onOuterAbort, { once: true })
  }
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, REQUEST_TIMEOUT_MS)
  try {
    const resp = await fetch(path, {
      ...init,
      signal: controller.signal,
      headers: { 'Content-Type': 'application/json', ...init?.headers },
    })
    if (!resp.ok) {
      let message = `${resp.status} ${resp.statusText}`
      let code = ''
      try {
        const body = await resp.json()
        if (body?.error) {
          code = String(body.error)
          message = `${resp.status} ${body.error}`
        }
      } catch {
        // 非 JSON 错误响应仍保留 HTTP 状态；取消不能降级成业务错误。
      }
      controller.signal.throwIfAborted()
      if (resp.status === 401 && path !== LOGIN_PATH) {
        window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
      }
      throw new ApiError(resp.status, message, code)
    }
    const result = await consume(resp)
    controller.signal.throwIfAborted()
    return result
  } catch (e) {
    if (timedOut) throw new Error(`请求超时（${REQUEST_TIMEOUT_MS / 1000} 秒），请检查网络或稍后重试`)
    throw e
  } finally {
    activeRequests.delete(controller)
    clearTimeout(timer)
    outer?.removeEventListener('abort', onOuterAbort)
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  return send(path, (resp) => resp.json() as Promise<T>, init)
}

/** ETag 在 HTTP 上是带引号的字符串，比较前先脱引号（弱标记 W/ 也一起去掉）。 */
function unquote(value: string | null): string {
  if (!value) return ''
  return value.replace(/^W\//, '').replace(/^"|"$/g, '').trim()
}

/** 错误码常量：数据层按它决定「重试」还是「重读再合并」。 */
export const ERR_DATA_STALE = 'DATA_STALE'

export function isApiError(e: unknown, status?: number, code?: string): boolean {
  if (!(e instanceof ApiError)) return false
  if (status !== undefined && e.status !== status) return false
  if (code !== undefined && e.code !== code) return false
  return true
}

export const UNAUTHORIZED_EVENT = 'animeko-unauthorized'
const LOGIN_PATH = '/api/session/login'

export const api = {
  health: (signal?: AbortSignal) =>
    request<{ status: string; version: string; authEnabled: boolean; authenticated: boolean }>(
      '/api/health',
      { signal },
    ),

  // ---- 登录会话 ----
  login: (username: string, password: string, signal?: AbortSignal) =>
    request<{ authenticated: boolean; user: string }>(LOGIN_PATH, {
      method: 'POST',
      body: JSON.stringify({ username, password }),
      signal,
    }),
  logout: (signal?: AbortSignal) => request<{ authenticated: boolean }>('/api/session/logout', { method: 'POST', signal }),

  // ---- 个人数据（收藏 / 历史 / 进度，存服务端）----
  /**
   * 读文档并拿到版本指纹。
   * 一定要 ETag：PUT 是整份覆盖，没有基准的话，另一个标签页/设备的改动会被静默吃掉。
   */
  loadDataWithEtag: async (signal?: AbortSignal): Promise<{ doc: UserData; etag: string }> => {
    // no-store 两头都要写：服务端已经发了这个 Cache-Control，但老镜像还没重建时，
    // 浏览器自己的启发式缓存仍可能把上一个账号的收藏喂回来。
    return send('/api/data', async (resp) => {
      const text = await resp.text()
      let doc: UserData = {}
      if (text.trim()) {
        const parsed = JSON.parse(text) as unknown
        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) doc = parsed as UserData
      }
      return { doc, etag: unquote(resp.headers.get('ETag')) }
    }, { cache: 'no-store', signal })
  },
  /**
   * 条件写。返回服务端新版本与落盘字节数；版本不符时服务端回 409 DATA_STALE。
   * bytes 不是装饰：服务端有 512KB 的文档上限，撞上之后每次保存都是 400，
   * 而「收藏点不动」这种症状没人会想到是满了 —— 所以要提前说。
   */
  saveData: async (doc: unknown, etag: string, signal?: AbortSignal): Promise<{ etag: string; bytes: number }> => {
    return send('/api/data', async (resp) => {
      let bytes = 0
      try {
        const body = (await resp.json()) as { bytes?: number }
        bytes = typeof body?.bytes === 'number' ? body.bytes : 0
      } catch (e) {
        // 仅兼容旧服务端的空/非 JSON 正文；传输失败不能确认本次提交。
        if (!(e instanceof SyntaxError)) throw e
      }
      return { etag: unquote(resp.headers.get('ETag')), bytes }
    }, {
      method: 'PUT',
      body: JSON.stringify(doc),
      headers: etag ? { 'If-Match': `"${etag}"` } : undefined,
      signal,
    })
  },

  // ---- Bangumi ----
  searchBangumi: (q: string, signal?: AbortSignal) =>
    request<BgmSubject[]>(`/api/bangumi/search?q=${encodeURIComponent(q)}`, { signal }),
  bangumiSubject: (id: number, signal?: AbortSignal) => request<BgmSubject>(`/api/bangumi/subjects/${id}`, { signal }),
  /** 排行榜（发现页）。镜像同样支持该端点，因为它走的是 /v0/subjects */
  bangumiRanking: (offset: number, limit = 24, signal?: AbortSignal) =>
    request<BgmSubjectPage>(`/api/bangumi/ranking?offset=${offset}&limit=${limit}`, { signal }),
  /** 每日放送表。镜像部署下会上游 502，调用方按可选功能处理（拉不到就不显示） */
  bangumiCalendar: (signal?: AbortSignal) =>
    request<BgmCalendarDay[]>(`/api/bangumi/calendar`, { signal }),
  bangumiChannel: (signal?: AbortSignal) => request<BgmChannelInfo>('/api/bangumi/channel', { signal }),
  setBangumiChannel: (channel: BgmChannelKind, customUrls: string[], signal?: AbortSignal) =>
    request<BgmChannelInfo>('/api/bangumi/channel', {
      method: 'PUT',
      body: JSON.stringify({ channel, customUrls }),
      signal,
    }),
  probeBangumiChannel: (target?: { channel: BgmChannelKind; customUrls: string[] }, signal?: AbortSignal) =>
    request<BgmProbeResult[]>('/api/bangumi/channel/probe', {
      method: 'POST',
      body: JSON.stringify(target ?? {}),
      signal,
    }),

  // ---- 数据源 ----
  sources: (signal?: AbortSignal) => request<StoredSource[]>('/api/selector/sources', { signal }),
  importSource: (json: string, signal?: AbortSignal) =>
    request<StoredSource['arguments']>('/api/selector/sources', { method: 'POST', body: json, signal }),
  deleteSource: (name: string, signal?: AbortSignal) =>
    request<{ deleted: string }>(`/api/selector/sources/${encodeURIComponent(name)}`, {
      method: 'DELETE',
      signal,
    }),
  setSourceEnabled: (name: string, enabled: boolean, signal?: AbortSignal) =>
    request<{ name: string; enabled: boolean }>(
      `/api/selector/sources/${encodeURIComponent(name)}/enable`,
      { method: 'POST', body: JSON.stringify({ enabled }), signal },
    ),
  sourceSearch: (name: string, keyword: string, signal?: AbortSignal) =>
    request<SubjectHit[]>(`/api/selector/sources/${encodeURIComponent(name)}/search`, {
      method: 'POST',
      body: JSON.stringify({ keyword }),
      signal,
    }),
  sourceEpisodes: (name: string, subjectUrl: string, signal?: AbortSignal) =>
    request<EpisodesResult>(`/api/selector/sources/${encodeURIComponent(name)}/episodes`, {
      method: 'POST',
      body: JSON.stringify({ subjectUrl }),
      signal,
    }),
  resolveVideo: (name: string, playUrl: string, signal?: AbortSignal) =>
    request<VideoSource>(`/api/selector/sources/${encodeURIComponent(name)}/video`, {
      method: 'POST',
      body: JSON.stringify({ playUrl }),
      signal,
    }),

  // ---- 聚合订阅：先预览、勾选后再导入 ----
  previewSubscription: (input: { url?: string; text?: string }, signal?: AbortSignal) =>
    request<SubscriptionPreview>('/api/selector/subscriptions/preview', {
      method: 'POST',
      body: JSON.stringify(input),
      signal,
    }),
  importSubscription: (input: { url?: string; text?: string; names: string[]; fingerprint?: string }, signal?: AbortSignal) =>
    request<SubscriptionImport>('/api/selector/subscriptions/import', {
      method: 'POST',
      body: JSON.stringify(input),
      signal,
    }),

}

/** 统一错误消息提取（catch 的 e 为 unknown） */
export function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

export function isDirectPlayable(url: string): boolean {
  // 播放地址是 /api/proxy?url=<encoded>（凭据在服务端会话里，URL 只带 sid），
  // 内层真实地址不在字符串末尾。必须先取出 url 参数的值再判断后缀，
  // 否则直链视频会被误判为 HLS（hls.js 把 WebM 字节流当 m3u8 解析 →
  // manifestParsingError → 播放失败）
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

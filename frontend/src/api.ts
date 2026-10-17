import type {
  BgmSubject,
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

/** 发请求并统一处理错误。拆成一层是因为 /api/data 还需要读响应头（ETag）。 */
async function send(path: string, init?: RequestInit): Promise<Response> {
  const resp = await fetch(path, {
    ...init,
    // init.headers 合进来而不是覆盖
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
  if (!resp.ok) {
    let message = `${resp.status} ${resp.statusText}`
    let code = ''
    try {
      const body = await resp.json()
      // 服务端对未预期异常只回 INTERNAL_ERROR（细节进服务端日志），
      // 所以这里必须保留状态码，否则用户报障时没有任何可定位信息
      if (body?.error) {
        code = String(body.error)
        message = `${resp.status} ${body.error}`
      }
    } catch {
      // 非 JSON 错误响应
    }
    // 会话在服务端过期（30 天不用 / 重启容器 / 点了注销）后，任何请求都会撞上 401。
    // 这里广播一次，让外层把界面切回登录页，而不是让用户对着「点了没反应」发呆。
    if (resp.status === 401 && path !== LOGIN_PATH) {
      window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
    }
    throw new ApiError(resp.status, message, code)
  }
  return resp
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  return (await send(path, init)).json() as T
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
  health: () =>
    request<{ status: string; version: string; authEnabled: boolean; authenticated: boolean }>(
      '/api/health',
    ),

  // ---- 登录会话 ----
  login: (username: string, password: string) =>
    request<{ authenticated: boolean; user: string }>(LOGIN_PATH, {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),
  logout: () => request<{ authenticated: boolean }>('/api/session/logout', { method: 'POST' }),

  // ---- 个人数据（收藏 / 历史 / 进度，存服务端）----
  /**
   * 读文档并拿到版本指纹。
   * 一定要 ETag：PUT 是整份覆盖，没有基准的话，另一个标签页/设备的改动会被静默吃掉。
   */
  loadDataWithEtag: async (): Promise<{ doc: UserData; etag: string }> => {
    const resp = await send('/api/data')
    const text = await resp.text()
    let doc: UserData = {}
    if (text.trim()) {
      const parsed = JSON.parse(text) as unknown
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) doc = parsed as UserData
    }
    return { doc, etag: unquote(resp.headers.get('ETag')) }
  },
  /**
   * 条件写。返回服务端新版本与落盘字节数；版本不符时服务端回 409 DATA_STALE。
   * bytes 不是装饰：服务端有 512KB 的文档上限，撞上之后每次保存都是 400，
   * 而「收藏点不动」这种症状没人会想到是满了 —— 所以要提前说。
   */
  saveData: async (doc: unknown, etag: string): Promise<{ etag: string; bytes: number }> => {
    const resp = await send('/api/data', {
      method: 'PUT',
      body: JSON.stringify(doc),
      headers: etag ? { 'If-Match': `"${etag}"` } : undefined,
    })
    let bytes = 0
    try {
      const body = (await resp.json()) as { bytes?: number }
      bytes = typeof body?.bytes === 'number' ? body.bytes : 0
    } catch {
      // 老服务端不回 bytes：不能因此把一次成功的写入当成失败
    }
    return { etag: unquote(resp.headers.get('ETag')), bytes }
  },

  // ---- Bangumi ----
  searchBangumi: (q: string, signal?: AbortSignal) =>
    request<BgmSubject[]>(`/api/bangumi/search?q=${encodeURIComponent(q)}`, { signal }),
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
  sourceSearch: (name: string, keyword: string, signal?: AbortSignal) =>
    request<SubjectHit[]>(`/api/selector/sources/${encodeURIComponent(name)}/search`, {
      method: 'POST',
      body: JSON.stringify({ keyword }),
      signal,
    }),
  sourceEpisodes: (name: string, subjectUrl: string) =>
    request<EpisodesResult>(`/api/selector/sources/${encodeURIComponent(name)}/episodes`, {
      method: 'POST',
      body: JSON.stringify({ subjectUrl }),
    }),
  resolveVideo: (name: string, playUrl: string) =>
    request<VideoSource>(`/api/selector/sources/${encodeURIComponent(name)}/video`, {
      method: 'POST',
      body: JSON.stringify({ playUrl }),
    }),

  // ---- 聚合订阅：先预览、勾选后再导入 ----
  previewSubscription: (input: { url?: string; text?: string }) =>
    request<SubscriptionPreview>('/api/selector/subscriptions/preview', {
      method: 'POST',
      body: JSON.stringify(input),
    }),
  importSubscription: (input: { url?: string; text?: string; names: string[]; fingerprint?: string }) =>
    request<SubscriptionImport>('/api/selector/subscriptions/import', {
      method: 'POST',
      body: JSON.stringify(input),
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

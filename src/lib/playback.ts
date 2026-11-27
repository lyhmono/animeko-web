import type Hls from 'hls.js'
import type { ErrorData } from 'hls.js'

type HlsModule = typeof import('hls.js')
export interface PlaybackOptions {
  isCurrent: () => boolean
  onHlsActive: (active: boolean) => void
  onError: (message: string, status?: number, context?: { code?: string }) => void
  /** Platform seam for a bounded MIME probe, not for streaming the media itself. */
  fetchMedia?: typeof fetch
}

function baseUrl(): string {
  return typeof location !== 'undefined' ? location.origin : 'https://animeko.invalid'
}

function sourceKind(src: string): 'native' | 'hls' | 'unknown' {
  try {
    const url = new URL(src, baseUrl())
    const target = url.pathname === '/api/proxy' ? url.searchParams.get('url') ?? src : src
    const path = new URL(target, baseUrl()).pathname
    if (/\.m3u8$/i.test(path)) return 'hls'
    if (/\.(?:mp4|webm|ogg|ogv|m4v|mov|mkv|mp3|aac|m4a|ts|m4s|flv)$/i.test(path)) return 'native'
  } catch { /* Unknown media can still be accepted by the native player. */ }
  return 'unknown'
}

function proxyErrorCode(src: string, data: ErrorData): string | undefined {
  try {
    const url = new URL(src, baseUrl())
    if (url.origin !== baseUrl() || url.pathname !== '/api/proxy') return undefined
    const details = data.networkDetails as { responseText?: unknown; response?: unknown } | undefined
    let raw: unknown
    // XHR responseText throws for arraybuffer responses (keys/fragments).
    try { raw = details?.responseText } catch { /* Try the binary response instead. */ }
    raw ??= details?.response ?? data.response?.data
    if (raw instanceof ArrayBuffer && raw.byteLength <= 8192) raw = new TextDecoder().decode(raw)
    if (typeof raw !== 'string' || raw.length > 8192) return undefined
    const code = (JSON.parse(raw) as { error?: unknown }).error
    return code === 'PROXY_SESSION_EXPIRED' || code === 'PROXY_SESSION_TARGET_MISMATCH' ? code : undefined
  } catch { return undefined }
}

/** One source generation owns the MIME probe, asynchronous import and HLS instance. */
export function attachPlayback(video: HTMLVideoElement, src: string, options: PlaybackOptions,
  loadHls: () => Promise<HlsModule> = () => import('hls.js')): () => void {
  let disposed = false
  let hls: Hls | undefined
  let recovered = false
  const probeController = new AbortController()
  let probeTimer: ReturnType<typeof setTimeout> | undefined
  const current = () => !disposed && options.isCurrent()
  const native = () => { if (current()) video.src = src }

  const startHls = async () => {
    // Native HLS does not need a library download; retain the Safari/iOS media path.
    if (!current()) return
    if (video.canPlayType('application/vnd.apple.mpegurl')) { native(); return }
    try {
      const { default: Hls } = await loadHls()
      if (!current()) return
      if (!Hls.isSupported()) { options.onError('当前浏览器不支持 HLS 播放'); return }
      const engine = new Hls({ enableWorker: true, fragLoadingTimeOut: 30000 })
      hls = engine
      engine.on(Hls.Events.ERROR, (_event, data) => {
        if (!current() || !data.fatal) return
        if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !recovered) {
          recovered = true
          engine.recoverMediaError()
          return
        }
        const status = data.response?.code
        const message = data.details === Hls.ErrorDetails.MANIFEST_LOAD_ERROR
          ? `视频清单加载失败（HTTP ${status ?? '未知'}，源站可能拒绝了服务端代理）`
          : `播放错误: ${data.details}`
        options.onError(message, status, { code: proxyErrorCode(src, data) })
      })
      options.onHlsActive(true)
      engine.loadSource(src)
      engine.attachMedia(video)
    } catch {
      hls?.destroy()
      hls = undefined
      if (current()) {
        options.onHlsActive(false)
        options.onError('HLS 播放模块加载失败，请重试播放')
      }
    }
  }

  const probeUnknown = async () => {
    // Extensionless URLs can be MP4 or HLS. Do not guess HLS and load the library.
    // Read headers only, then cancel this independent range probe immediately.
    probeTimer = setTimeout(() => probeController.abort(), 10_000)
    let response: Response | undefined
    let isHls = false
    try {
      response = await (options.fetchMedia ?? fetch)(src, {
        method: 'GET', headers: { Range: 'bytes=0-0' }, credentials: 'same-origin', signal: probeController.signal,
      })
      const type = response.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase()
      isHls = response.ok && ['application/vnd.apple.mpegurl', 'application/x-mpegurl', 'audio/mpegurl', 'audio/x-mpegurl'].includes(type ?? '')
    } catch { /* CORS/probe failure is not a playback failure: keep native compatibility. */ }
    finally {
      if (probeTimer != null) clearTimeout(probeTimer)
      probeTimer = undefined
      void response?.body?.cancel().catch(() => {})
      probeController.abort()
    }
    if (!current()) return
    if (isHls) void startHls()
    else native()
  }

  const kind = sourceKind(src)
  if (kind === 'native') native()
  else if (kind === 'hls') void startHls()
  else void probeUnknown()

  return () => {
    if (disposed) return
    disposed = true
    if (probeTimer != null) clearTimeout(probeTimer)
    probeTimer = undefined
    probeController.abort()
    hls?.destroy()
    hls = undefined
    if (options.isCurrent()) options.onHlsActive(false)
  }
}

import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent, type RefObject } from 'react'
import Hls from 'hls.js'
import { isDirectPlayable } from '../api'
import { getPlayerPrefs, savePlayerPrefs } from '../lib/storage'
import {
  IconBrightness,
  IconFullscreen,
  IconFullscreenExit,
  IconGauge,
  IconMute,
  IconNext,
  IconPause,
  IconPip,
  IconPlay,
  IconPrev,
  IconVolume,
} from '../lib/icons'

interface Props {
  /** 首选播放地址（直连或代理） */
  src: string
  /** 首选失败时的备用地址（服务端中转）。给出后，致命错误会自动切一次 */
  fallbackSrc?: string
  /** 加载后一次性定位到的秒数（断点续播） */
  startAt?: number
  /** 全屏目标容器；播放页传入包含控制条的舞台，缺省时全屏本组件 */
  fullscreenTarget?: RefObject<HTMLElement | null>
  /** 影院模式：舞台外框压暗，让放映区成为唯一视觉焦点 */
  theater?: boolean
  onPrevEpisode?: () => void
  onNextEpisode?: () => void
  onProgress?: (time: number, duration: number) => void
  onEnded?: () => void
  /** status 为清单请求的 HTTP 状态码（410=会话过期、403=目标不被允许），供上层决定重新解析 */
  onError?: (message: string, status?: number) => void
}

/**
 * 单击/双击的分界（毫秒），与系统默认的双击间隔一致。
 * 单击动作必须等这么久才执行：否则双击的第一下就会先把控制条切掉、第二下再快进 ——
 * 用户看到的是一次双击同时干了两件事（实测复现过）。双击窗口内的第二次点击会取消它。
 */
/** Safari / iOS 的 webkit 全屏接口（标准 lib.dom 里没有） */
type WebkitFullscreenDoc = {
  webkitFullscreenElement?: Element | null
  webkitExitFullscreen?: () => Promise<void> | void
}
type WebkitFullscreenTarget = HTMLElement & { webkitRequestFullscreen?: () => Promise<void> | void }
type WebkitFullscreenVideo = HTMLVideoElement & { webkitEnterFullscreen?: () => void }

const DOUBLE_TAP_MS = 300

function formatTime(seconds: number): string {
  if (!isFinite(seconds) || seconds < 0) return '00:00'
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = Math.floor(seconds % 60)
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

/** 判断事件目标是否为可输入元素（输入框聚焦时不拦截快捷键） */
function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  const tag = target.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable
}

/** 中央浮层提示的三种形状：跳转秒数 / 亮度 / 音量 */
interface GestureToast {
  kind: 'seek' | 'brightness' | 'volume'
  text: string
  /** 0..1，用于亮度/音量指示条的填充比例 */
  level: number
}

/**
 * 倍速档位。单一来源：偏好恢复时的吸附与菜单渲染都必须用这一份 ——
 * 两处各写一份数组时，存了一个不在档位里的值（如 1.75）会出现
 * 「播放速率确实变了，但菜单里一项都不高亮」的怪状态。
 */
const RATES = [0.5, 0.75, 1, 1.25, 1.5, 2] as const

/** 把任意速率吸附到最近的档位 */
function snapRate(value: number): number {
  return RATES.reduce(
    (best, r) => (Math.abs(r - value) < Math.abs(best - value) ? r : best),
    RATES[0] as number,
  )
}

export default function VideoPlayer({
  src,
  fallbackSrc,
  startAt,
  fullscreenTarget,
  theater = false,
  onPrevEpisode,
  onNextEpisode,
  onProgress,
  onEnded,
  onError,
}: Props) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const [playing, setPlaying] = useState(false)
  const [current, setCurrent] = useState(0)
  const [duration, setDuration] = useState(0)
  // 偏好只读一次（三个 useState 各自调一次 getPlayerPrefs 会读三遍 localStorage + 解析三遍 JSON）
  const [initialPrefs] = useState(getPlayerPrefs)
  const [volume, setVolume] = useState(initialPrefs.volume)
  const [muted, setMuted] = useState(initialPrefs.muted)
  const [rate, setRate] = useState(() => snapRate(initialPrefs.rate))
  /** 播放器偏好落盘的去抖定时器（音量拖动会连发几十次变化） */
  const prefsTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const [error, setError] = useState<string | null>(null)
  const [fullscreen, setFullscreen] = useState(false)
  const [autoplayBlocked, setAutoplayBlocked] = useState(false)
  const autoplayTriedRef = useRef(false)

  // ---- 影院化控制层的新增状态 ----
  /** 控制条是否可见：播放中 3 秒无操作自动隐藏，暂停/悬停时常驻 */
  const [controlsVisible, setControlsVisible] = useState(true)
  const hideTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  /** 上游缓冲中（waiting）：转圈提示，别让「卡住」和「暂停」看起来一样 */
  const [buffering, setBuffering] = useState(false)
  /** 已缓冲到的秒数（video.buffered 末段），画在进度条底层 */
  const [bufferedEnd, setBufferedEnd] = useState(0)
  /** 手势/双击的中央浮层提示，1.2 秒后自动消退 */
  const [gestureToast, setGestureToast] = useState<GestureToast | null>(null)
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  /** 等待双击窗口结束的单击动作（显隐控制条） */
  const tapTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  /** 控制条当前是否可见：单击动作要延迟 300ms 才执行，闭包里的 state 可能已过期 */
  const controlsVisibleRef = useRef(true)
  /** 亮度滤镜值（1 = 原样），手势调节时临时压暗/提亮画面 */
  const brightnessRef = useRef(1)
  /** 倍速菜单开合（自绘 popover 取代原生 select 的视觉） */
  const [rateOpen, setRateOpen] = useState(false)

  // 用 ref 持有最新回调：父组件每次渲染都会传入新的回调引用，
  // 若把回调列入 effect 依赖，timeupdate → 父组件重渲染 → 回调引用变化 →
  // effect 清理时 removeAttribute('src')+load() 会重置视频，播放永远无法持续
  const onErrorRef = useRef(onError)
  const onProgressRef = useRef(onProgress)
  const onEndedRef = useRef(onEnded)
  const fullscreenTargetRef = useRef(fullscreenTarget)
  /** 事件回调只绑一次，回退状态要经 ref 读取最新值（同上面几个回调的理由） */
  const fallbackSrcRef = useRef(fallbackSrc)
  const useFallbackRef = useRef(false)
  useEffect(() => {
    onErrorRef.current = onError
    onProgressRef.current = onProgress
    onEndedRef.current = onEnded
    fullscreenTargetRef.current = fullscreenTarget
    fallbackSrcRef.current = fallbackSrc
    useFallbackRef.current = useFallback
  })

  /**
   * 直连优先：主地址失败（防盗链、CORS、网络）时切到服务端中转，只切一次。
   * 换源/重新解析（src 变化）后重新给直连一次机会 —— 新地址可能是另一台主机。
   */
  const [useFallback, setUseFallback] = useState(false)
  const [lastPrimarySrc, setLastPrimarySrc] = useState(src)
  if (src !== lastPrimarySrc) {
    // render 阶段同步派生状态：用 effect 重置会让新 src 先带着旧的 useFallback 加载一帧
    setLastPrimarySrc(src)
    if (useFallback) setUseFallback(false)
  }
  const activeSrc = useFallback && fallbackSrc ? fallbackSrc : src

  // 加载源
  const startAtAppliedRef = useRef(false)
  /** 换源（直连失败回退、410 重解析）时要续上的播放位置；由上一份源的清理逻辑写入 */
  const resumeAtRef = useRef(0)
  useEffect(() => {
    const video = videoRef.current
    if (!video || !activeSrc) return
    let hls: Hls | null = null
    setError(null)
    // 换源（切集）后要重新允许定位一次，否则第二集的断点续播永远不生效
    startAtAppliedRef.current = false
    // 同样要重新允许自动开播一次：连播是整页跳转，新视频不该要求再点一下
    autoplayTriedRef.current = false
    setAutoplayBlocked(false)
    // 亮度滤镜和倍速一样是「这一部的观看设置」而不是全局设置：手势调暗后切集，
    // 不重置的话用户没再碰亮度，下一集画面却压着暗（filter 在 style 特性上，
    // removeAttribute('src') 清不掉它，只能显式抹）
    applyBrightness(1)

    if (isDirectPlayable(activeSrc)) {
      video.src = activeSrc
    } else if (Hls.isSupported()) {
      hls = new Hls({ enableWorker: true, fragLoadingTimeOut: 30000 })
      hls.loadSource(activeSrc)
      hls.attachMedia(video)
      hls.on(Hls.Events.ERROR, (_event, data) => {
        if (!data.fatal) return
        // 直连的清单拿不到（CORS/防盗链）：先悄悄换成服务端中转，这不算最终失败
        if (!useFallback && fallbackSrc) {
          setUseFallback(true)
          return
        }
        // 清单加载失败时把 HTTP 状态码一起抛出：410 是「播放会话过期」，
        // 与「源站拒绝代理」是两回事，上层据此自动重新解析一次
        const status = (data.response as { code?: number } | undefined)?.code
        const msg =
          data.details === Hls.ErrorDetails.MANIFEST_LOAD_ERROR
            ? `视频清单加载失败（HTTP ${status ?? '未知'}，源站可能拒绝了服务端代理）`
            : `播放错误: ${data.details}`
        setError(msg)
        onErrorRef.current?.(msg, status)
      })
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = activeSrc
    } else {
      const msg = '当前浏览器不支持 HLS 播放'
      setError(msg)
      onErrorRef.current?.(msg)
    }

    return () => {
      // 换源前记住当前位置：直接回退/410 重解析换的是同一集的另一条地址，
      // 不能再用最初的续播点重新定位（那会把看到一半的视频倒回起点）
      const at = video.currentTime
      if (Number.isFinite(at) && at > 0) resumeAtRef.current = at
      hls?.destroy()
      video.removeAttribute('src')
      video.load()
    }
  }, [activeSrc])

  // 断点续播：加载后定位一次
  useEffect(() => {
    const video = videoRef.current
    if (!video || startAtAppliedRef.current) return
    if (!startAt && resumeAtRef.current <= 0) return
    const apply = () => {
      if (startAtAppliedRef.current) return
      // 同一次播放中途换源时优先续上换源前的位置；首次加载才用 URL 里的续播点
      const target = resumeAtRef.current > 0 ? resumeAtRef.current : (startAt ?? 0)
      try {
        video.currentTime = target
        startAtAppliedRef.current = true
        resumeAtRef.current = 0
      } catch {
        // 元数据未就绪时忽略，等待下一次 loadedmetadata
      }
    }
    if (video.readyState >= 1) apply()
    video.addEventListener('loadedmetadata', apply)
    return () => video.removeEventListener('loadedmetadata', apply)
  }, [startAt, activeSrc])

  // 只有同一集的备用地址切换才续上当前位置；主地址换了（换集）必须清掉，
  // 否则会把上一集的秒数用到新一集上。
  useEffect(() => {
    resumeAtRef.current = 0
  }, [src])

  // ---------- 影院化控制层 ----------

  const showToast = (toast: GestureToast) => {
    setGestureToast(toast)
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current)
    toastTimerRef.current = setTimeout(() => setGestureToast(null), 1200)
  }

  /** 显露控制条并（播放中）安排一次自动隐藏 */
  const revealControls = useCallback(() => {
    setControlsVisible(true)
    if (hideTimerRef.current) clearTimeout(hideTimerRef.current)
    hideTimerRef.current = setTimeout(() => {
      // 暂停时不隐藏：暂停是「正在操作」的状态，把控制条收起来只会逼用户再晃一次鼠标
      if (!videoRef.current?.paused) setControlsVisible(false)
    }, 3000)
  }, [])

  useEffect(() => {
    revealControls()
    return () => {
      if (hideTimerRef.current) clearTimeout(hideTimerRef.current)
      if (toastTimerRef.current) clearTimeout(toastTimerRef.current)
      if (tapTimerRef.current) clearTimeout(tapTimerRef.current)
    }
  }, [revealControls])

  // 延迟执行的单击动作读这个 ref，而不是闭包里那份可能已过期的 state
  useEffect(() => {
    controlsVisibleRef.current = controlsVisible
  }, [controlsVisible])

  // 缓冲进度：轮询 video.buffered（hls.js 不会把它同步到 DOM 事件上，
  // 而 progress 事件只在当前播放位置推进时触发，seek 到未缓冲处就看不出差距）
  useEffect(() => {
    const tick = () => {
      const video = videoRef.current
      if (!video) return
      let end = 0
      for (let i = 0; i < video.buffered.length; i++) {
        if (video.buffered.start(i) <= video.currentTime) end = Math.max(end, video.buffered.end(i))
      }
      setBufferedEnd(end)
    }
    const id = setInterval(tick, 1000)
    tick()
    return () => clearInterval(id)
  }, [])

  // 事件绑定（只绑一次，回调经 ref 读取最新版本）
  useEffect(() => {
    const video = videoRef.current
    if (!video) return
    const onTime = () => {
      setCurrent(video.currentTime)
      onProgressRef.current?.(video.currentTime, video.duration || 0)
    }
    const onMeta = () => setDuration(video.duration || 0)
    const onPlay = () => {
      setPlaying(true)
      setAutoplayBlocked(false)
      setBuffering(false)
    }
    const onPause = () => setPlaying(false)
    // 媒体就绪时自己开始播。以前整条链路上只有 togglePlay 会调 play()，
    // 也就是说**从不自动开播**：而控件是自己画的（controls={false}，没有原生大播放键），
    // 于是「加载完停在画面上」和「坏了」看起来一模一样，连播每集都要再点一次。
    // 浏览器可能拒绝（无用户手势），那种情况显式给一个居中的播放按钮，别让用户猜。
    const onCanPlay = () => {
      const v = videoRef.current
      // 没有 src（hls.js 还没 attach 上）时不记「已尝试」，否则这一次空转会吃掉整集的自动开播
      if (!v || autoplayTriedRef.current || !v.src) return
      autoplayTriedRef.current = true
      // 断点续播的定位与这里并行没问题：currentTime 在播放中同样可以赋值
      v.play().catch(() => setAutoplayBlocked(true))
    }
    const onEnd = () => {
      setPlaying(false)
      onEndedRef.current?.()
    }
    // 上游缓冲中：真正的等待和「暂停中」界面必须区分开
    const onWaiting = () => setBuffering(true)
    const onPlaying = () => setBuffering(false)
    // 直链（mp4/webm）路径没有 hls.js 的 ERROR 事件可订阅，
    // 代理 403/404 时以前什么都不会发生：画面黑屏、也没有错误提示。
    const onMediaError = () => {
      // 没有 error 对象的 error 事件是切源/卸载清理时补发的，别当成播放失败
      if (!video.error) return
      // 直连被源站拒绝（403/404/CORS）：先切服务端中转再试一次
      if (!useFallbackRef.current && fallbackSrcRef.current) {
        setUseFallback(true)
        setError(null)
        return
      }
      const code = video.error.code
      const msg = `视频加载失败（MEDIA_ERROR_${code}），源站可能拒绝了代理请求，可尝试下方手动地址播放`
      setError(msg)
      onErrorRef.current?.(msg)
    }
    // 标准全屏 + Safari 的 webkit 变体都要认，否则按钮状态会停在旧值
    const onFs = () =>
      setFullscreen(
        document.fullscreenElement != null ||
          (document as Document & WebkitFullscreenDoc).webkitFullscreenElement != null,
      )
    // iOS 原生播放器全屏（webkitEnterFullscreen）不触发 fullscreenchange，
    // 只在这两个事件里通知
    const onNativeFsBegin = () => setFullscreen(true)
    const onNativeFsEnd = () => setFullscreen(false)
    video.addEventListener('timeupdate', onTime)
    video.addEventListener('loadedmetadata', onMeta)
    video.addEventListener('durationchange', onMeta)
    video.addEventListener('play', onPlay)
    video.addEventListener('pause', onPause)
    video.addEventListener('canplay', onCanPlay)
    video.addEventListener('ended', onEnd)
    video.addEventListener('error', onMediaError)
    video.addEventListener('waiting', onWaiting)
    video.addEventListener('playing', onPlaying)
    document.addEventListener('fullscreenchange', onFs)
    document.addEventListener('webkitfullscreenchange', onFs)
    video.addEventListener('webkitbeginfullscreen', onNativeFsBegin)
    video.addEventListener('webkitendfullscreen', onNativeFsEnd)
    return () => {
      video.removeEventListener('timeupdate', onTime)
      video.removeEventListener('loadedmetadata', onMeta)
      video.removeEventListener('durationchange', onMeta)
      video.removeEventListener('play', onPlay)
      video.removeEventListener('pause', onPause)
      video.removeEventListener('canplay', onCanPlay)
      video.removeEventListener('ended', onEnd)
      video.removeEventListener('error', onMediaError)
      video.removeEventListener('waiting', onWaiting)
      video.removeEventListener('playing', onPlaying)
      document.removeEventListener('fullscreenchange', onFs)
      document.removeEventListener('webkitfullscreenchange', onFs)
      video.removeEventListener('webkitbeginfullscreen', onNativeFsBegin)
      video.removeEventListener('webkitendfullscreen', onNativeFsEnd)
    }
  }, [])

  useEffect(() => {
    const video = videoRef.current
    if (video) {
      video.volume = volume
      video.muted = muted
    }
  }, [volume, muted])

  useEffect(() => {
    const video = videoRef.current
    if (video) video.playbackRate = rate
  }, [rate])

  // 把音量/静音/倍速记到本地：下次打开沿用上次的设置。
  // 去抖 400ms —— 拖音量条时这里会被连续触发几十次，每次都写 localStorage
  // 虽然不慢，但没必要；而卸载时**不清**定时器，否则最后那一下调整会丢。
  useEffect(() => {
    if (prefsTimerRef.current) clearTimeout(prefsTimerRef.current)
    prefsTimerRef.current = setTimeout(() => savePlayerPrefs({ volume, muted, rate }), 400)
  }, [volume, muted, rate])

  const togglePlay = () => {
    const video = videoRef.current
    if (!video) return
    if (video.paused) void video.play().catch(() => setPlaying(false))
    else video.pause()
  }

  const seekBy = (delta: number) => {
    const video = videoRef.current
    if (!video) return
    video.currentTime = Math.max(0, Math.min(video.duration || Infinity, video.currentTime + delta))
    setCurrent(video.currentTime)
  }

  const seek = (value: number) => {
    const video = videoRef.current
    if (!video) return
    video.currentTime = value
    setCurrent(value)
  }

  const changeVolume = (delta: number) => {
    setVolume((v) => {
      const next = Math.round(Math.min(1, Math.max(0, v + delta)) * 100) / 100
      if (next > 0) setMuted(false)
      return next
    })
  }

  const toggleFullscreen = () => {
    // 优先用外层舞台容器：本组件只是舞台里的一个子层，
    // 只全屏本组件会把舞台里的占位/提示排除在画面外。
    const target = fullscreenTargetRef.current?.current ?? containerRef.current
    if (!target) return
    const doc = document as Document & WebkitFullscreenDoc
    const video = videoRef.current as WebkitFullscreenVideo | null
    // iPhone Safari 没有 Element.requestFullscreen：视频只能进原生播放器全屏
    // （video.webkitEnterFullscreen）。原先这里直接调 target.requestFullscreen()，
    // 在那种环境里是一句 TypeError —— 双击中间、按钮、F 键三条路都静默失效。
    const nativeVideoFullscreen = () => video?.webkitEnterFullscreen?.()
    if (document.fullscreenElement || doc.webkitFullscreenElement) {
      if (typeof document.exitFullscreen === 'function') {
        void document.exitFullscreen().catch(() => {})
      } else if (typeof doc.webkitExitFullscreen === 'function') {
        void Promise.resolve(doc.webkitExitFullscreen()).catch(() => {})
      }
      return
    }
    if (typeof target.requestFullscreen === 'function') {
      void target.requestFullscreen().catch(nativeVideoFullscreen)
      return
    }
    const anyTarget = target as WebkitFullscreenTarget
    if (typeof anyTarget.webkitRequestFullscreen === 'function') {
      void Promise.resolve(anyTarget.webkitRequestFullscreen()).catch(nativeVideoFullscreen)
      return
    }
    nativeVideoFullscreen()
  }

  // ---- 键盘快捷键（绑一次，回调经 ref 读取，避免重绑监听） ----
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (isTypingTarget(e.target) || e.ctrlKey || e.altKey || e.metaKey) return
      const video = videoRef.current
      if (!video) return
      switch (e.key) {
        case ' ':
          // 焦点落在按钮/链接上时空格会触发原生 click，这里再 togglePlay 就等于放一下又暂停
          if (e.target instanceof HTMLElement && e.target.closest('button, a, select')) return
          e.preventDefault()
          togglePlay()
          break
        case 'k':
        case 'K':
          e.preventDefault()
          togglePlay()
          break
        case 'ArrowLeft':
          e.preventDefault()
          seekBy(-5)
          break
        case 'ArrowRight':
          e.preventDefault()
          seekBy(5)
          break
        case 'j':
        case 'J':
          seekBy(-10)
          break
        case 'l':
        case 'L':
          seekBy(10)
          break
        case 'ArrowUp':
          e.preventDefault()
          changeVolume(0.05)
          break
        case 'ArrowDown':
          e.preventDefault()
          changeVolume(-0.05)
          break
        case 'm':
        case 'M':
          setMuted((m) => !m)
          break
        case 'f':
        case 'F':
          toggleFullscreen()
          break
        default:
          break
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  // ---- 手势：单击显隐控制条、左右滑进度、左半屏亮度、右半屏音量 ----
  // 用 Pointer Events 一处覆盖鼠标+触摸；拖动与单击按位移阈值区分，
  // 否则「划一下进度」会紧接着触发一次暂停。
  const gestureRef = useRef({
    active: false,
    x0: 0,
    y0: 0,
    dx: 0,
    dy: 0,
    mode: 'none' as 'none' | 'seek' | 'brightness' | 'volume',
    moved: false,
    lastTap: 0,
    seekBase: 0,
    // 音量/亮度的**起始快照**：move 里必须从快照值加累计位移算，
    // 不能从「上一帧更新后的值」减「从起点累计的位移」——那是双重重算，
    // 音量会以两倍速跳、亮度会沿二次曲线爆掉（快照和 seekBase 一个道理）
    startVolume: 1,
    startBrightness: 1,
  })

  const applyBrightness = (level: number) => {
    const clamped = Math.round(Math.min(1.5, Math.max(0.3, level)) * 100) / 100
    brightnessRef.current = clamped
    const video = videoRef.current
    if (video) video.style.filter = clamped === 1 ? '' : `brightness(${clamped})`
  }

  const onPointerDown = (e: ReactPointerEvent<HTMLVideoElement>) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return
    // 新手势开始 = 上一次单击还没到执行时刻的那件事作废：
    // 否则「点一下、立刻开始拖拽」会在拖动中途被延迟动作切掉控制条。
    if (tapTimerRef.current) {
      clearTimeout(tapTimerRef.current)
      tapTimerRef.current = undefined
    }
    // 摸到画面就收起倍速菜单：它浮在控制条上方，不收的话会一直挡着进度条
    if (rateOpen) setRateOpen(false)
    const g = gestureRef.current
    g.active = true
    g.x0 = e.clientX
    g.y0 = e.clientY
    g.dx = 0
    g.dy = 0
    g.mode = 'none'
    g.moved = false
    g.seekBase = videoRef.current?.currentTime ?? 0
    g.startVolume = muted ? 0 : volume
    g.startBrightness = brightnessRef.current
  }

  const onPointerMove = (e: ReactPointerEvent<HTMLVideoElement>) => {
    const g = gestureRef.current
    if (!g.active) return
    // 「按键已经全松开」是这里唯一可靠的收尾信号。
    // 鼠标**没有**隐式指针捕获：实测（本机 Chromium + puppeteer 合成事件）
    // 在画面上按下、拖到控制条上松开时，画面元素既收不到 pointerup 也收不到
    // pointercancel，于是 g.active 一直停在 true。
    // 后果不是手感问题：之后只要把鼠标**移过**画面（一个键都没按），
    // 下面的逻辑就会照着 seek 分支把 currentTime 跟着鼠标横着拖 ——
    // 用户在没有任何操作的情况下看到播放进度乱跳，并且控制条再也不出现。
    // （同理也不要用 setPointerCapture 来「保证收到 up」：实测加了捕获后
    //  这条序列拿到的是 pointercancel 而不是 pointerup，兜不住同一个洞。）
    if (e.buttons === 0) {
      const wasMoved = g.moved
      g.active = false
      if (wasMoved) {
        g.lastTap = 0
        // 拖动收尾仍要同步一次：否则进度条会弹回拖之前的位置
        const video = videoRef.current
        if (video) setCurrent(video.currentTime)
        revealControls()
      }
      return
    }
    g.dx = e.clientX - g.x0
    g.dy = e.clientY - g.y0
    if (g.mode === 'none') {
      if (Math.abs(g.dx) < 10 && Math.abs(g.dy) < 10) return
      g.moved = true
      // 先在轨迹更长的轴上定向，再按落点分左右半屏（竖向手势）
      if (Math.abs(g.dx) > Math.abs(g.dy)) {
        g.mode = 'seek'
      } else {
        const rect = e.currentTarget.getBoundingClientRect()
        g.mode = e.clientX - rect.left < rect.width / 2 ? 'brightness' : 'volume'
      }
    }
    if (g.mode === 'seek') {
      const target = Math.max(0, Math.min(duration || Infinity, g.seekBase + g.dx / 4))
      if (videoRef.current) videoRef.current.currentTime = target
      showToast({ kind: 'seek', text: `${formatTime(target)} / ${formatTime(duration)}`, level: duration ? target / duration : 0 })
    } else if (g.mode === 'brightness') {
      // 从起始快照算：上滑（dy<0）提亮，量程 300px 对应满幅
      const level = Math.round((g.startBrightness - g.dy / 300) * 100) / 100
      applyBrightness(level)
      showToast({ kind: 'brightness', text: `亮度 ${Math.round(level * 100)}%`, level: Math.min(1, level / 1.5) })
    } else {
      const next = Math.round((g.startVolume - g.dy / 300) * 100) / 100
      setVolume(Math.min(1, Math.max(0, next)))
      setMuted(next <= 0)
      showToast({ kind: 'volume', text: `音量 ${Math.round(next * 100)}%`, level: next })
    }
  }

  const onPointerUp = (_e: ReactPointerEvent<HTMLVideoElement>) => {
    const g = gestureRef.current
    if (!g.active) return
    g.active = false
    if (g.moved) {
      // 一次拖动之后不能把上一次单击还留着：否则「点一下 → 拖一下 → 马上再点」
      // 的第三下会被当成双击，直接进全屏
      g.lastTap = 0
      // 拖动收尾：把最终位置同步进 React 状态，进度条不会弹回去
      const video = videoRef.current
      if (video) setCurrent(video.currentTime)
      revealControls()
      return
    }
    // 单击 vs 双击：DOUBLE_TAP_MS 内的第二次点击算双击，动作是**全屏/退出全屏**。
    // 双击不再承担快进/快退（按用户要求删掉了）：画面任意位置双击都是全屏；
    // 单击动作（显隐控制条）延后到双击窗口结束才执行，双击时取消 ——
    // 否则一次双击会先切一次控制条再进全屏。
    const now = Date.now()
    if (now - g.lastTap < DOUBLE_TAP_MS) {
      g.lastTap = 0
      if (tapTimerRef.current) {
        clearTimeout(tapTimerRef.current)
        tapTimerRef.current = undefined
      }
      toggleFullscreen()
      return
    }
    g.lastTap = now
    if (tapTimerRef.current) clearTimeout(tapTimerRef.current)
    tapTimerRef.current = setTimeout(() => {
      tapTimerRef.current = undefined
      if (controlsVisibleRef.current) setControlsVisible(false)
      else revealControls()
    }, DOUBLE_TAP_MS)
  }

  // ---- 画中画 ----
  const togglePip = async () => {
    const video = videoRef.current
    if (!video || !document.pictureInPictureEnabled) return
    try {
      if (document.pictureInPictureElement) await document.exitPictureInPicture()
      else await video.requestPictureInPicture()
    } catch {
      // 用户手势过期或浏览器策略拒绝：PiP 是增强项，静默失败即可
    }
  }

  return (
    <div
      ref={containerRef}
      className={`video-shell${theater ? ' theater' : ''}${controlsVisible ? '' : ' hide-controls'}`}
      // 隐藏之后也必须能被鼠标移动唤出：之前写成 `controlsVisible && revealControls()`，
      // 一旦自动收起，移动鼠标就再也不会显示控制条，只能先点一下画面
      onPointerMove={() => revealControls()}
    >
      <video
        ref={videoRef}
        playsInline
        controls={false}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={() => {
          gestureRef.current.active = false
        }}
      />
      {buffering && !error && (
        <div className="video-buffering" aria-label="缓冲中">
          <span className="spinner" />
        </div>
      )}
      {gestureToast && (
        <div className={`gesture-toast ${gestureToast.kind}`} role="status">
          {gestureToast.kind !== 'seek' && (
            <span className="gesture-level">
              <span className="gesture-level-fill" style={{ height: `${Math.round(gestureToast.level * 100)}%` }} />
            </span>
          )}
          <span className="gesture-text">
            {gestureToast.kind === 'brightness' && <IconBrightness size={14} />}
            {gestureToast.kind === 'volume' && <IconVolume size={14} />}
            {gestureToast.text}
          </span>
        </div>
      )}
      {autoplayBlocked && !error && (
        <button type="button" className="video-bigplay" onClick={togglePlay}>
          <IconPlay size={44} />
          <span>浏览器不允许自动播放，点这里开始</span>
        </button>
      )}
      {error && <div className="video-error">{error}</div>}
      <div className="player-controls">
        <div className="seek-track">
          <div
            className="seek-buffered"
            style={{ width: `${duration ? Math.min(100, (bufferedEnd / duration) * 100) : 0}%` }}
          />
          <div
            className="seek-played"
            style={{ width: `${duration ? Math.min(100, (current / duration) * 100) : 0}%` }}
          />
          <input
            className="seek-input"
            type="range"
            min={0}
            max={duration || 0}
            step={0.1}
            value={current}
            onChange={(e) => seek(Number(e.target.value))}
            aria-label="播放进度"
          />
        </div>
        <div className="controls-row">
          <button
            className="player-btn primary"
            onClick={togglePlay}
            aria-label="播放/暂停"
            title="播放/暂停（空格）"
          >
            {playing ? <IconPause size={16} /> : <IconPlay size={16} />}
          </button>
          {onPrevEpisode && (
            <button className="player-btn" onClick={onPrevEpisode} aria-label="上一集" title="上一集">
              <IconPrev size={16} />
            </button>
          )}
          {onNextEpisode && (
            <button className="player-btn" onClick={onNextEpisode} aria-label="下一集" title="下一集">
              <IconNext size={16} />
            </button>
          )}
          <span className="time">{formatTime(current)}</span>
          <span className="time-sep">/</span>
          <span className="time total">{formatTime(duration)}</span>
          <span className="controls-spacer" />
          <div className="volume-group">
            <button
              className="player-btn"
              onClick={() => setMuted((m) => !m)}
              aria-label="静音"
              title="静音（M）"
            >
              {muted || volume === 0 ? <IconMute size={16} /> : <IconVolume size={16} />}
            </button>
            <input
              className="volume-input"
              type="range"
              min={0}
              max={1}
              step={0.05}
              value={muted ? 0 : volume}
              onChange={(e) => {
                setVolume(Number(e.target.value))
                setMuted(false)
              }}
              aria-label="音量"
            />
          </div>
          <div className="rate-group">
            <button
              className="player-btn"
              onClick={() => setRateOpen((v) => !v)}
              aria-label="倍速"
              title="播放倍速"
              aria-expanded={rateOpen}
            >
              <IconGauge size={16} />
            </button>
            {rateOpen && (
              <div className="rate-menu" role="menu">
                {RATES.map((r) => (
                  <button
                    key={r}
                    className={`rate-item${r === rate ? ' active' : ''}`}
                    onClick={() => {
                      setRate(r)
                      setRateOpen(false)
                    }}
                    role="menuitemradio"
                    aria-checked={r === rate}
                  >
                    {r === 1 ? '1.0x 标准' : `${r}x`}
                  </button>
                ))}
              </div>
            )}
          </div>
          {typeof document !== 'undefined' && document.pictureInPictureEnabled && (
            <button className="player-btn" onClick={() => void togglePip()} aria-label="画中画" title="画中画">
              <IconPip size={16} />
            </button>
          )}
          <button
            className="player-btn"
            onClick={toggleFullscreen}
            aria-label="全屏"
            title="全屏（F）"
          >
            {fullscreen ? <IconFullscreenExit size={16} /> : <IconFullscreen size={16} />}
          </button>
        </div>
      </div>
    </div>
  )
}

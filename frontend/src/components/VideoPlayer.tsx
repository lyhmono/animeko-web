import { useEffect, useRef, useState, type RefObject } from 'react'
import Hls from 'hls.js'
import { isDirectPlayable } from '../api'
import {
  IconDanmaku,
  IconFullscreen,
  IconFullscreenExit,
  IconMute,
  IconNext,
  IconPause,
  IconPlay,
  IconPrev,
  IconVolume,
} from '../lib/icons'

interface Props {
  /** 播放地址（已通过服务端代理） */
  src: string
  /** 加载后一次性定位到的秒数（断点续播） */
  startAt?: number
  danmakuOn?: boolean
  /**
   * 全屏目标容器。默认只全屏本组件的 div —— 而弹幕图层是本组件的兄弟节点，
   * 于是按 F 之后弹幕直接消失。播放页需要把包含弹幕的外层容器传进来。
   */
  fullscreenTarget?: RefObject<HTMLElement | null>
  /** 真实播放/暂停状态：弹幕动画必须跟着它走，否则暂停后弹幕仍在飞 */
  onPlayingChange?: (playing: boolean) => void
  onToggleDanmaku?: () => void
  onPrevEpisode?: () => void
  onNextEpisode?: () => void
  onProgress?: (time: number, duration: number) => void
  onEnded?: () => void
  onError?: (message: string) => void
}

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

export default function VideoPlayer({
  src,
  startAt,
  danmakuOn = true,
  fullscreenTarget,
  onPlayingChange,
  onToggleDanmaku,
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
  const [volume, setVolume] = useState(1)
  const [muted, setMuted] = useState(false)
  const [rate, setRate] = useState(1)
  const [error, setError] = useState<string | null>(null)
  const [fullscreen, setFullscreen] = useState(false)

  // 用 ref 持有最新回调：父组件每次渲染都会传入新的回调引用，
  // 若把回调列入 effect 依赖，timeupdate → 父组件重渲染 → 回调引用变化 →
  // effect 清理时 removeAttribute('src')+load() 会重置视频，播放永远无法持续
  const onErrorRef = useRef(onError)
  const onProgressRef = useRef(onProgress)
  const onEndedRef = useRef(onEnded)
  const onToggleDanmakuRef = useRef(onToggleDanmaku)
  const onPlayingChangeRef = useRef(onPlayingChange)
  const fullscreenTargetRef = useRef(fullscreenTarget)
  useEffect(() => {
    onErrorRef.current = onError
    onProgressRef.current = onProgress
    onEndedRef.current = onEnded
    onToggleDanmakuRef.current = onToggleDanmaku
    onPlayingChangeRef.current = onPlayingChange
    fullscreenTargetRef.current = fullscreenTarget
  })

  // 加载源
  const startAtAppliedRef = useRef(false)
  useEffect(() => {
    const video = videoRef.current
    if (!video || !src) return
    let hls: Hls | null = null
    setError(null)
    // 换源（切集）后要重新允许定位一次，否则第二集的断点续播永远不生效
    startAtAppliedRef.current = false

    if (isDirectPlayable(src)) {
      video.src = src
    } else if (Hls.isSupported()) {
      hls = new Hls({ enableWorker: true, fragLoadingTimeOut: 30000 })
      hls.loadSource(src)
      hls.attachMedia(video)
      hls.on(Hls.Events.ERROR, (_event, data) => {
        if (data.fatal) {
          const msg =
            data.details === Hls.ErrorDetails.MANIFEST_LOAD_ERROR
              ? '视频清单加载失败（源站可能拒绝服务端代理，可尝试手动地址播放）'
              : `播放错误: ${data.details}`
          setError(msg)
          onErrorRef.current?.(msg)
        }
      })
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = src
    } else {
      const msg = '当前浏览器不支持 HLS 播放'
      setError(msg)
      onErrorRef.current?.(msg)
    }

    return () => {
      hls?.destroy()
      video.removeAttribute('src')
      video.load()
    }
  }, [src])

  // 断点续播：加载后定位一次
  useEffect(() => {
    const video = videoRef.current
    if (!video || !startAt || startAtAppliedRef.current) return
    const apply = () => {
      if (startAtAppliedRef.current) return
      try {
        video.currentTime = startAt
        startAtAppliedRef.current = true
      } catch {
        // 元数据未就绪时忽略，等待下一次 loadedmetadata
      }
    }
    if (video.readyState >= 1) apply()
    video.addEventListener('loadedmetadata', apply)
    return () => video.removeEventListener('loadedmetadata', apply)
  }, [startAt, src])

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
      onPlayingChangeRef.current?.(true)
    }
    const onPause = () => {
      setPlaying(false)
      onPlayingChangeRef.current?.(false)
    }
    const onEnd = () => {
      setPlaying(false)
      onPlayingChangeRef.current?.(false)
      onEndedRef.current?.()
    }
    // 直链（mp4/webm）路径没有 hls.js 的 ERROR 事件可订阅，
    // 代理 403/404 时以前什么都不会发生：画面黑屏、也没有错误提示。
    const onMediaError = () => {
      // 没有 error 对象的 error 事件是切源/卸载清理时补发的，别当成播放失败
      if (!video.error) return
      const code = video.error.code
      const msg = `视频加载失败（MEDIA_ERROR_${code}），源站可能拒绝了代理请求，可尝试下方手动地址播放`
      setError(msg)
      onErrorRef.current?.(msg)
    }
    const onFs = () => setFullscreen(document.fullscreenElement != null)
    video.addEventListener('timeupdate', onTime)
    video.addEventListener('loadedmetadata', onMeta)
    video.addEventListener('durationchange', onMeta)
    video.addEventListener('play', onPlay)
    video.addEventListener('pause', onPause)
    video.addEventListener('ended', onEnd)
    video.addEventListener('error', onMediaError)
    document.addEventListener('fullscreenchange', onFs)
    return () => {
      video.removeEventListener('timeupdate', onTime)
      video.removeEventListener('loadedmetadata', onMeta)
      video.removeEventListener('durationchange', onMeta)
      video.removeEventListener('play', onPlay)
      video.removeEventListener('pause', onPause)
      video.removeEventListener('ended', onEnd)
      video.removeEventListener('error', onMediaError)
      document.removeEventListener('fullscreenchange', onFs)
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
    // 优先用外层舞台容器：弹幕图层是本组件的兄弟节点，
    // 只全屏本组件的话按 F 弹幕会整体消失。
    const target = fullscreenTargetRef.current?.current ?? containerRef.current
    if (!target) return
    if (document.fullscreenElement) void document.exitFullscreen()
    else void target.requestFullscreen().catch(() => {})
  }

  // ---- 键盘快捷键（绑一次，弹幕开关回调经 ref 读取） ----
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
        case 'd':
        case 'D':
          onToggleDanmakuRef.current?.()
          break
        default:
          break
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  return (
    <div ref={containerRef} className="video-shell">
      <video ref={videoRef} playsInline controls={false} onClick={togglePlay} />
      {error && (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: 'rgba(0,0,0,0.75)',
            color: 'var(--danger)',
            padding: 24,
            textAlign: 'center',
            fontSize: 14,
          }}
        >
          {error}
        </div>
      )}
      <div className="player-controls">
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
        <input
          className="seek"
          type="range"
          min={0}
          max={duration || 0}
          step={0.1}
          value={current}
          onChange={(e) => seek(Number(e.target.value))}
          aria-label="播放进度"
        />
        <span className="time">{formatTime(duration)}</span>
        <button
          className="player-btn"
          onClick={() => setMuted((m) => !m)}
          aria-label="静音"
          title="静音（M）"
        >
          {muted || volume === 0 ? <IconMute size={16} /> : <IconVolume size={16} />}
        </button>
        <input
          type="range"
          min={0}
          max={1}
          step={0.05}
          value={muted ? 0 : volume}
          onChange={(e) => {
            setVolume(Number(e.target.value))
            setMuted(false)
          }}
          style={{ width: 72 }}
          aria-label="音量"
        />
        <select value={rate} onChange={(e) => setRate(Number(e.target.value))} aria-label="倍速">
          <option value={0.5}>0.5x</option>
          <option value={0.75}>0.75x</option>
          <option value={1}>1.0x</option>
          <option value={1.25}>1.25x</option>
          <option value={1.5}>1.5x</option>
          <option value={2}>2.0x</option>
        </select>
        <button
          className={`player-btn ${danmakuOn ? 'active' : ''}`}
          onClick={() => onToggleDanmaku?.()}
          aria-label="弹幕开关"
          title="弹幕开关（D）"
        >
          <IconDanmaku size={16} />
        </button>
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
  )
}

import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, errMsg, UNAUTHORIZED_EVENT } from '../api'
import VideoPlayer from '../components/VideoPlayer'
import { addRecent, clearProgress, getProgress, setProgress } from '../lib/storage'
import { IconCheck, IconClock, IconKeyboard, IconNext } from '../lib/icons'

/** 下一集信息（连播用），通过 URL 参数传递 */
interface NextEpisode {
  source: string
  play: string
  title: string
  anime: string
  ep: string
}

function parseNext(raw: string | null): NextEpisode | null {
  if (!raw) return null
  try {
    const obj = JSON.parse(raw)
    if (obj && typeof obj.play === 'string' && obj.play.startsWith('http')) return obj as NextEpisode
  } catch {
    /* ignore */
  }
  return null
}

function nextToParams(next: NextEpisode): URLSearchParams {
  return new URLSearchParams({
    source: next.source,
    play: next.play,
    title: next.title,
    anime: next.anime,
    ep: next.ep,
  })
}

function fmtTime(seconds: number): string {
  if (!isFinite(seconds) || seconds < 0) return '00:00'
  const m = Math.floor(seconds / 60)
  const s = Math.floor(seconds % 60)
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

export default function PlayerPage() {
  const [params, setSearchParams] = useSearchParams()
  const source = params.get('source') ?? ''
  const playUrl = params.get('play') ?? ''
  const title = params.get('title') ?? ''
  const animeName = params.get('anime') ?? ''
  const ep = params.get('ep') ?? ''
  const nextInfo = useMemo(() => parseNext(params.get('next')), [params])

  const [src, setSrc] = useState('')
  const [startAt, setStartAt] = useState(0)
  const [resumeTime, setResumeTime] = useState<number | null>(null)
  const [resolving, setResolving] = useState(false)
  const [error, setError] = useState('')
  const [manualUrl, setManualUrl] = useState('')
  const [nextCountdown, setNextCountdown] = useState<number | null>(null)

  const lastWriteRef = useRef<number | null>(null)
  const stageRef = useRef<HTMLDivElement | null>(null)
  const resolveSeqRef = useRef(0)
  // 播放会话过期后自动重新解析一次（长暂停会让剩余分片全部失效），再失败才报错
  const [resolveNonce, setResolveNonce] = useState(0)
  const retriedKeyRef = useRef<string | null>(null)
  const resetForRef = useRef<string | null>(null)

  // 解析视频地址 + 写入最近观看
  useEffect(() => {
    if (!source || !playUrl) return
    setError('')
    lastWriteRef.current = null
    const playKey = `${source}|${playUrl}`
    // 只在换剧集时清空重试额度：本 effect 也依赖 resolveNonce，无条件清空
    // 会让每次自动重解析都重置额度，退化成无界重试
    if (resetForRef.current !== playKey) {
      resetForRef.current = playKey
      retriedKeyRef.current = null
    }
    // source=manual：playUrl 即媒体直链，跳过数据源解析直接经 /api/proxy 播放
    if (source === 'manual') {
      resolveSeqRef.current++
      setSrc(`/api/proxy?${new URLSearchParams({ url: playUrl }).toString()}`)
      addRecent({ source, playUrl, title: title || playUrl, anime: animeName, ep, ts: Date.now() })
      return
    }
    // 序号守卫：解析可能耗时几十秒，期间换集或改用手播时过期响应必须丢弃，
    // 否则它会覆盖新的 src、把「最近观看」记到错误的条目上
    const seq = ++resolveSeqRef.current
    setResolving(true)
    api
      .resolveVideo(source, playUrl)
      .then((video) => {
        if (seq !== resolveSeqRef.current) return
        setSrc(video.proxyPath)
        addRecent({
          source,
          playUrl,
          title: title || playUrl,
          anime: animeName,
          ep,
          ts: Date.now(),
        })
      })
      .catch((e) => {
        if (seq !== resolveSeqRef.current) return
        setError(`视频地址解析失败：${errMsg(e)}（可尝试下方手动地址播放）`)
      })
      .finally(() => {
        if (seq === resolveSeqRef.current) setResolving(false)
      })
  }, [source, playUrl, title, animeName, ep, resolveNonce])

  // 断点续播：读取历史进度
  useEffect(() => {
    if (!source || !playUrl) return
    const p = getProgress(source, playUrl)
    setResumeTime(p ? p.time : null)
  }, [source, playUrl])

  // 连播倒计时
  useEffect(() => {
    if (nextCountdown == null) return
    if (nextCountdown <= 0) {
      if (nextInfo) window.location.assign(`/play?${nextToParams(nextInfo).toString()}`)
      return
    }
    const timer = window.setTimeout(() => setNextCountdown((c) => (c ?? 1) - 1), 1000)
    return () => window.clearTimeout(timer)
  }, [nextCountdown, nextInfo])

  // 播放进度节流写入
  const handleProgress = (time: number, duration: number) => {
    if (!source || !playUrl) return
    const last = lastWriteRef.current
    if (last == null || Math.abs(time - last) >= 4) {
      lastWriteRef.current = time
      setProgress({ source, playUrl, title, time, duration, ts: Date.now() })
    }
  }

  // 播放结束：清除进度，有下一集则启动连播倒计时
  const handleEnded = () => {
    if (source && playUrl) clearProgress(source, playUrl)
    if (nextInfo) setNextCountdown(5)
  }

  const resumePlay = () => {
    if (resumeTime != null) setStartAt(Math.floor(resumeTime))
    setResumeTime(null)
  }

  const restartPlay = () => {
    if (source && playUrl) clearProgress(source, playUrl)
    setStartAt(0)
    setResumeTime(null)
  }

  const gotoNext = () => {
    if (!nextInfo) return
    window.location.assign(`/play?${nextToParams(nextInfo).toString()}`)
  }

  const playManualUrl = () => {
    const url = manualUrl.trim()
    if (!url) return
    setSrc(`/api/proxy?${new URLSearchParams({ url }).toString()}`)
    setError('')
    addRecent({
      source: 'manual',
      playUrl: url,
      title: title || url,
      anime: animeName,
      ep,
      ts: Date.now(),
    })
    // 同步地址栏为 manual 直链模式（replace 不新增历史记录）：刷新/分享后可直接重放，
    // 播放进度的 key 也与此一致
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev)
        next.set('source', 'manual')
        next.set('play', url)
        return next
      },
      { replace: true },
    )
  }

  if (!source && !playUrl) {
    return (
      <div className="empty">
        <span className="empty-emoji">🎬</span>
        缺少播放参数。请从 <Link to="/">首页</Link> 搜索进入。
      </div>
    )
  }

  return (
    <div className="player-page fade-in">
      <div className="player-stage" ref={stageRef}>
        {src && (
          <VideoPlayer
            src={src}
            startAt={startAt}
            fullscreenTarget={stageRef}
            onNextEpisode={nextInfo ? gotoNext : undefined}
            onProgress={handleProgress}
            onEnded={handleEnded}
            onError={(msg, status) => {
              const key = `${source}|${playUrl}`
              // 登录会话没了：分片会一直 401。这里必须把界面切回登录页 ——
              // 播放器只会报「视频加载失败」，用户没有任何线索知道是会话过期了
              if (status === 401) {
                window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
                setError('登录已过期，请重新登录')
                return
              }
              // 410=播放会话过期、403=sid 与目标主机不匹配：重新解析即可拿到新会话
              if ((status === 410 || status === 403) && retriedKeyRef.current !== key) {
                retriedKeyRef.current = key
                setError('播放会话已过期，正在重新解析地址…')
                setResolveNonce((n) => n + 1)
                return
              }
              setError(msg)
            }}
          />
        )}
        {!src && <div className="stage-placeholder">{resolving ? '正在解析视频地址…' : '等待视频地址'}</div>}
      </div>

      {resumeTime != null && (
        <div className="resume-bar fade-in">
          <IconClock size={15} />
          <span className="resume-text">
            上次播放到 <b>{fmtTime(resumeTime)}</b>，是否继续？
          </span>
          <button className="btn btn-sm" onClick={resumePlay}>
            <IconCheck size={14} />
            继续播放
          </button>
          <button className="btn btn-secondary btn-sm" onClick={restartPlay}>
            从头开始
          </button>
        </div>
      )}

      {nextCountdown != null && (
        <div className="next-bar fade-in">
          <IconNext size={15} />
          <span className="next-text">
            即将播放下一集：<b>{nextInfo?.title ?? ''}</b>
          </span>
          <span className="next-count">{nextCountdown}s</span>
          <button className="btn btn-sm" onClick={gotoNext}>
            立即播放
          </button>
          <button className="btn btn-secondary btn-sm" onClick={() => setNextCountdown(null)}>
            取消
          </button>
        </div>
      )}

      {error && <div className="error-box">{error}</div>}

      <div className="player-info">
        <h2>{title}</h2>
        <div className="path">播放页：{playUrl || '（手动地址模式）'}</div>
      </div>

      <div className="player-side">
        <div className="card">
          <b className="card-title">手动地址播放</b>
          <p className="card-hint">
            数据源解析失败、或需要执行 JS 的源，可直接粘贴视频地址（m3u8 / mp4）播放
          </p>
          <div className="manual-url">
            <input
              value={manualUrl}
              placeholder="https://example.com/video/index.m3u8"
              aria-label="手动视频地址"
              onChange={(e) => setManualUrl(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') playManualUrl()
              }}
            />
            <button className="btn" onClick={playManualUrl}>
              播放
            </button>
          </div>
        </div>

        <div className="card">
          <b className="card-title">
            <IconKeyboard size={16} />
            播放快捷键
          </b>
          <div className="shortcuts-grid">
            <div className="row">
              <span className="keys">
                <span className="kbd">空格</span>
              </span>
              播放 / 暂停
            </div>
            <div className="row">
              <span className="keys">
                <span className="kbd">←</span>
                <span className="kbd">→</span>
              </span>
              快退 / 快进 5 秒
            </div>
            <div className="row">
              <span className="keys">
                <span className="kbd">J</span>
                <span className="kbd">L</span>
              </span>
              快退 / 快进 10 秒
            </div>
            <div className="row">
              <span className="keys">
                <span className="kbd">↑</span>
                <span className="kbd">↓</span>
              </span>
              音量增减
            </div>
            <div className="row">
              <span className="keys">
                <span className="kbd">M</span>
              </span>
              静音
            </div>
            <div className="row">
              <span className="keys">
                <span className="kbd">F</span>
              </span>
              全屏
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, errMsg } from '../api'
import type { DdComment, DanmakuOptions } from '../types'
import VideoPlayer from '../components/VideoPlayer'
import DanmakuCanvas from '../components/DanmakuCanvas'
import {
  addRecent,
  clearProgress,
  getProgress,
  readJson,
  setProgress,
  writeJson,
} from '../lib/storage'
import { IconCheck, IconClock, IconDanmaku, IconKeyboard, IconNext } from '../lib/icons'

// storage.ts 会自动加 animeko-web: 前缀，最终键名与旧版一致，历史偏好不丢
const DANMAKU_PREFS_KEY = 'danmaku-prefs'

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

const defaultPrefs: DanmakuOptions = {
  enabled: true,
  fontSize: 22,
  opacity: 0.85,
  speed: 1,
  density: 1,
  blockWords: '',
}

/**
 * 数值偏好一律限幅：清空数字输入框会得到 ''/NaN，
 * 原样写进 prefs 会让弹幕字号归零或 density<=0（画布直接不画），
 * 而且这些脏值会被持久化，刷新后依旧复现。
 */
function clampPref(value: unknown, min: number, max: number, fallback: number): number {
  const n = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(n)) return fallback
  return Math.min(max, Math.max(min, n))
}

function normalizePrefs(raw: unknown): DanmakuOptions {
  const o = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>
  return {
    enabled: typeof o.enabled === 'boolean' ? o.enabled : defaultPrefs.enabled,
    fontSize: clampPref(o.fontSize, 12, 48, defaultPrefs.fontSize),
    opacity: clampPref(o.opacity, 0.1, 1, defaultPrefs.opacity),
    speed: clampPref(o.speed, 0.3, 3, defaultPrefs.speed),
    density: clampPref(o.density, 0.1, 3, defaultPrefs.density),
    blockWords: typeof o.blockWords === 'string' ? o.blockWords : defaultPrefs.blockWords,
  }
}

function loadPrefs(): DanmakuOptions {
  return normalizePrefs(readJson<unknown>(DANMAKU_PREFS_KEY, null))
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
  const [currentTime, setCurrentTime] = useState(0)
  const [comments, setComments] = useState<DdComment[]>([])
  const [danmakuInfo, setDanmakuInfo] = useState('')
  const [manualUrl, setManualUrl] = useState('')
  const [prefs, setPrefs] = useState<DanmakuOptions>(loadPrefs)
  const [showDanmakuPanel, setShowDanmakuPanel] = useState(false)
  const [nextCountdown, setNextCountdown] = useState<number | null>(null)
  const [videoPlaying, setVideoPlaying] = useState(false)

  const lastWriteRef = useRef<number | null>(null)
  const stageRef = useRef<HTMLDivElement | null>(null)
  const resolveSeqRef = useRef(0)

  useEffect(() => {
    writeJson(DANMAKU_PREFS_KEY, prefs)
  }, [prefs])

  // 解析视频地址 + 写入最近观看
  useEffect(() => {
    if (!source || !playUrl) return
    setError('')
    lastWriteRef.current = null
    // source=manual：playUrl 即媒体直链，跳过数据源解析直接经 /api/proxy 播放。
    // 手动地址播放写入的最近观看条目以此为 source，点击卡片可立即重放。
    // 注意：manual 是保留源名，若用户把数据源命名为 manual 会走错分支（概率极低，
    // 且失败时仍有错误态与手动播放入口兜底）
    if (source === 'manual') {
      resolveSeqRef.current++
      setSrc(`/api/proxy?${new URLSearchParams({ url: playUrl }).toString()}`)
      addRecent({ source, playUrl, title: title || playUrl, anime: animeName, ep, ts: Date.now() })
      return
    }
    // 序号守卫：解析可能耗时几十秒，期间换集或改用手播时过期响应必须丢弃，
    // 否则它会覆盖新的 src、把「最近观看」记到错误的条目上，并提前熄灭 loading 态
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
  }, [source, playUrl, title, animeName, ep])

  // 断点续播：读取历史进度
  useEffect(() => {
    if (!source || !playUrl) return
    const p = getProgress(source, playUrl)
    setResumeTime(p ? p.time : null)
  }, [source, playUrl])

  // 匹配并加载弹幕
  useEffect(() => {
    if (!animeName) return
    let cancelled = false
    setDanmakuInfo('弹幕匹配中…')
    setComments([])
    api
      .danmakuSearch(animeName, ep || undefined)
      .then(async (animes) => {
        if (cancelled) return
        if (animes.length === 0) {
          setDanmakuInfo('未找到匹配的弹幕')
          return
        }
        const anime = animes[0]
        // ep 往往是「1」「01」「第1话」这类文本。原先直接 episodeTitle.includes(ep)：
        // ep='1' 会命中「第11话」，ep='' 则无条件命中第一集 —— 两种都是静默配错弹幕。
        const want = parseInt(ep.replace(/\D/g, ''), 10)
        const episode =
          (Number.isFinite(want)
            ? anime.episodes.find((e) => {
                const n = parseInt(e.episodeTitle.replace(/\D/g, ''), 10)
                return Number.isFinite(n) && n === want
              })
            : undefined) ?? anime.episodes[0]
        if (!episode) {
          setDanmakuInfo('未找到匹配剧集的弹幕')
          return
        }
        const list = await api.danmakuComments(episode.episodeId)
        if (cancelled) return
        setComments(list)
        setDanmakuInfo(`《${anime.animeTitle}》${episode.episodeTitle} · ${list.length} 条弹幕`)
      })
      .catch((e) => {
        if (cancelled) return
        const msg = errMsg(e)
        // 未配置弹幕是部署选择而非故障（README 承诺「仅无弹幕，不影响播放」），
        // 不该以「加载失败」的红色文案吓用户
        setDanmakuInfo(
          msg.includes('DANMAKU_NOT_CONFIGURED')
            ? '未配置弹幕服务（服务端缺 appId/appSecret），不影响播放'
            : `弹幕加载失败：${msg}`,
        )
      })
    return () => {
      cancelled = true
    }
  }, [animeName, ep])

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
    setCurrentTime(time)
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

  const toggleDanmaku = () => setPrefs((p) => ({ ...p, enabled: !p.enabled }))

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

  if (!source && !playUrl) {
    return (
      <div className="empty">
        <span className="empty-emoji">🎬</span>
        缺少播放参数。请从 <Link to="/" style={{ color: 'var(--accent)' }}>首页</Link> 搜索进入。
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
            danmakuOn={prefs.enabled}
            fullscreenTarget={stageRef}
            onPlayingChange={setVideoPlaying}
            onToggleDanmaku={toggleDanmaku}
            onNextEpisode={nextInfo ? gotoNext : undefined}
            onProgress={handleProgress}
            onEnded={handleEnded}
            onError={(msg) => setError(msg)}
          />
        )}
        {!src && (
          <div
            style={{
              position: 'absolute',
              inset: 0,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: 'var(--text-dim)',
            }}
          >
            {resolving ? '正在解析视频地址…' : '等待视频地址'}
          </div>
        )}
        <DanmakuCanvas
          comments={comments}
          currentTime={currentTime}
          playing={videoPlaying}
          options={prefs}
        />
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
        {danmakuInfo && (
          <div className="path" style={{ marginTop: 4 }}>
            弹幕：{danmakuInfo}
          </div>
        )}
      </div>

      <div className="player-side">
        <div className="card">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <b style={{ fontSize: 14, display: 'inline-flex', alignItems: 'center', gap: 7 }}>
              <IconDanmaku size={16} />
              弹幕设置
            </b>
            <button className="btn btn-secondary btn-sm" onClick={() => setShowDanmakuPanel((v) => !v)}>
              {showDanmakuPanel ? '收起' : '展开'}
            </button>
          </div>
          {showDanmakuPanel && (
            <div className="danmaku-settings" style={{ marginTop: 12 }}>
              <label>
                <input
                  type="checkbox"
                  checked={prefs.enabled}
                  onChange={(e) => setPrefs((p) => ({ ...p, enabled: e.target.checked }))}
                />
                显示弹幕
              </label>
              <label>
                字号
                <input
                  type="number"
                  min={12}
                  max={48}
                  value={prefs.fontSize}
                  onChange={(e) => setPrefs((p) => ({ ...p, fontSize: clampPref(e.target.value, 12, 48, defaultPrefs.fontSize) }))}
                />
              </label>
              <label>
                透明度
                <input
                  type="number"
                  min={0.1}
                  max={1}
                  step={0.05}
                  value={prefs.opacity}
                  onChange={(e) => setPrefs((p) => ({ ...p, opacity: clampPref(e.target.value, 0.1, 1, defaultPrefs.opacity) }))}
                />
              </label>
              <label>
                速度
                <input
                  type="number"
                  min={0.3}
                  max={3}
                  step={0.1}
                  value={prefs.speed}
                  onChange={(e) => setPrefs((p) => ({ ...p, speed: clampPref(e.target.value, 0.3, 3, defaultPrefs.speed) }))}
                />
              </label>
              <label>
                密度
                <input
                  type="number"
                  min={0.1}
                  max={3}
                  step={0.1}
                  value={prefs.density}
                  onChange={(e) => setPrefs((p) => ({ ...p, density: clampPref(e.target.value, 0.1, 3, defaultPrefs.density) }))}
                />
              </label>
              <label style={{ flex: 1, minWidth: 200 }}>
                屏蔽词（逗号分隔）
                <input
                  style={{ flex: 1 }}
                  value={prefs.blockWords}
                  placeholder="如：剧透,前方高能"
                  onChange={(e) => setPrefs((p) => ({ ...p, blockWords: e.target.value }))}
                />
              </label>
            </div>
          )}
        </div>

        <div className="card">
          <b style={{ fontSize: 14, display: 'inline-flex', alignItems: 'center', gap: 7 }}>
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
            <div className="row">
              <span className="keys">
                <span className="kbd">D</span>
              </span>
              弹幕开关
            </div>
          </div>
        </div>

        <div className="card">
          <b style={{ fontSize: 14 }}>手动地址播放</b>
          <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 4 }}>
            数据源解析失败或需要 JS 执行的源，可粘贴视频地址（m3u8/mp4）直接播放
          </div>
          <div className="manual-url">
            <input
              value={manualUrl}
              placeholder="https://example.com/video/index.m3u8"
              onChange={(e) => setManualUrl(e.target.value)}
            />
            <button
              className="btn"
              onClick={() => {
                const url = manualUrl.trim()
                if (!url) return
                setSrc(`/api/proxy?${new URLSearchParams({ url }).toString()}`)
                setError('')
                // 手动播放同样写入最近观看：以 manual 源 + 媒体直链为标识，
                // 与数据源解析成功路径保持一致，点击最近观看卡片可经
                // /play?source=manual 直接重放（见上方解析 effect 的手动分支）
                addRecent({
                  source: 'manual',
                  playUrl: url,
                  title: title || url,
                  anime: animeName,
                  ep,
                  ts: Date.now(),
                })
                // 同步地址栏为 manual 直链模式（replace 不新增历史记录）：
                // 刷新/分享后经 /play?source=manual 直接重放，播放进度 key 也与此一致
                setSearchParams(
                  (prev) => {
                    const next = new URLSearchParams(prev)
                    next.set('source', 'manual')
                    next.set('play', url)
                    return next
                  },
                  { replace: true },
                )
              }}
            >
              播放
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

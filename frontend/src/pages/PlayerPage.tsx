import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, errMsg, UNAUTHORIZED_EVENT } from '../api'
import VideoPlayer from '../components/VideoPlayer'
import {
  addRecent,
  clearProgress,
  getProgress,
  setProgress,
  setSubjectProgress,
} from '../lib/storage'
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

function nextToParams(next: NextEpisode, subjectId = 0, subjectUrl = ''): URLSearchParams {
  const p = new URLSearchParams({
    source: next.source,
    play: next.play,
    title: next.title,
    anime: next.anime,
    ep: next.ep,
  })
  // 连播要把条目 id 一起带过去。原先 next 里只有五个字段（没有 subject），
  // 于是自动切到下一话后 subjectId=0，那一句 `if (subjectId > 0) setSubjectProgress(...)`
  // 再也不执行 —— 条目页的「看到第几话」永久停在上一话，而且没有任何报错。
  if (subjectId > 0) p.set('subject', String(subjectId))
  // 数据源详情页地址也要带过去：它是连播链条「手里没有下一集时」唯一的续命凭据
  if (subjectUrl) p.set('surl', subjectUrl)
  return p
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
  /** 从条目页进来时带的 Bangumi 条目 id：用来回写「看到第几话」 */
  const subjectId = Number(params.get('subject') || '') || 0
  /** 数据源详情页地址：连播链条重新取剧集列表时用（见 resolveChain） */
  const subjectUrl = params.get('surl') ?? ''
  const nextInfo = useMemo(() => parseNext(params.get('next')), [params])

  const [src, setSrc] = useState('')
  const [startAt, setStartAt] = useState(0)
  const [resumeTime, setResumeTime] = useState<number | null>(null)
  const [resolving, setResolving] = useState(false)
  const [error, setError] = useState('')
  const [manualUrl, setManualUrl] = useState('')
  const [nextCountdown, setNextCountdown] = useState<number | null>(null)
  /**
   * 连播链条在「URL 里没有下一集」时现场解析出来的那一集。
   *
   * 为什么必须有这一格：条目页只把**紧邻的下一集**塞进 URL 的 next，
   * 而播放页跳转时只能原样传下去（它手里就这一条）。于是
   * 「播完 12 → 自动播 13」之后，13 播完时 next 已经用完，
   * `handleEnded` 的 `if (nextInfo)` 不成立 —— 整条自动连播从此静默停住，
   * 「即将播放下一集」再也不出现，用户唯一的办法是回条目页重新点一次。
   * 一个号称「自动连播」的功能只会连一集，这不是降级，是功能不成立。
   */
  const [autoNext, setAutoNext] = useState<NextEpisode | null>(null)
  /** 顺带多算出来的一集：带进下一跳的 URL，让下一页仍能走零成本的旧路径 */
  const successorRef = useRef<NextEpisode | null>(null)
  const chainSeqRef = useRef(0)
  const target = nextInfo ?? autoNext

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
  const navToNext = () => {
    const ep = target
    if (!ep) return
    const p = nextToParams(ep, subjectId, subjectUrl)
    // 只有 resolveChain 顺手多算出来的那一集才写进 URL；从条目页来的旧路径
    // 手里只有 next 这一条，下一跳会自己去取列表（那时 surl 已经带上了）
    if (successorRef.current) p.set('next', JSON.stringify(successorRef.current))
    window.location.assign(`/play?${p.toString()}`)
  }

  /**
   * 手里没有下一集时，重新向数据源详情页取一次剧集列表，按当前这一集定位它的下一集。
   *
   * 定位用 playUrl 而不是集号数字：源的集号文本五花八门（「第12话」「12」「正片」），
   * 而 playUrl 就是这一页此刻正在播的那条地址，是唯一可靠的钥匙。
   * **找不到当前这一集时必须什么都不做**：少了这一格，findIndex 返回 -1，
   * list[i + 1] 就是 list[0]，症状会变成「播完最后一集自动跳回第一集」——
   * 源站在两次抓取之间换了列表（很常见）时就会触发。
   */
  const resolveChain = async () => {
    if (!source || !subjectUrl || !playUrl) return
    const seq = ++chainSeqRef.current
    try {
      const result = await api.sourceEpisodes(source, subjectUrl)
      if (seq !== chainSeqRef.current) return
      const list = result.episodes.length > 0
        ? result.episodes
        : result.groups.flatMap((g) => g.episodes)
      const i = list.findIndex((e) => e.playUrl === playUrl)
      if (i < 0) return
      const mk = (e: (typeof list)[number]): NextEpisode => ({
        source,
        play: e.playUrl,
        title: `${animeName || title} ${e.name}`,
        anime: animeName,
        ep: String(e.sortNum ?? e.sort),
      })
      const nx = list[i + 1]
      if (!nx) return
      setAutoNext(mk(nx))
      const after = list[i + 2]
      successorRef.current = after ? mk(after) : null
      setNextCountdown(5)
    } catch {
      // 取列表失败就停在今天的行为上（停在当前，用户仍可手动选）。
      // 不弹错误框：视频已经播完了，此时那句「拿不到剧集列表」只是噪声
    }
  }

  useEffect(() => {
    if (nextCountdown == null) return
    if (nextCountdown <= 0) {
      navToNext()
      return
    }
    const timer = window.setTimeout(() => setNextCountdown((c) => (c ?? 1) - 1), 1000)
    return () => window.clearTimeout(timer)
  }, [nextCountdown, target, subjectId, subjectUrl])

  // 播放进度节流写入
  const handleProgress = (time: number, duration: number) => {
    if (!source || !playUrl) return
    const last = lastWriteRef.current
    if (last == null || Math.abs(time - last) >= 4) {
      lastWriteRef.current = time
      setProgress({ source, playUrl, title, time, duration, ts: Date.now() })
      // 同步记一笔条目级进度：条目页据此显示「看到第 X 话」并标出已看过的集。
      // 只在有 subject 参数（从条目页进入）时写，从数据源页直接进来没有条目 id。
      if (subjectId > 0) setSubjectProgress(subjectId, Number(ep) || 0)
    }
  }

  // 播放结束：清除进度，有下一集则启动连播倒计时；
  // URL 里没带下一集时先去找一集（找到才会启动倒计时，找不到就停在这里）
  const handleEnded = () => {
    if (source && playUrl) clearProgress(source, playUrl)
    if (target) setNextCountdown(5)
    else void resolveChain()
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

  const gotoNext = navToNext

  const playManualUrl = () => {
    const url = manualUrl.trim()
    if (!url) return
    setSrc(`/api/proxy?${new URLSearchParams({ url }).toString()}`)
    setError('')
    // 手动换地址是本页**唯一不整页跳转**的换片路径（其余走 location.assign，组件重挂）。
    // 所以两份状态必须在这里自己清掉，否则会跟着带到新视频上：
    //  - startAt：用户点过「继续播放」再改用手动地址时，新视频会被定位到上一部的秒数；
    //    短片会被定位到结尾之后，表现就是「画面不动」；
    //  - next / nextCountdown：连播还指着刚刚失败的那条线路，播完会自动跳回它。
    setStartAt(0)
    setResumeTime(null)
    setNextCountdown(null)
    // 连播的「现场解析出来的那一集」也必须一起清掉：URL 里的 next 已经删了，
    // 但 autoNext 是本页的 state，留着它就等于「手动换地址播完仍会跳回数据源那条链」
    setAutoNext(null)
    successorRef.current = null
    chainSeqRef.current++
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
        next.delete('next')
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
            theater
            onNextEpisode={target ? gotoNext : undefined}
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
            即将播放下一集：<b>{target?.title ?? ''}</b>
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
            <div className="row">
              <span className="keys">
                <span className="kbd">触屏</span>
              </span>
              左右滑进度 · 左半屏上下滑亮度 · 右半屏上下滑音量
            </div>
            <div className="row">
              <span className="keys">
                <span className="kbd">双击</span>
              </span>
              画面左 / 右半边快退 / 快进 10 秒
            </div>
            <div className="row">
              <span className="keys">
                <span className="kbd">单击</span>
              </span>
              显示 / 收起控制条（播放中会自动收起）
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

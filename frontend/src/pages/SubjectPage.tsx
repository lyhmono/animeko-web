import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import Poster from '../components/Poster'
import { api, errMsg } from '../api'
import { pickBestHit, pickEpisode } from '../lib/titleMatch'
import type { BgmEpisode, BgmSubject, EpisodesResult, StoredSource } from '../types'
import { getSubjectProgress, isFavorite, toggleFavorite } from '../lib/storage'
import { IconHeart, IconHeartFilled } from '../lib/icons'

/** Bangumi 剧集 type：0 本篇，1 SP，2 OP，3 ED，4 预告，5 MAD，6 其他 */
const EP_TYPE_TAG: Record<number, string> = { 1: 'SP', 2: 'OP', 3: 'ED', 4: 'CM', 5: 'MAD', 6: '其他' }

/** 播放弹窗状态 */
interface PlayFlow {
  episode: BgmEpisode
  /** 数据源选择 */
  pickingSource: boolean
  /** 自动匹配中 */
  matching: boolean
  matchError: string
  /** 本次匹配使用的数据源 */
  matchedSource: string
  /** 自动匹配到多个/无法确定集数时的手动选剧集 */
  pickingEpisodes: EpisodesResult | null
  /**
   * 本次取剧集列表用的**数据源详情页地址**（hits[0].url）。
   * 必须跟着走：播放页的连播在 URL 里没有下一集时，要靠它重新取一次列表
   * （见 PlayerPage 的 resolveChain），否则连播只能多播一集就停住。
   */
  sourceSubjectUrl: string
}

export default function SubjectPage() {
  const { id } = useParams<{ id: string }>()
  const idNum = Number(id)
  // Number('abc') 为 NaN、Number('') 为 0：必须显式校验正整数，否则无效 ID 会让页面卡在「加载中…」
  const subjectId = Number.isInteger(idNum) && idNum > 0 ? idNum : 0
  const [subject, setSubject] = useState<BgmSubject | null>(null)
  const [error, setError] = useState('')
  const [sources, setSources] = useState<StoredSource[]>([])
  const [flow, setFlow] = useState<PlayFlow | null>(null)
  const [faved, setFaved] = useState(false)
  /** 「看到第几话」：由播放页在保存进度时回写（见 lib/storage 的 subjectProgress） */
  const [watchedEp, setWatchedEp] = useState(0)
  /**
   * 自动匹配的世代号。
   *
   * autoMatch 要等两次**第三方站点**的抓取（搜索 + 取剧集，慢的能到几十秒），
   * 而它的成功分支直接 window.location.assign 整页跳进播放页。
   * 原先只有失败分支带 `f ? … : f` 的判空（弹窗关了就不写状态），成功分支没有：
   * 用户点了「取消」或已经切到别的集、甚至离开了本页，那条在飞的匹配照样会把他
   * 扔进某一集里 —— 而且很可能是刚才那集，不是他现在想看的。
   * 关闭弹窗 / 换条目 / 卸载时世代+1，回不来的一律作废。
   */
  const matchGenRef = useRef(0)
  const closeFlow = useCallback(() => {
    matchGenRef.current++
    setFlow(null)
  }, [])

  useEffect(() => {
    // cancelled 守卫：切换条目时旧请求的响应不能覆盖新条目的数据
    let cancelled = false
    // 组件复用时必须重置上一条目遗留的错误与数据，否则错误会永久遮住新内容
    setError('')
    setSubject(null)
    setFaved(false)
    setWatchedEp(0)
    // 换条目也要作废上一轮的自动匹配并收起弹窗：
    // 否则在 A 条目发起的匹配会在用户切到 B 条目之后把用户带进 A 的某一集
    matchGenRef.current++
    setFlow(null)
    if (!subjectId) {
      setError('无效的条目 ID')
    } else {
      setFaved(isFavorite(subjectId))
      setWatchedEp(getSubjectProgress(subjectId)?.ep ?? 0)
      api
        .bangumiSubject(subjectId)
        .then((s) => {
          if (!cancelled) setSubject(s)
        })
        .catch((e) => {
          if (!cancelled) setError(errMsg(e))
        })
      api
        .sources()
        .then((list) => {
          if (!cancelled) setSources(list.filter((s) => s.enabled))
        })
        .catch(() => {
          if (!cancelled) setSources([])
        })
    }
    return () => {
      cancelled = true
      // 卸载后那条在飞的匹配同样不许再跳转（它做的是整页 location.assign）
      matchGenRef.current++
    }
  }, [subjectId])

  const enabledSources = sources

  const onToggleFav = () => {
    if (!subject) return
    const nowFaved = toggleFavorite({
      id: subject.id,
      name: subject.name,
      name_cn: subject.name_cn,
      cover: subject.images?.large ?? subject.images?.common ?? '',
      date: subject.date ?? '',
      score: subject.rating?.score ?? subject.score ?? null,
      ts: Date.now(),
    })
    setFaved(nowFaved)
  }

  const goPlay = useCallback(
    (
      sourceName: string,
      playUrl: string,
      animeName: string,
      epSort: number,
      epLabel: string,
      next?: { source: string; play: string; title: string; anime: string; ep: string } | null,
      subjectUrl?: string,
    ) => {
      const params = new URLSearchParams({
        source: sourceName,
        play: playUrl,
        title: `${animeName} ${epLabel}`,
        anime: animeName,
        ep: String(epSort),
        // 带上条目 id：播放页据此回写「看到第几话」，条目页才能标出进度
        subject: String(subjectId),
      })
      if (next) params.set('next', JSON.stringify(next))
      // 数据源详情页地址：连播链条在「URL 里没带下一集」时靠它重新取列表
      if (subjectUrl) params.set('surl', subjectUrl)
      window.location.assign(`/play?${params.toString()}`)
    },
    [],
  )

  /** 由数据源剧集列表构造「下一集」连播参数 */
  const nextOf = useCallback(
    (
      sourceName: string,
      episodes: { name: string; playUrl: string; sortNum: number | null; sort: string }[],
      index: number,
      animeName: string,
    ) => {
      const ep = episodes[index + 1]
      if (!ep) return null
      return {
        source: sourceName,
        play: ep.playUrl,
        title: `${animeName} ${ep.name}`,
        anime: animeName,
        ep: String(ep.sortNum ?? ep.sort),
      }
    },
    [],
  )

  /** 自动匹配：在数据源中搜番剧 → 取第一个命中 → 按集数选剧集 */
  const autoMatch = useCallback(
    async (sourceName: string, ep: BgmEpisode) => {
      if (!subject) return
      const animeName = subject.name_cn || subject.name
      const gen = ++matchGenRef.current
      // 每次写状态与跳转前都要确认这一轮还是「当前这一轮」：
      // 失败分支原来用 `f ? … : f` 只是「弹窗没了就不写」，并不能阻止成功分支跳转
      const alive = () => gen === matchGenRef.current
      setFlow((f) => (f ? { ...f, pickingSource: false, matching: true, matchError: '', matchedSource: sourceName } : f))
      try {
        const hits = await api.sourceSearch(sourceName, animeName)
        if (!alive()) return
        if (hits.length === 0) {
          setFlow((f) =>
            f ? { ...f, matching: false, matchError: `数据源「${sourceName}」未找到「${animeName}」` } : f,
          )
          return
        }
        // 不能直接拿 hits[0]：数据源按相关度排，第一条常常是别的季或番外合集
        // （用户点第一季第一集、播出来却是最终季/番外，就是这么来的）。
        // 按「季号 + 类型 + 标题相似度」挑最像这个条目的那一条。
        const best = pickBestHit(hits, [subject.name_cn, subject.name]) ?? hits[0]
        const result = await api.sourceEpisodes(sourceName, best.url)
        if (!alive()) return
        const want = ep.sort ?? 1
        // 选集同理：优先「非番外」线路，并按条目的季号在同页多季里挑对那一路；
        // 点的剧集本身是 SP/OP/ED 时（type != 0）改为只认番外线路
        const matched = pickEpisode(
          result,
          want,
          ep.name_cn || ep.name,
          [subject.name_cn, subject.name],
          (ep.type ?? 0) !== 0,
        )
        if (matched) {
          // 不能用 indexOf：分组源里 pickEpisode 返回的是 group.episodes 里的对象，
          // 经 JSON 反序列化后与扁平 episodes 中的对象不是同一个引用，indexOf 恒为 -1，
          // 连播的「下一集」会错误地落到整表第 1 条（常常是番外第 1 集）。
          const idx = result.episodes.findIndex((e) => e.playUrl === matched.playUrl)
          goPlay(
            sourceName,
            matched.playUrl,
            animeName,
            want,
            matched.name,
            nextOf(sourceName, result.episodes, idx, animeName),
            best.url,
          )
          return
        }
        // 无法自动确定集数：交给用户手动选
        setFlow((f) =>
          f ? { ...f, matching: false, pickingEpisodes: result, matchedSource: sourceName, sourceSubjectUrl: best.url } : f,
        )
      } catch (e) {
        // 这里也必须判 alive：用户取消后**又开了一个新的选集弹窗**时，
        // 上一轮的失败会把错误写进这一轮的弹窗里
        if (!alive()) return
        setFlow((f) => (f ? { ...f, matching: false, matchError: errMsg(e) } : f))
      }
    },
    [subject, goPlay],
  )

  if (error) return <div className="error-box">{error}</div>
  if (!subject) return <div className="loading">加载中…</div>

  const cover = subject.images?.large ?? subject.images?.common ?? ''
  // 封面多尺寸 srcset：浏览器按实际显示宽度选图
  const coverSrcSet = (
    [
      [subject.images?.medium, '100w'],
      [subject.images?.common, '200w'],
      [subject.images?.large, '400w'],
    ] as [string | null | undefined, string][]
  )
    .filter(([u]) => !!u)
    .map(([u, w]) => `${u} ${w}`)
    .join(', ')
  const epsList = Array.isArray(subject.eps) ? subject.eps : []
  const episodes = [...epsList].sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0))

  return (
    <div>
      <div className="subject-header">
        {/* 给 Poster 原始地址而不是代理地址：它自己会在直连失败时改走 /api/proxy。
            传代理地址进去等于双层代理 —— 直连失败时它会把「代理地址」再代理一次 */}
        <Poster
          className="cover"
          src={cover}
          srcSet={coverSrcSet}
          sizes="(max-width: 720px) 40vw, 220px"
          alt={subject.name_cn || subject.name}
        />
        <div className="meta">
          <h1>{subject.name_cn || subject.name}</h1>
          {subject.name_cn && subject.name && <div className="sub">{subject.name}</div>}
          <div className="stats">
            {(subject.rating?.score ?? subject.score) != null && (
              <span>评分 {subject.rating?.score ?? subject.score}</span>
            )}
            {(subject.rating?.rank ?? subject.rank) != null && (
              <span>排名 #{subject.rating?.rank ?? subject.rank}</span>
            )}
            {subject.date && <span>放送 {subject.date}</span>}
            <span>共 {episodes.length} 话</span>
            {watchedEp > 0 && (
              <span style={{ color: 'var(--accent)' }}>看到第 {watchedEp} 话</span>
            )}
          </div>
          <button className={`fav-btn ${faved ? 'active' : ''}`} onClick={onToggleFav}>
            {faved ? <IconHeartFilled size={15} /> : <IconHeart size={15} />}
            {faved ? '已收藏' : '收藏'}
          </button>
          <div className="summary">{subject.summary}</div>
        </div>
      </div>

      <h2 className="section-title">剧集列表</h2>
      {episodes.length === 0 && <div className="empty">该条目没有剧集信息（可能是电影/剧场版）</div>}
      {enabledSources.length === 0 && episodes.length > 0 && (
        <div className="error-box">
          还没有启用的数据源。去{' '}
          <Link to="/sources" style={{ color: 'var(--accent)', textDecoration: 'underline' }}>
            数据源管理
          </Link>{' '}
          导入 animeko 配置后再播放。
        </div>
      )}
      <div className="episode-list">
        {episodes.map((ep) => (
          <div
            className={`episode-row${
              (ep.type ?? 0) === 0 && watchedEp > 0 && ep.sort != null && ep.sort <= watchedEp ? ' watched' : ''
            }`}
            key={ep.id}
          >
            <span className="ep-sort">{ep.sort ?? '-'}</span>
            <span className="ep-title">{ep.name_cn || ep.name || `第${ep.sort}话`}</span>
            {/* 非正片剧集要和正片区分开：同一部番里 SP 和本篇的 sort 都从 1 开始，
                不标出来用户点「1」时根本不知道会播哪一个 */}
            {ep.type ? (
              <span className="tag" title="非正片剧集，自动匹配只走番外线路">
                {EP_TYPE_TAG[ep.type] ?? 'SP'}
              </span>
            ) : null}
            {ep.airdate && <span className="ep-date">{ep.airdate}</span>}
            <button
              className="btn btn-sm"
              disabled={enabledSources.length === 0}
              onClick={() =>
                setFlow({
                  episode: ep,
                  pickingSource: true,
                  matching: false,
                  matchError: '',
                  matchedSource: '',
                  pickingEpisodes: null,
                  sourceSubjectUrl: '',
                })
              }
            >
              播放
            </button>
          </div>
        ))}
      </div>

      {flow && (
        <div className="modal-mask" onClick={closeFlow}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            {flow.pickingSource && (
              <>
                <h3>
                  选择数据源 · 第{flow.episode.sort}话 {flow.episode.name_cn || flow.episode.name}
                </h3>
                <div className="source-pick-list">
                  {enabledSources.map((s) => (
                    <div className="source-pick" key={s.arguments.name}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <div style={{ flex: 1 }}>
                          <div>{s.arguments.name}</div>
                          <div className="desc">{s.arguments.description}</div>
                        </div>
                        <button
                          className="btn btn-sm"
                          onClick={() => void autoMatch(s.arguments.name, flow.episode)}
                        >
                          自动匹配
                        </button>
                        <button
                          className="btn btn-secondary btn-sm"
                          onClick={() => {
                            const params = new URLSearchParams({
                              source: s.arguments.name,
                              title: `${subject.name_cn || subject.name} ${flow.episode.name_cn || flow.episode.name}`,
                              anime: subject.name_cn || subject.name,
                              ep: String(flow.episode.sort ?? 1),
                              subject: String(subjectId),
                            })
                            window.location.assign(`/play?${params.toString()}`)
                          }}
                        >
                          手动
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
                <div className="modal-actions">
                  <button className="btn btn-secondary" onClick={closeFlow}>
                    取消
                  </button>
                </div>
              </>
            )}

            {flow.matching && <div className="loading">正在数据源中匹配…</div>}

            {!flow.pickingSource && !flow.matching && flow.matchError && (
              <>
                <h3>匹配失败</h3>
                <div className="error-box">{flow.matchError}</div>
                <div className="modal-actions">
                  <button
                    className="btn btn-secondary"
                    onClick={() =>
                      setFlow((f) => (f ? { ...f, matchError: '', pickingSource: true } : f))
                    }
                  >
                    重试
                  </button>
                  <button className="btn" onClick={closeFlow}>
                    关闭
                  </button>
                </div>
              </>
            )}

            {flow.pickingEpisodes && (
              <>
                <h3>未能自动确定集数，请手动选择剧集</h3>
                <div className="hit-list" style={{ maxHeight: 300, overflow: 'auto' }}>
                  {flow.pickingEpisodes.episodes.map((ep, idx) => (
                    <button
                      key={ep.playUrl}
                      className="source-pick"
                      onClick={() =>
                        goPlay(
                          flow.matchedSource,
                          ep.playUrl,
                          subject.name_cn || subject.name,
                          flow.episode.sort ?? 1,
                          ep.name,
                          nextOf(
                            flow.matchedSource,
                            flow.pickingEpisodes?.episodes ?? [],
                            idx,
                            subject.name_cn || subject.name,
                          ),
                          flow.sourceSubjectUrl,
                        )
                      }
                    >
                      <div>
                        [{ep.channel ?? '默认线路'}] {ep.name}
                      </div>
                    </button>
                  ))}
                </div>
                <div className="modal-actions">
                  <button className="btn btn-secondary" onClick={closeFlow}>
                    取消
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  )
}

import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, errMsg } from '../api'
import type { BgmEpisode, BgmSubject, EpisodesResult, StoredSource } from '../types'
import { isFavorite, toggleFavorite } from '../lib/storage'
import { IconHeart, IconHeartFilled } from '../lib/icons'

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

  useEffect(() => {
    // cancelled 守卫：切换条目时旧请求的响应不能覆盖新条目的数据
    let cancelled = false
    // 组件复用时必须重置上一条目遗留的错误与数据，否则错误会永久遮住新内容
    setError('')
    setSubject(null)
    setFaved(false)
    if (!subjectId) {
      setError('无效的条目 ID')
      return
    }
    setFaved(isFavorite(subjectId))
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
    return () => {
      cancelled = true
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
    ) => {
      const params = new URLSearchParams({
        source: sourceName,
        play: playUrl,
        title: `${animeName} ${epLabel}`,
        anime: animeName,
        ep: String(epSort),
      })
      if (next) params.set('next', JSON.stringify(next))
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
      setFlow((f) => (f ? { ...f, pickingSource: false, matching: true, matchError: '', matchedSource: sourceName } : f))
      try {
        const hits = await api.sourceSearch(sourceName, animeName)
        if (hits.length === 0) {
          setFlow((f) =>
            f ? { ...f, matching: false, matchError: `数据源「${sourceName}」未找到「${animeName}」` } : f,
          )
          return
        }
        const result = await api.sourceEpisodes(sourceName, hits[0].url)
        const want = ep.sort ?? 1
        const matched =
          result.episodes.find((e) => e.sortNum === want) ??
          result.episodes.find((e) => e.sortNum != null && e.sortNum >= want) ??
          null
        if (matched) {
          const idx = result.episodes.indexOf(matched)
          goPlay(
            sourceName,
            matched.playUrl,
            animeName,
            want,
            matched.name,
            nextOf(sourceName, result.episodes, idx, animeName),
          )
          return
        }
        // 无法自动确定集数：交给用户手动选
        setFlow((f) =>
          f ? { ...f, matching: false, pickingEpisodes: result, matchedSource: sourceName } : f,
        )
      } catch (e) {
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
        {cover && (
          <img
            className="cover"
            src={cover}
            srcSet={coverSrcSet}
            sizes="(max-width: 720px) 40vw, 220px"
            alt={subject.name_cn}
          />
        )}
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
          <div className="episode-row" key={ep.id}>
            <span className="ep-sort">{ep.sort ?? '-'}</span>
            <span className="ep-title">{ep.name_cn || ep.name || `第${ep.sort}话`}</span>
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
                })
              }
            >
              播放
            </button>
          </div>
        ))}
      </div>

      {flow && (
        <div className="modal-mask" onClick={() => setFlow(null)}>
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
                  <button className="btn btn-secondary" onClick={() => setFlow(null)}>
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
                  <button className="btn" onClick={() => setFlow(null)}>
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
                  <button className="btn btn-secondary" onClick={() => setFlow(null)}>
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

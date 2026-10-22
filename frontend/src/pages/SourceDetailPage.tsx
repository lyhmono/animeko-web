import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { api, errMsg } from '../api'
import type { EpisodeHit, EpisodesResult } from '../types'
import { isSourceFavorite, toggleSourceFavorite, type SourceFavorite } from '../lib/storage'
import { IconHeart, IconHeartFilled } from '../lib/icons'

export default function SourceDetailPage() {
  const { name } = useParams<{ name: string }>()
  const [params] = useSearchParams()
  const subjectUrl = params.get('url') ?? ''
  const title = params.get('title') ?? ''
  const cover = params.get('cover') ?? ''
  const [faved, setFaved] = useState(() => isSourceFavorite(name ?? '', subjectUrl))
  const [result, setResult] = useState<EpisodesResult | null>(null)
  const [error, setError] = useState('')
  const [channel, setChannel] = useState<string | null>(null)

  useEffect(() => {
    // cancelled 守卫：切换数据源/条目时旧请求的慢响应不能覆盖新结果
    let cancelled = false
    setResult(null)
    setError('')
    setChannel(null)
    // 同一源内换条目时组件不重新挂载，收藏态必须跟着地址走，
    // 否则小红心会停在上一个条目的状态上
    setFaved(!!name && isSourceFavorite(name, subjectUrl))
    if (!name || !subjectUrl) {
      // 参数缺失时必须给出明确提示，否则页面会停在「正在解析剧集列表…」
      setError(!name ? '缺少数据源参数' : '缺少条目地址（url 参数），无法解析剧集列表')
      return
    }
    // 注意：react-router v6/v7 的 useParams 已自动解码，直接使用；
    // 不能再 decodeURIComponent（含 % 的源名会双重解码而错乱/抛错）
    api
      .sourceEpisodes(name, subjectUrl)
      .then((r) => {
        if (cancelled) return
        setResult(r)
        setChannel(r.channels[0] ?? null)
      })
      .catch((e) => {
        if (!cancelled) setError(errMsg(e))
      })
    return () => {
      cancelled = true
    }
  }, [name, subjectUrl])

  const goPlay = (playUrl: string, epName: string, epSort: string, nextEp?: EpisodeHit) => {
    const q = new URLSearchParams({
      source: name ?? '',
      play: playUrl,
      title: `${title} ${epName}`,
      anime: title,
      ep: epSort,
    })
    // 数据源详情页地址也要带过去：播放页的连播在 URL 里没带下一集时靠它重新取列表。
    // 不带的话，从这条入口（数据源搜索结果 → 详情页）进去的连播只能连一集 ——
    // 和条目页那条入口的行为不一致，而两处用的是同一个播放页。
    q.set('surl', subjectUrl)
    if (nextEp) {
      q.set(
        'next',
        JSON.stringify({
          source: name ?? '',
          play: nextEp.playUrl,
          title: `${title} ${nextEp.name}`,
          anime: title,
          ep: nextEp.sort,
        }),
      )
    }
    window.location.assign(`/play?${q.toString()}`)
  }

  if (error) return <div className="error-box">{error}</div>
  if (!result) return <div className="loading">正在解析剧集列表…</div>

  const groups = result.groups.length > 0 ? result.groups : null
  const visibleEpisodes = groups
    ? channel
      ? (groups.find((g) => g.name === channel)?.episodes ?? [])
      : groups.flatMap((g) => g.episodes)
    : result.episodes

  // 「下一集」按线路计算：全部线路视图下直接取 idx+1 会跨到下一条线路的首集，连播等于重播
  const nextEpisodeOf = (ep: EpisodeHit, idx: number): EpisodeHit | undefined => {
    if (!groups || channel) return visibleEpisodes[idx + 1]
    const sameChannel = visibleEpisodes.filter((e) => e.channel === ep.channel)
    return sameChannel[sameChannel.indexOf(ep) + 1]
  }

  return (
    <div>
      <div className="page-head">
        <h2 className="section-title">
          {title || '剧集列表'}
          <span className="count">{name ?? ''}</span>
        </h2>
        {/* 从数据源结果进来的条目没有 Bangumi id，收藏按（源, 地址）记 */}
        <button
          className={`btn btn-sm ${faved ? 'btn-secondary' : ''}`}
          onClick={() => {
            const item: SourceFavorite = {
              source: name ?? '',
              url: subjectUrl,
              name: title || '未命名条目',
              cover,
              ts: Date.now(),
            }
            setFaved(toggleSourceFavorite(item))
          }}
          disabled={!name || !subjectUrl}
        >
          {faved ? <IconHeartFilled size={14} /> : <IconHeart size={14} />}
          {faved ? '已收藏' : '收藏'}
        </button>
      </div>

      {groups && groups.length > 1 && (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 14 }}>
          {groups.map((g) => (
            <button
              key={g.name}
              className={`btn btn-sm ${channel === g.name ? '' : 'btn-secondary'}`}
              onClick={() => setChannel(channel === g.name ? null : g.name)}
            >
              {g.name}（{g.episodes.length}）
            </button>
          ))}
        </div>
      )}

      {visibleEpisodes.length === 0 && <div className="empty">未解析到剧集</div>}
      <div className="episode-list">
        {visibleEpisodes.map((ep, idx) => (
          <div className="episode-row" key={ep.playUrl}>
            <span className="ep-sort">{ep.sort}</span>
            <span className="ep-title">{ep.name}</span>
            {ep.channel && <span className="tag">{ep.channel}</span>}
            <button
              className="btn btn-sm"
              onClick={() => goPlay(ep.playUrl, ep.name, ep.sort, nextEpisodeOf(ep, idx))}
            >
              播放
            </button>
          </div>
        ))}
      </div>

      <div style={{ marginTop: 20 }}>
        <Link className="btn btn-secondary" to={`/search?q=${encodeURIComponent(title)}`}>
          返回搜索
        </Link>
      </div>
    </div>
  )
}

import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import type { EpisodeHit, EpisodesResult } from '../types'

export default function SourceDetailPage() {
  const { name } = useParams<{ name: string }>()
  const [params] = useSearchParams()
  const subjectUrl = params.get('url') ?? ''
  const title = params.get('title') ?? ''
  const [result, setResult] = useState<EpisodesResult | null>(null)
  const [error, setError] = useState('')
  const [channel, setChannel] = useState<string | null>(null)

  useEffect(() => {
    if (!name || !subjectUrl) return
    // 注意：react-router v6/v7 的 useParams 已自动解码，直接使用；
    // 不能再 decodeURIComponent（含 % 的源名会双重解码而错乱/抛错）
    api
      .sourceEpisodes(name, subjectUrl)
      .then((r) => {
        setResult(r)
        setChannel(r.channels[0] ?? null)
      })
      .catch((e) => setError(String(e.message ?? e)))
  }, [name, subjectUrl])

  const goPlay = (playUrl: string, epName: string, epSort: string, nextEp?: EpisodeHit) => {
    const q = new URLSearchParams({
      source: name ?? '',
      play: playUrl,
      title: `${title} ${epName}`,
      anime: title,
      ep: epSort,
    })
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

  return (
    <div>
      <h2 className="section-title">
        {title || '剧集列表'}
        <span className="count">{name ?? ''}</span>
      </h2>

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
              onClick={() => goPlay(ep.playUrl, ep.name, ep.sort, visibleEpisodes[idx + 1])}
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

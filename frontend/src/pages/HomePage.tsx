import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
import type { StoredSource } from '../types'
import {
  addSearchHistory,
  clearSearchHistory,
  listRecent,
  listSearchHistory,
  type RecentItem,
} from '../lib/storage'
import {
  IconClock,
  IconHistory,
  IconLayers,
  IconPlay,
  IconSearch,
  IconSettings,
  IconTrash,
} from '../lib/icons'

export default function HomePage() {
  const [keyword, setKeyword] = useState('')
  const [sources, setSources] = useState<StoredSource[]>([])
  const [health, setHealth] = useState<{ danmakuConfigured: boolean; authEnabled: boolean } | null>(null)
  // 区分「检测中」与「连接失败」：null=检测中，true/false=健康检查结果
  const [apiReachable, setApiReachable] = useState<boolean | null>(null)
  const [history, setHistory] = useState<string[]>([])
  const [recent, setRecent] = useState<RecentItem[]>([])
  const navigate = useNavigate()

  useEffect(() => {
    api
      .health()
      .then((h) => {
        setHealth(h)
        setApiReachable(true)
      })
      .catch(() => {
        setHealth(null)
        setApiReachable(false)
      })
    api.sources().then(setSources).catch(() => setSources([]))
    setHistory(listSearchHistory())
    setRecent(listRecent())
  }, [])

  const enabledCount = sources.filter((s) => s.enabled).length

  const doSearch = (q: string) => {
    const kw = q.trim()
    if (!kw) return
    setHistory(addSearchHistory(kw))
    navigate(`/search?q=${encodeURIComponent(kw)}`)
  }

  return (
    <div className="fade-in">
      <section className="hero">
        <div className="hero-badge">
          <span className="badge-dot" />
          Bangumi · Selector 数据源 · 弹弹play 弹幕 · 单容器自托管
        </div>
        <h1>自托管弹幕追番</h1>
        <p>全部跑在你的容器里，浏览器负责播放与弹幕渲染</p>
        <div className="search-bar">
          <input
            placeholder="搜索番剧名称，如：孤独摇滚"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') doSearch(keyword)
            }}
          />
          <button className="btn" onClick={() => doSearch(keyword)}>
            <IconSearch size={15} />
            搜索
          </button>
        </div>
        <div className="hero-actions">
          <Link className="btn btn-secondary" to="/sources">
            <IconLayers size={15} />
            数据源管理（{enabledCount}/{sources.length} 已启用）
          </Link>
          <Link className="btn btn-secondary" to="/settings">
            <IconSettings size={15} />
            设置
          </Link>
        </div>
      </section>

      {history.length > 0 && (
        <>
          <h2 className="section-title">
            <IconHistory size={17} />
            搜索历史
          </h2>
          <div className="history-chips">
            {history.map((h) => (
              <button key={h} className="chip" onClick={() => doSearch(h)} title={h}>
                <IconClock size={13} />
                <span className="chip-text">{h}</span>
              </button>
            ))}
            <button
              className="chip-clear"
              onClick={() => {
                clearSearchHistory()
                setHistory([])
              }}
            >
              <IconTrash size={13} />
              清除
            </button>
          </div>
        </>
      )}

      {recent.length > 0 && (
        <>
          <h2 className="section-title">
            <IconPlay size={16} />
            最近观看
          </h2>
          <div className="recent-grid">
            {recent.map((r) => (
              <Link
                key={`${r.source}:${r.playUrl}`}
                className="recent-card"
                to={`/play?${new URLSearchParams({
                  source: r.source,
                  play: r.playUrl,
                  title: r.title,
                  anime: r.anime,
                  ep: r.ep,
                }).toString()}`}
              >
                <span className="recent-icon">
                  <IconPlay size={15} />
                </span>
                <span className="recent-info">
                  <span className="recent-title">{r.title}</span>
                  <span className="recent-meta">
                    {r.anime}
                    {r.ep ? ` · 第${r.ep}话` : ''}
                  </span>
                </span>
              </Link>
            ))}
          </div>
        </>
      )}

      <div className="card" style={{ marginTop: 28 }}>
        <h3 style={{ marginBottom: 10 }}>服务状态</h3>
        <div
          style={{
            display: 'flex',
            gap: 18,
            flexWrap: 'wrap',
            fontSize: 13,
            color: 'var(--text-secondary)',
          }}
        >
          <span>
            后端 API：
            <b
              style={{
                color:
                  apiReachable === null
                    ? 'var(--text-dim)'
                    : apiReachable
                      ? 'var(--success)'
                      : 'var(--danger)',
              }}
            >
              {apiReachable === null ? '检测中' : apiReachable ? '已连接' : '未连接'}
            </b>
          </span>
          <span>
            弹幕服务：
            <b style={{ color: health?.danmakuConfigured ? 'var(--success)' : 'var(--text-dim)' }}>
              {health === null
                ? '检测中'
                : health.danmakuConfigured
                  ? '已配置'
                  : '未配置（设置 ANIMEKO_DANMAKU_APP_ID/SECRET）'}
            </b>
          </span>
          <span>
            访问鉴权：
            <b style={{ color: health?.authEnabled ? 'var(--success)' : 'var(--text-dim)' }}>
              {health === null
                ? '检测中'
                : health.authEnabled
                  ? '已开启'
                  : '未开启（仅内网使用）'}
            </b>
          </span>
        </div>
      </div>

      {sources.length > 0 && (
        <>
          <h2 className="section-title">
            已启用数据源 <span className="count">{enabledCount} 个</span>
          </h2>
          <div className="hit-list">
            {sources
              .filter((s) => s.enabled)
              .map((s) => (
                <div className="hit-item" key={s.arguments.name}>
                  <span className="tag tag-accent">{s.arguments.searchConfig.subjectFormatId}</span>
                  <span className="name">{s.arguments.name}</span>
                  <Link className="btn btn-secondary btn-sm" to={`/sources`}>
                    管理
                  </Link>
                </div>
              ))}
          </div>
        </>
      )}
    </div>
  )
}

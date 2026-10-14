import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, errMsg } from '../api'
import type { BgmSubject, StoredSource, SubjectHit } from '../types'
import { addSearchHistory, favoriteIdSet } from '../lib/storage'
import { IconHeartFilled } from '../lib/icons'

export default function SearchPage() {
  const [params] = useSearchParams()
  const q = params.get('q') ?? ''
  const [bgm, setBgm] = useState<BgmSubject[]>([])
  const [bgmLoading, setBgmLoading] = useState(!!q)
  const [bgmError, setBgmError] = useState('')
  const [sources, setSources] = useState<StoredSource[]>([])
  const [sourceHits, setSourceHits] = useState<Record<string, SubjectHit[]>>({})
  const [sourceErrors, setSourceErrors] = useState<Record<string, string>>({})
  const [sourcesLoading, setSourcesLoading] = useState(!!q)
  const [favIds, setFavIds] = useState<Set<number>>(() => favoriteIdSet())

  useEffect(() => {
    // cancelled 守卫：快速连续搜索时丢弃过期响应，避免旧结果覆盖新结果
    let cancelled = false
    if (!q) {
      // 无关键词（直接打开 /search 或清空搜索词）：清空结果与加载态，避免骨架屏常驻
      setBgm([])
      setBgmError('')
      setSources([])
      setSourceHits({})
      setSourceErrors({})
      setBgmLoading(false)
      setSourcesLoading(false)
      return
    }
    addSearchHistory(q)
    setFavIds(favoriteIdSet())
    // 每次搜索都重置错误与上一轮结果，避免旧结果/旧错误残留
    setBgm([])
    setBgmError('')
    setSources([])
    setSourceHits({})
    setSourceErrors({})
    setBgmLoading(true)
    setSourcesLoading(true)
    api
      .searchBangumi(q)
      .then((r) => {
        if (!cancelled) setBgm(r)
      })
      .catch((e) => {
        if (!cancelled) setBgmError(errMsg(e))
      })
      .finally(() => {
        if (!cancelled) setBgmLoading(false)
      })

    api
      .sources()
      .then(async (list) => {
        if (cancelled) return
        const enabled = list.filter((s) => s.enabled)
        setSources(enabled)
        const hits: Record<string, SubjectHit[]> = {}
        const errors: Record<string, string> = {}
        await Promise.all(
          enabled.map(async (s) => {
            try {
              hits[s.arguments.name] = await api.sourceSearch(s.arguments.name, q)
            } catch (e) {
              errors[s.arguments.name] = errMsg(e)
            }
          }),
        )
        if (cancelled) return
        setSourceHits(hits)
        setSourceErrors(errors)
      })
      .catch(() => {
        if (!cancelled) setSources([])
      })
      .finally(() => {
        if (!cancelled) setSourcesLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [q])

  const coverOf = (s: BgmSubject) =>
    s.images?.large ?? s.images?.common ?? s.images?.medium ?? s.images?.small ?? s.images?.grid ?? ''

  // Bangumi 封面多尺寸 srcset：浏览器按实际显示宽度选图，移动端/低 DPR 下载小图省流量
  const coverSrcSet = (s: BgmSubject) =>
    (
      [
        [s.images?.medium, '100w'],
        [s.images?.common, '200w'],
        [s.images?.large, '400w'],
      ] as [string | null | undefined, string][]
    )
      .filter(([u]) => !!u)
      .map(([u, w]) => `${u} ${w}`)
      .join(', ')

  return (
    <div>
      <h2 className="section-title">
        搜索「{q}」
      </h2>
      {!q && <div className="empty">输入关键词后开始搜索</div>}

      <h3 className="section-title" style={{ fontSize: 15 }}>
        Bangumi 条目 <span className="count">{bgm.length > 0 ? `${bgm.length} 条` : ''}</span>
      </h3>
      {bgmLoading && (
        <div className="result-grid">
          {Array.from({ length: 6 }).map((_, i) => (
            <div className="skeleton-card" key={i}>
              <div className="skeleton skeleton-cover" />
              <div className="skeleton skeleton-line" />
              <div className="skeleton skeleton-line short" />
            </div>
          ))}
        </div>
      )}
      {bgmError && <div className="error-box">Bangumi 搜索失败：{bgmError}</div>}
      {!!q && !bgmLoading && !bgmError && bgm.length === 0 && (
        <div className="empty">无 Bangumi 结果</div>
      )}
      <div className="result-grid">
        {bgm.map((s) => (
          <Link key={s.id} className="subject-card" to={`/subject/${s.id}`}>
            {favIds.has(s.id) && (
              <span className="fav-badge" title="已收藏">
                <IconHeartFilled size={13} />
              </span>
            )}
            {coverOf(s) ? (
              <img
                className="cover"
                src={coverOf(s)}
                srcSet={coverSrcSet(s)}
                sizes="(max-width: 720px) 45vw, 200px"
                alt={s.name_cn}
                loading="lazy"
              />
            ) : (
              <div className="cover" />
            )}
            <div className="info">
              <div className="title">{s.name_cn || s.name}</div>
              <div className="meta">
                <span>{s.date ?? ''}</span>
                {(s.rating?.score ?? s.score) ? <span className="score">{(s.rating?.score ?? s.score)!.toFixed(1)}</span> : null}
              </div>
            </div>
          </Link>
        ))}
      </div>

      <h3 className="section-title" style={{ fontSize: 15 }}>
        视频数据源 <span className="count">{sources.length} 个已启用</span>
      </h3>
      {sourcesLoading && <div className="loading">数据源搜索中…</div>}
      {!!q && !sourcesLoading && sources.length === 0 && (
        <div className="empty">
          还没有启用的数据源，去 <Link to="/sources" style={{ color: 'var(--accent)' }}>数据源管理</Link> 导入 animeko
          配置
        </div>
      )}
      {sources.map((s) => (
        <div key={s.arguments.name} style={{ marginBottom: 18 }}>
          <div style={{ fontSize: 14, marginBottom: 8, color: 'var(--text-secondary)' }}>
            {s.arguments.name}
            <span className="count" style={{ marginLeft: 8, fontSize: 12 }}>
              {sourceHits[s.arguments.name]?.length ?? 0} 个结果
            </span>
          </div>
          {sourceErrors[s.arguments.name] && (
            <div className="error-box">{sourceErrors[s.arguments.name]}</div>
          )}
          <div className="hit-list">
            {(sourceHits[s.arguments.name] ?? []).slice(0, 20).map((hit) => (
              <div className="hit-item" key={hit.url}>
                <span className="name" title={hit.name}>
                  {hit.name}
                </span>
                <Link
                  className="btn btn-sm"
                  to={`/source/${encodeURIComponent(s.arguments.name)}?url=${encodeURIComponent(hit.url)}&title=${encodeURIComponent(hit.name)}`}
                >
                  剧集
                </Link>
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}

import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import Poster from '../components/Poster'
import { api, errMsg } from '../api'
import type { BgmSubject, StoredSource, SubjectHit } from '../types'
import { addSearchHistory, favoriteIdSet } from '../lib/storage'
import { IconHeartFilled } from '../lib/icons'

/**
 * 同时向多少个数据源发搜索请求。浏览器对同一来源只有 6 条连接，
 * 全占满之后连 /api/health 都要排队等超时（实测会让整个应用卡在启动页）。
 */
const SOURCE_SEARCH_CONCURRENCY = 3

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
    // 换关键词/离开页面时把在飞的请求掐掉：光靠 cancelled 只是不采用结果，
    // 请求还占着那条连接，下一个页面的首屏请求就得排队等它超时
    const ctrl = new AbortController()
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
      .searchBangumi(q, ctrl.signal)
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
        // 逐个回填，不能等 Promise.all 一次性 set：十几个源里有几个是死的，
        // 每个要等到超时才回来。攒齐再渲染的结果是「明明有结果的源
        // 也陪着死源一起空转」，用户看到满屏「0 个结果」以为功能坏了 —— 实测如此。
        //
        // 并发必须设上限：浏览器对同一来源只有 6 条连接，全部源一起发会把它们占满，
        // 于是 /api/health、/api/data 这类小请求排在超时后面 —— 实测表现为
        // 重新打开标签页时整个应用卡在「正在连接服务器…」。
        let next = 0
        const worker = async () => {
          while (next < enabled.length) {
            const s = enabled[next++]
            const name = s.arguments.name
            try {
              const hits = await api.sourceSearch(name, q, ctrl.signal)
              if (!cancelled) setSourceHits((prev) => ({ ...prev, [name]: hits }))
            } catch (e) {
              if (!cancelled) setSourceErrors((prev) => ({ ...prev, [name]: errMsg(e) }))
            }
          }
        }
        await Promise.all(
          Array.from({ length: Math.min(SOURCE_SEARCH_CONCURRENCY, enabled.length) }, worker),
        )
        if (!cancelled) setSourcesLoading(false)
      })
      .catch(() => {
        if (!cancelled) setSources([])
      })
      .finally(() => {
        if (!cancelled) setSourcesLoading(false)
      })
    return () => {
      cancelled = true
      ctrl.abort()
    }
  }, [q])

  const sourceDetailHref = (source: string, name: string, url: string, cover: string) =>
    `/source/${encodeURIComponent(source)}?url=${encodeURIComponent(url)}&title=${encodeURIComponent(name)}&cover=${encodeURIComponent(cover)}`

  const hitsWithCovers = (source: string) =>
    (sourceHits[source] ?? []).some((h) => !!h.cover)

  /**
   * 源的展示顺序：有结果的按命中数降序排最前，失败的折叠成一行摘要 ——
   * 十几个源里挂掉三五个时，平铺 error-box 会把整页结果推到屏幕外。
   *
   * 分三段而不是直接按命中数排，是为了配合「逐个回填」不抖动：只按数字排的话，
   * 一个源从 2 条变 6 条就会在页面上跳一次位置。这里 pending 的单独一段，
   * 每个源最多只移动一次（从「搜索中」落到有结果或零命中段）。
   */
  const ranked = useMemo(() => {
    const failed: { name: string; error: string }[] = []
    const withHits: { name: string; count: number }[] = []
    const pending: string[] = []
    const zero: string[] = []
    for (const s of sources) {
      const name = s.arguments.name
      const error = sourceErrors[name]
      if (error !== undefined) {
        failed.push({ name, error })
      } else if (name in sourceHits) {
        const count = sourceHits[name].length
        if (count > 0) withHits.push({ name, count })
        else zero.push(name)
      } else {
        pending.push(name)
      }
    }
    withHits.sort((a, b) => b.count - a.count)
    return {
      order: [...withHits.map((x) => x.name), ...pending, ...zero],
      failed,
    }
  }, [sources, sourceHits, sourceErrors])

  const coverOf = (s: BgmSubject) =>
    s.images?.large ?? s.images?.common ?? s.images?.medium ?? s.images?.small ?? s.images?.grid ?? ''

  // Bangumi 封面多尺寸 srcset：浏览器按实际显示宽度选图，移动端/低 DPR 下载小图省流量。
  // 这里给的是**原始地址**：Poster 在直连失败时才改走代理，那条路径会主动丢掉 srcset
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
            <Poster
              className="cover"
              src={coverOf(s)}
              srcSet={coverSrcSet(s)}
              sizes="(max-width: 720px) 40vw, 220px"
              alt={s.name_cn || s.name}
            />
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
      {/* 不再放全局「数据源搜索中…」：每个源自己就有 搜索中…/N 个结果，
          多出来这一行只是在结果上方悬一条居中的孤句，把版面撑出一大段空白 */}
      {!!q && !sourcesLoading && sources.length === 0 && (
        <div className="empty">
          还没有启用的数据源，去 <Link to="/sources" style={{ color: 'var(--accent)' }}>数据源管理</Link> 导入 animeko
          配置
        </div>
      )}
      {ranked.order.map((name) => {
        const hits = sourceHits[name] ?? []
        const pending = !(name in sourceHits) && !(name in sourceErrors)
        return (
          <div key={name} style={{ marginBottom: 18 }}>
            <div style={{ fontSize: 14, marginBottom: 8, color: 'var(--text-secondary)' }}>
              {name}
              <span className="count" style={{ marginLeft: 8, fontSize: 12 }}>
                {pending ? '搜索中…' : `${hits.length} 个结果`}
              </span>
            </div>
            {/* 有封面就排成卡片，没有就退回紧凑列表：
                不同源的条目格式差别很大（CSS / JsonPath），不能假定都有图 */}
            {!pending && (hitsWithCovers(name) ? (
              <div className="result-grid">
                {hits.slice(0, 20).map((hit) => (
                  <Link
                    key={hit.url}
                    className="subject-card"
                    to={sourceDetailHref(name, hit.name, hit.url, hit.cover ?? '')}
                  >
                    <Poster
                      className="cover"
                      src={hit.cover ?? ''}
                      alt={hit.name}
                      sizes="(max-width: 720px) 40vw, 220px"
                    />
                    <div className="info">
                      <div className="title" title={hit.name}>
                        {hit.name}
                      </div>
                      <div className="meta">
                        <span className="count">剧集</span>
                      </div>
                    </div>
                  </Link>
                ))}
              </div>
            ) : (
              <div className="hit-list">
                {hits.slice(0, 20).map((hit) => (
                  <div className="hit-item" key={hit.url}>
                    <span className="name" title={hit.name}>
                      {hit.name}
                    </span>
                    <Link className="btn btn-sm" to={sourceDetailHref(name, hit.name, hit.url, hit.cover ?? '')}>
                      剧集
                    </Link>
                  </div>
                ))}
              </div>
            ))}
          </div>
        )
      })}

      {ranked.failed.length > 0 && (
        <details className="source-fail">
          <summary>
            {ranked.failed.length} 个数据源无响应（点开看原因，不影响上面已成功的结果）
          </summary>
          <ul>
            {ranked.failed.map((f) => (
              <li key={f.name}>
                <b>{f.name}</b>
                <span className="source-fail-msg">{f.error}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  )
}

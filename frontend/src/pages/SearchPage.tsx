import { useEffect, useState } from 'react'
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
        // 逐个回填，不能等 Promise.all 一次性 set：21 个源里有几个是死的，
        // 每个要等到 20 秒超时才回来。攒齐再渲染的结果是「明明有结果的源
        // 也陪着死源一起空转」，用户看到满屏「0 个结果」以为功能坏了 —— 实测如此。
        //
        // 并发必须设上限：浏览器对同一来源只有 6 条连接，全部源一起发会把它们占满，
        // 于是 /api/health、/api/data 这类小请求排在 20 秒超时后面 —— 实测表现为
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
            <Poster
              className="cover"
              src={coverOf(s)}
              srcSet={coverSrcSet(s)}
              sizes="(max-width: 720px) 45vw, 200px"
              alt={s.name_cn}
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
      {sources.map((s) => {
        const name = s.arguments.name
        // 结果和错误都没回来 = 这个源还在跑。直接显示「0 个结果」是谎报：
        // 死源要 20 秒才超时，这期间用户看到的就是「所有源都是 0」
        const pending = !(name in sourceHits) && !(name in sourceErrors)
        return (
        <div key={name} style={{ marginBottom: 18 }}>
          <div style={{ fontSize: 14, marginBottom: 8, color: 'var(--text-secondary)' }}>
            {name}
            <span className="count" style={{ marginLeft: 8, fontSize: 12 }}>
              {pending ? '搜索中…' : `${sourceHits[name]?.length ?? 0} 个结果`}
            </span>
          </div>
          {sourceErrors[name] && (
            <div className="error-box">{sourceErrors[name]}</div>
          )}
          {/* 有封面就排成卡片，没有就退回紧凑列表：
              不同源的条目格式差别很大（CSS / JsonPath），不能假定都有图 */}
          {!pending && (hitsWithCovers(name) ? (
            <div className="result-grid">
              {(sourceHits[name] ?? []).slice(0, 20).map((hit) => (
                <Link
                  key={hit.url}
                  className="subject-card"
                  to={sourceDetailHref(name, hit.name, hit.url, hit.cover ?? '')}
                >
                  <Poster
                    className="cover"
                    src={hit.cover ?? ''}
                    alt={hit.name}
                    sizes="(max-width: 720px) 45vw, 200px"
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
              {(sourceHits[name] ?? []).slice(0, 20).map((hit) => (
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
    </div>
  )
}

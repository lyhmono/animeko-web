import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import Poster from '../components/Poster'
import { api, errMsg } from '../api'
import { favoriteIdSet, onDocChange } from '../lib/storage'
import { IconHeartFilled, IconStar } from '../lib/icons'
import type { BgmSubject } from '../types'

const PAGE_SIZE = 24

/**
 * 发现页：Bangumi 评分排行榜。
 *
 * 补的是「不知道看什么」这个场景 —— 搜索要求用户先有名字，而追番的真实起点
 * 往往是「最近有什么值得看的」。排行榜走 /v0/subjects?sort=rank，社区镜像
 * 也镜像了这个端点（与每日放送不同），所以在只有镜像可用的部署上依然能开。
 *
 * 分页用「加载更多」而不是页码：自托管场景下没有分享/回跳某一页的需求，
 * 逐段追加更省事，也不会因为滚动位置丢失而困惑。
 */
export default function DiscoverPage() {
  const [items, setItems] = useState<BgmSubject[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [favIds, setFavIds] = useState<Set<number>>(() => favoriteIdSet())

  const load = useCallback(async (offset: number, signal?: AbortSignal) => {
    setLoading(true)
    setError('')
    try {
      const page = await api.bangumiRanking(offset, PAGE_SIZE, signal)
      if (signal?.aborted) return
      setTotal(page.total)
      setItems((prev) => (offset === 0 ? page.data : [...prev, ...page.data]))
    } catch (e) {
      if (!signal?.aborted) setError(errMsg(e))
    } finally {
      if (!signal?.aborted) setLoading(false)
    }
  }, [])

  useEffect(() => {
    const ctrl = new AbortController()
    void load(0, ctrl.signal)
    return () => ctrl.abort()
  }, [load])

  // 收藏变了要跟上：从条目页返回会重挂并重建初始值；整份文档被换掉
  // （启动读取失败后点「重新读取」）则靠这个订阅。
  useEffect(() => onDocChange(() => setFavIds(favoriteIdSet())), [])

  const coverOf = (s: BgmSubject) =>
    s.images?.large ?? s.images?.common ?? s.images?.medium ?? s.images?.small ?? ''

  const scoreOf = (s: BgmSubject) => s.rating?.score ?? s.score ?? null
  const rankOf = (s: BgmSubject) => s.rating?.rank ?? s.rank ?? null

  return (
    <div className="fade-in">
      <h2 className="section-title">
        <IconStar size={17} />
        排行榜
        {total > 0 && <span className="count">Bangumi 动画评分榜 · 共 {total} 部</span>}
      </h2>

      {error && <div className="error-box">排行榜加载失败：{error}</div>}

      {items.length === 0 && loading && (
        <div className="result-grid">
          {Array.from({ length: 8 }).map((_, i) => (
            <div className="skeleton-card" key={i}>
              <div className="skeleton skeleton-cover" />
              <div className="skeleton skeleton-line" />
            </div>
          ))}
        </div>
      )}

      <div className="result-grid" data-testid="rank-grid">
        {items.map((s) => {
          const title = s.name_cn || s.name
          const score = scoreOf(s)
          const rank = rankOf(s)
          return (
            <Link key={s.id} className="subject-card" to={`/subject/${s.id}`}>
              {favIds.has(s.id) && (
                <span className="fav-badge" title="已收藏">
                  <IconHeartFilled size={13} />
                </span>
              )}
              <Poster
                className="cover"
                src={coverOf(s)}
                alt={title}
                sizes="(max-width: 720px) 40vw, 220px"
              />
              <div className="info">
                <div className="title">{title}</div>
                <div className="meta">
                  <span>{rank != null ? `#${rank}` : (s.date ?? '')}</span>
                  {score != null && <span className="score">{score.toFixed(1)}</span>}
                </div>
              </div>
            </Link>
          )
        })}
      </div>

      {items.length > 0 && items.length < total && (
        <div style={{ display: 'flex', justifyContent: 'center', marginTop: 24 }}>
          <button
            className="btn btn-secondary"
            disabled={loading}
            onClick={() => void load(items.length)}
          >
            {loading ? '加载中…' : '加载更多'}
          </button>
        </div>
      )}
    </div>
  )
}

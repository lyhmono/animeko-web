import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  FAV_STATUS_LABEL,
  listFavorites,
  listSourceFavorites,
  onDocChange,
  setFavoriteStatus,
  toggleFavorite,
  toggleSourceFavorite,
  type FavStatus,
  type FavoriteSubject,
  type SourceFavorite,
} from '../lib/storage'
import Poster from '../components/Poster'
import { IconHeart, IconSearch, IconTrash } from '../lib/icons'

const STATUS_ORDER: FavStatus[] = ['watching', 'wish', 'onhold', 'done']
const PAGE_SIZE = 24
type FavoriteFilter = FavStatus | 'all'
// 只保留浏览位置，不缓存收藏数据；返回时仍从当前个人文档读取。
let retainedView: {
  filter: FavoriteFilter
  limits: Partial<Record<FavoriteFilter, number>>
  sourceLimit: number
} | undefined

/**
 * 追番收藏页。
 *
 * 按状态分组而不是平铺一个列表：追番的真实状态是「在看的几部 + 想看的几十部 +
 * 看过的更多」，平铺时找正在追的那部要在几十张卡里扫。默认落在「在看」，
 * 没有在看的就落到「全部」——大多数时候用户要找的就是正在追的。
 */
export default function FavoritesPage() {
  const [favorites, setFavorites] = useState<FavoriteSubject[]>(listFavorites)
  const [sourceFavs, setSourceFavs] = useState<SourceFavorite[]>(listSourceFavorites)
  const [filter, setFilter] = useState<FavoriteFilter>(() =>
    retainedView?.filter ?? (listFavorites().some((f) => f.status === 'watching') ? 'watching' : 'all'),
  )
  const [limits, setLimits] = useState<Partial<Record<FavoriteFilter, number>>>(() => retainedView?.limits ?? {})
  const [sourceLimit, setSourceLimit] = useState(() => retainedView?.sourceLimit ?? PAGE_SIZE)
  useEffect(() => { retainedView = { filter, limits, sourceLimit } }, [filter, limits, sourceLimit])

  useEffect(
    () =>
      onDocChange(() => {
        // 上面那两个 useState 抄的是**挂载那一刻**的文档。启动时读取失败 → 用户点
        // 横幅上的「重新读取」，换掉的是整个 doc，而这个页面毫无所知 ——
        // 于是收藏明明已经读回来了，界面上仍然是一片「还没有收藏」，
        // 看起来就像数据没了。整份换档时重抄一遍。
        setFavorites(listFavorites())
        setSourceFavs(listSourceFavorites())
      }),
    [],
  )

  const counts = useMemo(() => {
    const map: Record<FavStatus, number> = { wish: 0, watching: 0, done: 0, onhold: 0 }
    for (const f of favorites) map[f.status] += 1
    return map
  }, [favorites])

  const visible = useMemo(
    () => (filter === 'all' ? favorites : favorites.filter((f) => f.status === filter)),
    [favorites, filter],
  )
  const visibleLimit = limits[filter] ?? PAGE_SIZE

  const removeSource = useCallback((item: SourceFavorite) => {
    toggleSourceFavorite(item)
    setSourceFavs(listSourceFavorites())
  }, [])

  const remove = useCallback((id: number) => {
    const list = listFavorites()
    const target = list.find((f) => f.id === id)
    if (target) toggleFavorite(target)
    setFavorites(listFavorites())
  }, [])

  const changeStatus = useCallback((id: number, status: FavStatus) => {
    setFavoriteStatus(id, status)
    setFavorites(listFavorites())
  }, [])

  return (
    <div className="fade-in">
      <h2 className="section-title">
        <IconHeart size={18} />
        追番收藏 <span className="count">{favorites.length} 部</span>
      </h2>

      {favorites.length === 0 ? (
        <div className="empty">
          <span className="empty-emoji">💖</span>
          还没有收藏的番剧。去{' '}
          <Link to="/" style={{ color: 'var(--accent)' }}>
            首页搜索
          </Link>
          ，在条目页点击「收藏」即可加入追番列表。
        </div>
      ) : (
        <>
          <div className="fav-tabs" role="group" aria-label="按追番状态筛选" data-testid="fav-tabs">
            <button
              className={`fav-tab${filter === 'all' ? ' active' : ''}`}
              onClick={() => setFilter('all')}
              type="button"
              aria-pressed={filter === 'all'}
            >
              全部 <span className="count">{favorites.length}</span>
            </button>
            {STATUS_ORDER.map((s) => (
              <button
                key={s}
                className={`fav-tab${filter === s ? ' active' : ''}${counts[s] === 0 ? ' empty' : ''}`}
                onClick={() => setFilter(s)}
                type="button"
                aria-pressed={filter === s}
              >
                {FAV_STATUS_LABEL[s]} <span className="count">{counts[s]}</span>
              </button>
            ))}
          </div>

          {visible.length === 0 ? (
            <div className="empty" style={{ padding: '22px 0' }}>
              这一档还没有番剧。用卡片里的下拉框把某一部挪进来即可。
            </div>
          ) : (
            <div className="fav-grid">
              {visible.slice(0, visibleLimit).map((f) => (
                <div className="fav-card" key={f.id}>
                  <Link to={`/subject/${f.id}`} aria-label={f.name_cn || f.name}>
                    <Poster className="cover" src={f.cover} alt={f.name_cn || f.name} />
                  </Link>
                  <button
                    className="fav-remove"
                    onClick={() => remove(f.id)}
                    title="取消收藏"
                    aria-label="取消收藏"
                  >
                    <IconTrash size={14} />
                  </button>
                  <div className="info">
                    <Link to={`/subject/${f.id}`}>
                      <div className="title">{f.name_cn || f.name}</div>
                    </Link>
                    <div className="meta">
                      <span>{f.date || '—'}</span>
                      {f.score != null && (
                        <span style={{ color: 'var(--warn)', fontWeight: 600 }}>
                          {f.score.toFixed(1)}
                        </span>
                      )}
                    </div>
                    <select
                      className={`fav-status status-${f.status}`}
                      value={f.status}
                      onChange={(e) => changeStatus(f.id, e.target.value as FavStatus)}
                      aria-label={`${f.name_cn || f.name} 的追番状态`}
                      title="追番状态"
                    >
                      {STATUS_ORDER.map((s) => (
                        <option key={s} value={s}>
                          {FAV_STATUS_LABEL[s]}
                        </option>
                      ))}
                    </select>
                  </div>
                </div>
              ))}
            </div>
          )}
          {visible.length > 0 && (
            <div className="fav-pagination">
              <span>已显示 {Math.min(visibleLimit, visible.length)} / {visible.length}</span>
              {visibleLimit < visible.length && (
                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  aria-label="加载更多追番收藏"
                  onClick={() => setLimits((previous) => ({
                    ...previous,
                    [filter]: Math.min(visible.length, (previous[filter] ?? PAGE_SIZE) + PAGE_SIZE),
                  }))}
                >加载更多</button>
              )}
            </div>
          )}
        </>
      )}

      <div style={{ marginTop: 24 }}>
        <Link className="btn btn-secondary" to="/">
          <IconSearch size={15} />
          去搜索更多番剧
        </Link>
      </div>

      {/* 数据源收藏：从源结果页直接收藏的条目，点进去还是那个源的剧集列表 */}
      <h2 className="section-title" style={{ marginTop: 34 }}>
        <IconHeart size={18} />
        数据源收藏 <span className="count">{sourceFavs.length} 条</span>
      </h2>
      {sourceFavs.length === 0 ? (
        <div className="empty" style={{ padding: '22px 0' }}>
          在数据源搜索结果或剧集列表页点「收藏」，条目会记在这里（按 源 + 地址 计）。
        </div>
      ) : (
        <>
          <div className="fav-grid">
            {sourceFavs.slice(0, sourceLimit).map((f) => (
              <div className="fav-card" key={`${f.source}|${f.url}`}>
                <Link
                  to={`/source/${encodeURIComponent(f.source)}?url=${encodeURIComponent(f.url)}&title=${encodeURIComponent(f.name)}&cover=${encodeURIComponent(f.cover)}`}
                  aria-label={f.name}
                >
                  <Poster className="cover" src={f.cover} alt={f.name} />
                </Link>
                <button
                  className="fav-remove"
                  onClick={() => removeSource(f)}
                  title="取消收藏"
                  aria-label="取消收藏"
                >
                  <IconTrash size={14} />
                </button>
                <div className="info">
                  <Link
                    to={`/source/${encodeURIComponent(f.source)}?url=${encodeURIComponent(f.url)}&title=${encodeURIComponent(f.name)}&cover=${encodeURIComponent(f.cover)}`}
                  >
                    <div className="title">{f.name}</div>
                  </Link>
                  <div className="meta">
                    <span className="count">{f.source}</span>
                  </div>
                </div>
              </div>
            ))}
          </div>
          <div className="fav-pagination">
            <span>已显示 {Math.min(sourceLimit, sourceFavs.length)} / {sourceFavs.length}</span>
            {sourceLimit < sourceFavs.length && (
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                aria-label="加载更多数据源收藏"
                onClick={() => setSourceLimit((previous) => Math.min(sourceFavs.length, previous + PAGE_SIZE))}
              >加载更多</button>
            )}
          </div>
        </>
      )}
    </div>
  )
}

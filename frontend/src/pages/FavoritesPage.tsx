import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { listFavorites, toggleFavorite, type FavoriteSubject } from '../lib/storage'
import { IconHeart, IconSearch, IconTrash } from '../lib/icons'

export default function FavoritesPage() {
  const [favorites, setFavorites] = useState<FavoriteSubject[]>(listFavorites)

  const remove = useCallback((id: number) => {
    const list = listFavorites()
    const target = list.find((f) => f.id === id)
    if (target) toggleFavorite(target)
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
        <div className="fav-grid">
          {favorites.map((f) => (
            <div className="fav-card" key={f.id}>
              <Link to={`/subject/${f.id}`} aria-label={f.name_cn || f.name}>
                {f.cover ? (
                  <img className="cover" src={f.cover} alt={f.name_cn || f.name} loading="lazy" />
                ) : (
                  <div className="cover" />
                )}
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
                    <span style={{ color: 'var(--warn)', fontWeight: 600 }}>{f.score.toFixed(1)}</span>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      <div style={{ marginTop: 24 }}>
        <Link className="btn btn-secondary" to="/">
          <IconSearch size={15} />
          去搜索更多番剧
        </Link>
      </div>
    </div>
  )
}

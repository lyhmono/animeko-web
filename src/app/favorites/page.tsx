'use client';

// 收藏页：按追番状态筛选 + 数据源收藏。
import { useEffect, useState } from 'react';
import Link from 'next/link';
import {
  FAV_STATUS_LABEL, listFavorites, listSourceFavorites, onDocChange,
  setFavoriteStatus, toggleFavorite, toggleSourceFavorite,
  type FavoriteSubject, type FavStatus, type SourceFavorite,
} from '@/lib/storage';
import { VideoCard } from '@/components/VideoCard';
import { IconDatabase, IconTrash } from '@/components/Icons';

export default function FavoritesPage() {
  const [favorites, setFavorites] = useState<FavoriteSubject[]>([]);
  const [sourceFavorites, setSourceFavorites] = useState<SourceFavorite[]>([]);
  const [filter, setFilter] = useState<FavStatus | 'all'>('all');

  useEffect(() => {
    const update = () => {
      setFavorites(listFavorites());
      setSourceFavorites(listSourceFavorites());
    };
    update();
    return onDocChange(update);
  }, []);

  const filtered = filter === 'all' ? favorites : favorites.filter((f) => f.status === filter);

  return (
    <div className="space-y-10">
      <section>
        <div className="mb-4 flex flex-wrap items-center gap-2">
          <h1 className="mr-3 text-xl font-bold">我的收藏</h1>
          {(['all', 'watching', 'wish', 'done', 'onhold'] as const).map((status) => (
            <button key={status} type="button" onClick={() => setFilter(status)}
              className={`rounded-full px-4 py-1.5 text-sm transition ${filter === status ? 'bg-brand-500 font-semibold text-white' : 'glass-chip hover:text-brand-400'}`}>
              {status === 'all' ? `全部（${favorites.length}）` : FAV_STATUS_LABEL[status]}
            </button>
          ))}
        </div>

        {filtered.length ? (
          <div className="grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-6 lg:grid-cols-8">
            {filtered.map((f) => (
              <div key={f.id} className="relative">
                <VideoCard href={`/subject/${f.id}`} title={f.name_cn || f.name} cover={f.cover}
                  score={f.score} year={f.date ? f.date.slice(0, 4) : null}
                  badge={FAV_STATUS_LABEL[f.status]} favorite
                  onToggleFavorite={() => toggleFavorite(f)} />
                <label className="sr-only" htmlFor={`fav-status-${f.id}`}>追番状态</label>
                <select id={`fav-status-${f.id}`} value={f.status}
                  onChange={(e) => setFavoriteStatus(f.id, e.target.value as FavStatus)}
                  className="mt-1 w-full rounded-lg border border-white/10 bg-black/30 px-1 py-1 text-xs text-gray-300 outline-none">
                  {(Object.keys(FAV_STATUS_LABEL) as FavStatus[]).map((s) => (
                    <option key={s} value={s}>{FAV_STATUS_LABEL[s]}</option>
                  ))}
                </select>
              </div>
            ))}
          </div>
        ) : (
          <p className="py-12 text-center text-gray-500">
            暂无收藏。去 <Link href="/discover" className="text-brand-400 hover:underline">发现页</Link> 找几部番吧。
          </p>
        )}
      </section>

      {sourceFavorites.length > 0 && (
        <section>
          <h2 className="mb-3 flex items-center gap-2 text-lg font-bold"><IconDatabase /> 数据源收藏</h2>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {sourceFavorites.map((f) => (
              <div key={`${f.source}|${f.url}`} className="glass-panel flex items-center gap-3 p-4">
                <Link href={`/source/${encodeURIComponent(f.source)}?url=${encodeURIComponent(f.url)}&anime=${encodeURIComponent(f.name)}`}
                  className="min-w-0 flex-1">
                  <p className="truncate text-sm font-semibold hover:text-brand-400">{f.name}</p>
                  <p className="mt-1 text-xs text-gray-500">{f.source}</p>
                </Link>
                <button type="button" aria-label="删除收藏" className="text-gray-500 transition hover:text-red-400"
                  onClick={() => toggleSourceFavorite(f)}>
                  <IconTrash />
                </button>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}

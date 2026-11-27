'use client';

// 条目页：沉浸式背景 + 详情 + 收藏 + Bangumi 剧集 + 数据源自动匹配。
import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { api, errMsg } from '@/api';
import type { BgmEpisode, BgmSubject, StoredSource, SubjectHit } from '@/types';
import {
  FAV_STATUS_LABEL, getSubjectProgress, isFavorite, listFavorites, onDocChange,
  setFavoriteStatus, toggleFavorite, type FavStatus,
} from '@/lib/storage';
import { pickBestHit } from '@/lib/titleMatch';
import { IconCheck, IconHeart, IconHeartFilled, IconPlay, IconStar } from '@/components/Icons';

interface SourceMatch {
  source: string;
  hit: SubjectHit;
  total: number;
}

export default function SubjectPage({ params }: { params: { id: string } }) {
  const subjectId = Number(params.id);
  const [subject, setSubject] = useState<BgmSubject | null>(null);
  const [error, setError] = useState('');
  const [favorite, setFavorite] = useState(false);
  const [favStatus, setFavStatus] = useState<FavStatus>('wish');
  const [progressEp, setProgressEp] = useState(0);
  const [matches, setMatches] = useState<SourceMatch[]>([]);
  const [matching, setMatching] = useState(false);
  const [matchError, setMatchError] = useState('');
  const [episodeType, setEpisodeType] = useState(0);

  useEffect(() => {
    let cancelled = false;
    api.bangumiSubject(subjectId)
      .then((s) => { if (!cancelled) setSubject(s); })
      .catch((e) => { if (!cancelled) setError(errMsg(e)); });
    return () => { cancelled = true; };
  }, [subjectId]);

  useEffect(() => {
    const update = () => {
      setFavorite(isFavorite(subjectId));
      const fav = listFavorites().find((f) => f.id === subjectId);
      if (fav) setFavStatus(fav.status);
      setProgressEp(getSubjectProgress(subjectId)?.ep ?? 0);
    };
    update();
    return onDocChange(update);
  }, [subjectId]);

  const matchSources = useCallback(async (s: BgmSubject) => {
    setMatching(true);
    setMatchError('');
    setMatches([]);
    try {
      const sources: StoredSource[] = await api.sources();
      const enabled = sources.filter((x) => x.enabled);
      if (!enabled.length) {
        setMatchError('还没有启用的数据源，请先到「数据源」页导入。');
        return;
      }
      const names = [s.name_cn, s.name].filter(Boolean);
      await Promise.all(enabled.map(async (source) => {
        const sourceName = source.arguments.name;
        try {
          const hits = await api.sourceSearch(sourceName, names[0] || s.name);
          const best = pickBestHit(hits, names);
          if (best) {
            setMatches((prev) => [...prev, { source: sourceName, hit: best, total: hits.length }]);
          }
        } catch {
          // 单源失败不影响其他源
        }
      }));
    } catch (e) {
      setMatchError(errMsg(e));
    } finally {
      setMatching(false);
    }
  }, []);

  useEffect(() => {
    if (subject) void matchSources(subject);
  }, [subject, matchSources]);

  if (error) {
    return <div className="glass-panel p-6 text-sm text-red-400">条目加载失败：{error}</div>;
  }
  if (!subject) {
    return <div className="flex justify-center py-24"><div className="h-8 w-8 animate-spin rounded-full border-2 border-brand-500 border-t-transparent" /></div>;
  }

  const title = subject.name_cn || subject.name;
  const score = subject.score ?? subject.rating?.score ?? null;
  const episodes = (Array.isArray(subject.eps) ? subject.eps : []).filter((e) => (e.type ?? 0) === episodeType);
  const episodeTypes = [...new Set((Array.isArray(subject.eps) ? subject.eps : []).map((e) => e.type ?? 0))];

  const onToggleFavorite = () => {
    toggleFavorite({
      id: subject.id,
      name: subject.name,
      name_cn: subject.name_cn,
      cover: subject.images?.common ?? '',
      date: subject.date ?? '',
      score,
      ts: Date.now(),
    });
  };

  return (
    <div className="space-y-8">
      {/* === 沉浸式头部 === */}
      <section className="relative overflow-hidden rounded-2xl">
        {subject.images?.large && (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={subject.images.large} alt="" aria-hidden="true"
            className="absolute inset-0 h-full w-full scale-110 object-cover blur-2xl brightness-[0.35]" />
        )}
        <div className="relative flex flex-col gap-6 p-6 sm:flex-row sm:p-8">
          <div className="mx-auto w-40 shrink-0 sm:mx-0 sm:w-48">
            <div className="aspect-[2/3] overflow-hidden rounded-xl shadow-2xl">
              {subject.images?.common ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img src={subject.images.common} alt={title} className="h-full w-full object-cover" />
              ) : (
                <div className="flex h-full items-center justify-center bg-gray-800 p-4 text-center text-sm text-gray-400">{title}</div>
              )}
            </div>
          </div>
          <div className="min-w-0 flex-1">
            <h1 className="text-2xl font-bold sm:text-3xl">{title}</h1>
            {subject.name_cn && subject.name && <p className="mt-1 text-sm text-gray-400">{subject.name}</p>}
            <div className="mt-3 flex flex-wrap items-center gap-3 text-sm">
              {score != null && score > 0 && (
                <span className="flex items-center gap-1 font-semibold text-amber-300"><IconStar />{score.toFixed(1)}</span>
              )}
              {subject.rating?.total != null && <span className="text-gray-400">{subject.rating.total} 人评分</span>}
              {subject.date && <span className="glass-chip">{subject.date}</span>}
              {subject.rating?.rank ? <span className="glass-chip">排名 #{subject.rating.rank}</span> : null}
              {progressEp > 0 && <span className="glass-chip border-brand-500/40 text-brand-400">已看到第 {progressEp} 话</span>}
            </div>
            {subject.summary && <p className="mt-4 max-w-3xl text-sm leading-relaxed text-gray-300">{subject.summary}</p>}
            <div className="mt-5 flex flex-wrap items-center gap-3">
              <button type="button" className="btn-primary" onClick={onToggleFavorite}>
                {favorite ? <IconHeartFilled className="text-red-300" /> : <IconHeart />}
                {favorite ? '已收藏' : '收藏'}
              </button>
              {favorite && (
                <div className="flex gap-1 rounded-full border border-white/10 bg-black/30 p-1">
                  {(Object.keys(FAV_STATUS_LABEL) as FavStatus[]).map((status) => (
                    <button key={status} type="button" onClick={() => setFavoriteStatus(subject.id, status)}
                      className={`rounded-full px-3 py-1 text-xs transition ${favStatus === status ? 'bg-brand-500 text-white' : 'text-gray-400 hover:text-gray-100'}`}>
                      {FAV_STATUS_LABEL[status]}
                    </button>
                  ))}
                </div>
              )}
              {matches.length > 0 && (
                <Link href={`/source/${encodeURIComponent(matches[0].source)}?url=${encodeURIComponent(matches[0].hit.url)}&subject=${subject.id}&anime=${encodeURIComponent(title)}`}
                  className="btn-ghost">
                  <IconPlay /> 立即播放
                </Link>
              )}
            </div>
          </div>
        </div>
      </section>

      {/* === 数据源匹配 === */}
      <section>
        <h2 className="mb-3 text-lg font-bold">播放源{matching ? '（匹配中…）' : ''}</h2>
        {matchError && <p className="text-sm text-amber-300">{matchError}</p>}
        {matches.length ? (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {matches.map((m) => (
              <Link key={m.source} href={`/source/${encodeURIComponent(m.source)}?url=${encodeURIComponent(m.hit.url)}&subject=${subject.id}&anime=${encodeURIComponent(title)}`}
                className="glass-panel flex items-center gap-3 p-3 transition hover:border-brand-500/50">
                <div className="h-20 w-14 shrink-0 overflow-hidden rounded-lg bg-gray-800">
                  {m.hit.cover && (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img src={m.hit.cover} alt={m.hit.name} className="h-full w-full object-cover" />
                  )}
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-semibold">{m.hit.name}</p>
                  <p className="mt-1 text-xs text-gray-500">{m.source} · {m.total} 个匹配结果</p>
                </div>
                <IconPlay className="shrink-0 text-brand-500" />
              </Link>
            ))}
          </div>
        ) : !matching && !matchError && (
          <p className="text-sm text-gray-500">已启用的数据源中未找到匹配条目，可尝试在搜索页直接检索数据源。</p>
        )}
      </section>

      {/* === Bangumi 剧集 === */}
      {episodes.length > 0 && (
        <section>
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-lg font-bold">剧集列表</h2>
            {episodeTypes.length > 1 && (
              <div className="flex gap-1">
                {episodeTypes.map((t) => (
                  <button key={t} type="button" onClick={() => setEpisodeType(t)}
                    className={`rounded-full px-3 py-1 text-xs ${episodeType === t ? 'bg-brand-500 text-white' : 'glass-chip'}`}>
                    {t === 0 ? '本篇' : t === 1 ? 'SP' : t === 2 ? 'OP' : t === 3 ? 'ED' : t === 4 ? '预告' : '其他'}
                  </button>
                ))}
              </div>
            )}
          </div>
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {episodes.map((ep: BgmEpisode) => {
              const watched = ep.sort != null && ep.sort <= progressEp;
              return (
                <div key={ep.id} className={`glass-panel flex items-center gap-3 px-4 py-2.5 text-sm ${watched ? 'opacity-60' : ''}`}>
                  <span className="w-8 shrink-0 text-center font-semibold text-brand-400">{ep.sort ?? '-'}</span>
                  <span className="min-w-0 flex-1 truncate" title={ep.name_cn || ep.name}>{ep.name_cn || ep.name || `第 ${ep.sort} 话`}</span>
                  {watched && <IconCheck className="shrink-0 text-brand-500" />}
                  {ep.airdate && <span className="shrink-0 text-xs text-gray-500">{ep.airdate}</span>}
                </div>
              );
            })}
          </div>
        </section>
      )}
    </div>
  );
}

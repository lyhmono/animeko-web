'use client';

// 数据源条目页：线路分组选集、源收藏、逐集播放入口。
import { Suspense, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { api, errMsg } from '@/api';
import type { EpisodeHit, EpisodesResult } from '@/types';
import {
  isSourceFavorite, onDocChange, progressMap, toggleSourceFavorite,
} from '@/lib/storage';
import { buildPlayUrl, type PlayEpisode } from '@/lib/playUrl';
import { IconHeart, IconHeartFilled, IconPlay } from '@/components/Icons';

function SourceDetailInner({ params }: { params: { name: string } }) {
  const sourceName = decodeURIComponent(params.name);
  const searchParams = useSearchParams();
  const subjectUrl = searchParams.get('url') ?? '';
  const subjectId = Number(searchParams.get('subject') ?? 0) || 0;
  const anime = searchParams.get('anime') ?? '';

  const [result, setResult] = useState<EpisodesResult | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [channel, setChannel] = useState(0);
  const [favorite, setFavorite] = useState(false);
  const [progress, setProgress] = useState<Record<string, unknown>>({});

  useEffect(() => {
    if (!subjectUrl) {
      setError('缺少条目地址（url 参数）');
      setLoading(false);
      return;
    }
    setLoading(true);
    api.sourceEpisodes(sourceName, subjectUrl)
      .then(setResult)
      .catch((e) => setError(errMsg(e)))
      .finally(() => setLoading(false));
  }, [sourceName, subjectUrl]);

  useEffect(() => {
    const update = () => {
      setFavorite(isSourceFavorite(sourceName, subjectUrl));
      setProgress(progressMap());
    };
    update();
    return onDocChange(update);
  }, [sourceName, subjectUrl]);

  const episodes: EpisodeHit[] = useMemo(() => {
    if (!result) return [];
    if (result.groups.length) return result.groups[channel]?.episodes ?? [];
    return result.episodes;
  }, [result, channel]);

  const playLink = (ep: EpisodeHit, next?: EpisodeHit) => {
    const toPlayEpisode = (e: EpisodeHit): PlayEpisode => ({
      source: sourceName,
      play: e.playUrl,
      title: anime ? `${anime} ${e.name}` : e.name,
      anime,
      ep: e.sort,
      ...(subjectId && e.sortNum != null ? { bep: String(e.sortNum) } : {}),
    });
    return buildPlayUrl(toPlayEpisode(ep), {
      subjectId,
      subjectUrl,
      next: next ? toPlayEpisode(next) : null,
    });
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold">{anime || '数据源条目'}</h1>
          <p className="mt-1 text-sm text-gray-500">{sourceName}{result ? ` · 共 ${result.episodes.length} 集` : ''}</p>
        </div>
        <button type="button" className="btn-ghost" onClick={() => {
          toggleSourceFavorite({ source: sourceName, url: subjectUrl, name: anime || subjectUrl, cover: '', ts: Date.now() });
        }}>
          {favorite ? <IconHeartFilled className="text-red-400" /> : <IconHeart />}
          {favorite ? '已收藏' : '收藏该源'}
        </button>
      </div>

      {result && result.groups.length > 1 && (
        <div className="flex flex-wrap gap-2" role="tablist" aria-label="线路">
          {result.groups.map((g, i) => (
            <button key={g.name} type="button" role="tab" aria-selected={channel === i} onClick={() => setChannel(i)}
              className={`rounded-full px-4 py-1.5 text-sm transition ${channel === i ? 'bg-brand-500 font-semibold text-white' : 'glass-chip hover:text-brand-400'}`}>
              {g.name}（{g.episodes.length}）
            </button>
          ))}
        </div>
      )}

      {loading && (
        <div className="flex justify-center py-16"><div className="h-8 w-8 animate-spin rounded-full border-2 border-brand-500 border-t-transparent" /></div>
      )}
      {error && <div className="glass-panel p-4 text-sm text-red-400">{error}</div>}

      {result && !episodes.length && !loading && (
        <p className="text-sm text-gray-500">该条目下没有解析到剧集。</p>
      )}

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 md:grid-cols-6 lg:grid-cols-8">
        {episodes.map((ep, i) => {
          const entry = progress[`${sourceName}:${ep.playUrl}`] as { time?: number; duration?: number } | undefined;
          const watched = entry?.time && entry.duration && entry.time / entry.duration > 0.9;
          return (
            <a key={ep.playUrl} href={playLink(ep, episodes[i + 1])}
              className={`glass-panel group relative flex flex-col items-center justify-center gap-1 px-2 py-3 text-center transition hover:border-brand-500/60 ${watched ? 'opacity-50' : ''}`}
              title={ep.name}>
              <span className="text-base font-semibold text-brand-400">{ep.sort || i + 1}</span>
              <span className="line-clamp-1 text-xs text-gray-400">{ep.name}</span>
              <IconPlay className="absolute right-2 top-2 text-xs text-brand-500 opacity-0 transition group-hover:opacity-100" />
            </a>
          );
        })}
      </div>
    </div>
  );
}

export default function SourceDetailPage({ params }: { params: { name: string } }) {
  return (
    <Suspense fallback={<div className="py-16 text-center text-gray-500">加载中…</div>}>
      <SourceDetailInner params={params} />
    </Suspense>
  );
}

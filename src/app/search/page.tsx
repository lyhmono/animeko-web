'use client';

// 搜索页：Bangumi + 全部启用数据源聚合搜索，搜索历史持久化到服务端。
import { Suspense, useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { api, errMsg } from '@/api';
import type { StoredSource, SubjectHit } from '@/types';
import { addSearchHistory, clearSearchHistory, listSearchHistory, onDocChange } from '@/lib/storage';
import { SectionHeader } from '@/components/ScrollableRow';
import { VideoCard, VideoCardSkeleton } from '@/components/VideoCard';
import { IconSearch, IconTrash, IconX } from '@/components/Icons';

interface SourceResult {
  source: string;
  hits: SubjectHit[];
  error?: string;
}

function SearchInner() {
  const params = useSearchParams();
  const router = useRouter();
  const q = params.get('q')?.trim() ?? '';
  const [input, setInput] = useState(q);
  const [bangumi, setBangumi] = useState<Awaited<ReturnType<typeof api.searchBangumi>>>([]);
  const [sourceResults, setSourceResults] = useState<SourceResult[]>([]);
  const [sources, setSources] = useState<StoredSource[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [history, setHistory] = useState<string[]>([]);

  useEffect(() => setInput(q), [q]);

  useEffect(() => {
    setHistory(listSearchHistory());
    return onDocChange(() => setHistory(listSearchHistory()));
  }, []);

  useEffect(() => {
    api.sources().then(setSources).catch(() => setSources([]));
  }, []);

  const doSearch = useCallback(async (keyword: string) => {
    if (!keyword) return;
    setLoading(true);
    setError('');
    setBangumi([]);
    setSourceResults([]);
    addSearchHistory(keyword);
    const bgmPromise = api.searchBangumi(keyword).then(setBangumi).catch((e) => setError(errMsg(e)));
    let sourceList = sources;
    if (!sourceList.length) {
      sourceList = await api.sources().catch(() => [] as StoredSource[]);
      setSources(sourceList);
    }
    const enabled = sourceList.filter((s) => s.enabled);
    const sourcePromise = Promise.all(enabled.map(async (s) => {
      const name = s.arguments.name;
      try {
        const hits = await api.sourceSearch(name, keyword);
        setSourceResults((prev) => [...prev, { source: name, hits }]);
      } catch (e) {
        setSourceResults((prev) => [...prev, { source: name, hits: [], error: errMsg(e) }]);
      }
    }));
    await Promise.allSettled([bgmPromise, sourcePromise]);
    setLoading(false);
  }, [sources]);

  useEffect(() => {
    if (q) void doSearch(q);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q]);

  return (
    <div className="space-y-8">
      <form role="search" className="mx-auto max-w-2xl" onSubmit={(e) => {
        e.preventDefault();
        const keyword = input.trim();
        if (keyword) router.push(`/search?q=${encodeURIComponent(keyword)}`);
      }}>
        <label htmlFor="search-page-input" className="sr-only">搜索番剧</label>
        <div className="relative">
          <IconSearch className="absolute left-4 top-1/2 -translate-y-1/2 text-xl text-gray-500" />
          <input id="search-page-input" value={input} onChange={(e) => setInput(e.target.value)} autoFocus
            placeholder="输入番剧名称，同时检索 Bangumi 与已启用数据源"
            className="w-full rounded-full border border-white/10 bg-black/30 py-3 pl-12 pr-12 text-base outline-none transition focus:border-brand-500/60 focus:ring-2 focus:ring-brand-500/20" />
          {input && (
            <button type="button" aria-label="清空" onClick={() => setInput('')}
              className="absolute right-4 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-200">
              <IconX />
            </button>
          )}
        </div>
      </form>

      {!q && history.length > 0 && (
        <section>
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-semibold text-gray-400">搜索历史</h2>
            <button type="button" className="text-xs text-gray-500 hover:text-red-400"
              onClick={() => { clearSearchHistory(); setHistory([]); }}>
              <IconTrash className="mr-1 inline" />清空
            </button>
          </div>
          <div className="flex flex-wrap gap-2">
            {history.map((h) => (
              <Link key={h} href={`/search?q=${encodeURIComponent(h)}`} className="glass-chip hover:border-brand-500/50 hover:text-brand-400">{h}</Link>
            ))}
          </div>
        </section>
      )}

      {error && <p className="text-sm text-amber-300">Bangumi 搜索失败：{error}</p>}

      {q && (
        <section>
          <SectionHeader title={`Bangumi 结果${bangumi.length ? `（${bangumi.length}）` : ''}`} />
          {loading && !bangumi.length ? (
            <div className="grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-6 lg:grid-cols-8">
              {Array.from({ length: 8 }, (_, i) => <VideoCardSkeleton key={i} />)}
            </div>
          ) : bangumi.length ? (
            <div className="grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-6 lg:grid-cols-8">
              {bangumi.map((s) => (
                <VideoCard key={s.id} href={`/subject/${s.id}`} title={s.name_cn || s.name}
                  cover={s.images?.common ?? null} score={s.score ?? s.rating?.score ?? null}
                  year={s.date ? s.date.slice(0, 4) : null} />
              ))}
            </div>
          ) : !loading && <p className="text-sm text-gray-500">Bangumi 没有匹配结果。</p>}
        </section>
      )}

      {sourceResults.map((result) => (
        <section key={result.source}>
          <SectionHeader title={`数据源 · ${result.source}`} />
          {result.error ? (
            <p className="text-sm text-red-400">{result.error}</p>
          ) : result.hits.length ? (
            <div className="grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-6 lg:grid-cols-8">
              {result.hits.map((hit) => (
                <VideoCard key={hit.url} title={hit.name} cover={hit.cover ?? null}
                  href={`/source/${encodeURIComponent(result.source)}?url=${encodeURIComponent(hit.url)}`}
                  badge={result.source} />
              ))}
            </div>
          ) : (
            <p className="text-sm text-gray-500">该源没有匹配结果。</p>
          )}
        </section>
      ))}

      {q && !loading && !bangumi.length && !sourceResults.length && !error && (
        <p className="py-16 text-center text-gray-500">没有找到相关内容，换个关键词试试。</p>
      )}
    </div>
  );
}

export default function SearchPage() {
  return (
    <Suspense fallback={<div className="py-16 text-center text-gray-500">加载中…</div>}>
      <SearchInner />
    </Suspense>
  );
}

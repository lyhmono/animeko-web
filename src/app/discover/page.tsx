'use client';

// 发现页：每日放送（按星期切换）+ 评分排行榜分页浏览。
import { useEffect, useState } from 'react';
import { api, errMsg } from '@/api';
import type { BgmCalendarDay, BgmSubject } from '@/types';
import { SectionHeader } from '@/components/ScrollableRow';
import { VideoCard, VideoCardSkeleton } from '@/components/VideoCard';

export default function DiscoverPage() {
  const [calendar, setCalendar] = useState<BgmCalendarDay[]>([]);
  const [calendarError, setCalendarError] = useState('');
  const jsDay = new Date().getDay();
  const [weekday, setWeekday] = useState(jsDay === 0 ? 7 : jsDay);
  const [ranking, setRanking] = useState<BgmSubject[]>([]);
  const [offset, setOffset] = useState(0);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    api.bangumiCalendar().then(setCalendar).catch((e) => setCalendarError(errMsg(e)));
  }, []);

  useEffect(() => {
    setLoading(true);
    api.bangumiRanking(offset, 24)
      .then((page) => {
        setTotal(page.total);
        setRanking((prev) => offset === 0 ? page.data : [...prev, ...page.data]);
      })
      .catch((e) => setError(errMsg(e)))
      .finally(() => setLoading(false));
  }, [offset]);

  const currentDay = calendar.find((d) => d.weekday.id === weekday);

  return (
    <div className="space-y-10">
      <section>
        <SectionHeader title="每日放送" />
        {calendarError ? (
          <p className="glass-panel p-4 text-sm text-amber-300">放送表不可用（当前通道可能不支持）：{calendarError}</p>
        ) : (
          <>
            <div className="mb-4 flex flex-wrap gap-2" role="tablist" aria-label="星期">
              {calendar.map((day) => (
                <button key={day.weekday.id} type="button" role="tab" aria-selected={weekday === day.weekday.id}
                  onClick={() => setWeekday(day.weekday.id)}
                  className={`rounded-full px-4 py-1.5 text-sm transition ${weekday === day.weekday.id ? 'bg-brand-500 font-semibold text-white' : 'glass-chip hover:text-brand-400'}`}>
                  {day.weekday.cn}
                </button>
              ))}
            </div>
            <div className="grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-6 lg:grid-cols-8">
              {(currentDay?.items ?? []).map((item) => (
                <VideoCard key={item.id} href={`/subject/${item.id}`} title={item.name_cn || item.name}
                  cover={item.images?.common ?? null} score={item.score ?? item.rating?.score ?? null}
                  badge={item.eps != null ? `${item.eps} 话` : null} />
              ))}
            </div>
            {!calendar.length && <p className="text-sm text-gray-500">放送表加载中…</p>}
          </>
        )}
      </section>

      <section>
        <SectionHeader title="评分排行榜" />
        {error && <p className="mb-3 text-sm text-amber-300">{error}</p>}
        <div className="grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-6 lg:grid-cols-8">
          {ranking.map((s) => (
            <VideoCard key={s.id} href={`/subject/${s.id}`} title={s.name_cn || s.name}
              cover={s.images?.common ?? null} score={s.score ?? s.rating?.score ?? null}
              year={s.date ? s.date.slice(0, 4) : null}
              badge={s.rank ?? s.rating?.rank ? `No.${s.rank ?? s.rating?.rank}` : null} />
          ))}
          {loading && Array.from({ length: 8 }, (_, i) => <VideoCardSkeleton key={`skeleton-${i}`} />)}
        </div>
        {ranking.length > 0 && ranking.length < total && (
          <div className="mt-6 text-center">
            <button type="button" className="btn-ghost" disabled={loading} onClick={() => setOffset((o) => o + 24)}>
              {loading ? '加载中…' : `加载更多（${ranking.length}/${total}）`}
            </button>
          </div>
        )}
      </section>
    </div>
  );
}

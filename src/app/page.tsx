'use client';

// 首页：Banner 轮播 + 继续观看 + 今日放送 + 评分排行榜 + 在看收藏。
import { useEffect, useMemo, useState } from 'react';
import { api, errMsg } from '@/api';
import type { BgmCalendarDay, BgmSubject } from '@/types';
import { listFavorites, onDocChange, type FavoriteSubject } from '@/lib/storage';
import { BannerCarousel } from '@/components/BannerCarousel';
import { ContinueWatching } from '@/components/ContinueWatching';
import { SectionHeader, ScrollableRow } from '@/components/ScrollableRow';
import { VideoCard, VideoCardSkeleton } from '@/components/VideoCard';

function subjectTitle(s: { name: string; name_cn: string }): string {
  return s.name_cn || s.name;
}

export default function HomePage() {
  const [ranking, setRanking] = useState<BgmSubject[]>([]);
  const [calendar, setCalendar] = useState<BgmCalendarDay[]>([]);
  const [watching, setWatching] = useState<FavoriteSubject[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    Promise.allSettled([api.bangumiRanking(0, 24), api.bangumiCalendar()])
      .then(([rank, cal]) => {
        if (cancelled) return;
        if (rank.status === 'fulfilled') setRanking(rank.value.data);
        else setError(errMsg(rank.reason));
        if (cal.status === 'fulfilled') setCalendar(cal.value);
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    const update = () => setWatching(listFavorites().filter((f) => f.status === 'watching').slice(0, 12));
    update();
    return onDocChange(update);
  }, []);

  const todayItems = useMemo(() => {
    if (!calendar.length) return [];
    const jsDay = new Date().getDay(); // 0=周日
    const weekday = jsDay === 0 ? 7 : jsDay;
    const today = calendar.find((d) => d.weekday.id === weekday);
    return (today ?? calendar[0]).items.slice(0, 16);
  }, [calendar]);

  const bannerItems = useMemo(() => ranking.slice(0, 5).map((s) => ({
    id: s.id,
    title: subjectTitle(s),
    cover: s.images?.large ?? s.images?.common ?? null,
    summary: s.summary,
    score: s.score ?? s.rating?.score ?? null,
    href: `/subject/${s.id}`,
  })), [ranking]);

  return (
    <div className="space-y-8">
      {bannerItems.length > 0 && <BannerCarousel items={bannerItems} />}

      <ContinueWatching />

      {error && (
        <div className="glass-panel p-4 text-sm text-amber-300">
          排行榜加载失败：{error}。可前往「设置 → Bangumi 通道」探测可用线路。
        </div>
      )}

      {todayItems.length > 0 && (
        <section>
          <SectionHeader title="今日新番放送" moreHref="/discover" />
          <ScrollableRow>
            {todayItems.map((item) => (
              <VideoCard key={item.id} href={`/subject/${item.id}`} title={subjectTitle(item)}
                cover={item.images?.common ?? item.images?.medium ?? null}
                score={item.score ?? item.rating?.score ?? null}
                badge={item.eps != null ? `更新至 ${item.eps} 话` : null} />
            ))}
          </ScrollableRow>
        </section>
      )}

      <section>
        <SectionHeader title="评分排行榜" moreHref="/discover" />
        {loading ? (
          <ScrollableRow>{Array.from({ length: 8 }, (_, i) => <VideoCardSkeleton key={i} />)}</ScrollableRow>
        ) : (
          <ScrollableRow>
            {ranking.map((s) => (
              <VideoCard key={s.id} href={`/subject/${s.id}`} title={subjectTitle(s)}
                cover={s.images?.common ?? s.images?.medium ?? null}
                score={s.score ?? s.rating?.score ?? null}
                year={s.date ? s.date.slice(0, 4) : null}
                badge={s.rank ?? s.rating?.rank ? `No.${s.rank ?? s.rating?.rank}` : null} />
            ))}
          </ScrollableRow>
        )}
      </section>

      {watching.length > 0 && (
        <section>
          <SectionHeader title="在看番剧" moreHref="/favorites" />
          <ScrollableRow>
            {watching.map((f) => (
              <VideoCard key={f.id} href={`/subject/${f.id}`} title={f.name_cn || f.name}
                cover={f.cover} score={f.score} />
            ))}
          </ScrollableRow>
        </section>
      )}
    </div>
  );
}

'use client';

// 继续观看：最近观看 + 断点进度，随个人数据变更实时刷新。
import { useEffect, useState } from 'react';
import { listRecent, onDocChange, progressMap, type RecentItem } from '@/lib/storage';
import { buildPlayUrl } from '@/lib/playUrl';
import { SectionHeader, ScrollableRow } from './ScrollableRow';
import { VideoCard } from './VideoCard';

interface ContinueItem extends RecentItem {
  progress: number;
  duration: number;
}

function collect(): ContinueItem[] {
  const map = progressMap();
  return listRecent(8).map((item) => {
    const key = `${item.source}:${item.playUrl}`;
    const entry = map[key] as { time?: number; duration?: number } | undefined;
    const time = typeof entry?.time === 'number' ? entry.time : 0;
    const duration = typeof entry?.duration === 'number' ? entry.duration : 0;
    return { ...item, progress: duration > 0 ? time / duration : 0, duration };
  });
}

export function ContinueWatching() {
  const [items, setItems] = useState<ContinueItem[]>([]);

  useEffect(() => {
    setItems(collect());
    return onDocChange(() => setItems(collect()));
  }, []);

  if (!items.length) return null;

  return (
    <section>
      <SectionHeader title="继续观看" moreHref="/favorites" />
      <ScrollableRow>
        {items.map((item) => (
          <VideoCard key={`${item.source}:${item.playUrl}`} wide
            href={buildPlayUrl({ source: item.source, play: item.playUrl, title: item.title, anime: item.anime, ep: item.ep })}
            title={item.anime || item.title}
            subtitle={`${item.ep ? `第 ${item.ep} 集 · ` : ''}${item.source}`}
            progress={item.progress}
            cover={null}
          />
        ))}
      </ScrollableRow>
    </section>
  );
}

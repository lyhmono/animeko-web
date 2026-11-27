'use client';

// 首页横幅轮播：交叉淡入淡出、自动播放、hover 暂停、触屏滑动。
import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { IconChevronLeft, IconChevronRight, IconPlay, IconStar } from './Icons';

export interface BannerItem {
  id: number;
  title: string;
  cover?: string | null;
  summary?: string;
  score?: number | null;
  href: string;
}

export function BannerCarousel({ items }: { items: BannerItem[] }) {
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  const touchStartX = useRef(0);
  const count = items.length;

  const next = useCallback(() => setIndex((i) => (i + 1) % count), [count]);
  const prev = useCallback(() => setIndex((i) => (i - 1 + count) % count), [count]);

  useEffect(() => {
    if (paused || count <= 1) return;
    const timer = setInterval(next, 5000);
    return () => clearInterval(timer);
  }, [next, paused, count]);

  if (!count) return null;

  return (
    <section aria-label="推荐番剧" className="relative h-52 overflow-hidden rounded-2xl sm:h-72 lg:h-96"
      onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)}
      onTouchStart={(e) => { touchStartX.current = e.touches[0].clientX; }}
      onTouchEnd={(e) => {
        const dx = e.changedTouches[0].clientX - touchStartX.current;
        if (dx > 40) prev();
        else if (dx < -40) next();
      }}>
      {items.map((item, i) => (
        <div key={item.id} className={`banner-slide absolute inset-0 ${i === index ? 'opacity-100' : 'pointer-events-none opacity-0'}`}>
          {/* 模糊背景 + 主视觉 */}
          {item.cover && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={item.cover} alt="" aria-hidden="true"
              className="absolute inset-0 h-full w-full scale-110 object-cover blur-2xl brightness-50" />
          )}
          {item.cover && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={item.cover} alt={item.title}
              className="absolute inset-0 h-full w-full object-cover object-top opacity-70" />
          )}
          <div className="absolute inset-0 bg-gradient-to-t from-black via-black/40 to-transparent" />
          <div className="absolute inset-0 bg-gradient-to-r from-black/70 via-transparent to-transparent" />
          <div className="absolute bottom-0 left-0 max-w-2xl p-5 sm:p-8">
            <h2 className="text-xl font-bold text-white drop-shadow sm:text-3xl">{item.title}</h2>
            {item.score != null && item.score > 0 && (
              <p className="mt-1 flex items-center gap-1 text-sm text-amber-300">
                <IconStar /> {item.score.toFixed(1)} 分
              </p>
            )}
            {item.summary && (
              <p className="mt-2 hidden text-sm leading-relaxed text-gray-300 sm:line-clamp-2">{item.summary}</p>
            )}
            <Link href={item.href} className="btn-primary mt-4">
              <IconPlay /> 查看详情
            </Link>
          </div>
        </div>
      ))}
      {count > 1 && (
        <>
          <button type="button" aria-label="上一张" onClick={prev}
            className="absolute left-3 top-1/2 hidden -translate-y-1/2 rounded-full bg-black/50 p-2 text-white transition hover:bg-brand-500 md:block">
            <IconChevronLeft className="text-xl" />
          </button>
          <button type="button" aria-label="下一张" onClick={next}
            className="absolute right-3 top-1/2 hidden -translate-y-1/2 rounded-full bg-black/50 p-2 text-white transition hover:bg-brand-500 md:block">
            <IconChevronRight className="text-xl" />
          </button>
          <div className="absolute bottom-3 right-4 flex gap-1.5">
            {items.map((item, i) => (
              <button key={item.id} type="button" aria-label={`切换到第 ${i + 1} 张`} onClick={() => setIndex(i)}
                className={`h-1.5 rounded-full transition-all ${i === index ? 'w-6 bg-brand-500' : 'w-1.5 bg-white/40'}`} />
            ))}
          </div>
        </>
      )}
    </section>
  );
}

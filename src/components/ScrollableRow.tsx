'use client';

// 横向分区：标题 + 查看更多 + 可滚动卡片行。
import Link from 'next/link';
import { useRef } from 'react';
import { IconChevronLeft, IconChevronRight } from './Icons';

export function SectionHeader({ title, moreHref }: { title: string; moreHref?: string }) {
  return (
    <div className="mb-3 flex items-center justify-between">
      <h2 className="text-lg font-bold tracking-wide">{title}</h2>
      {moreHref && (
        <Link href={moreHref} className="text-xs text-gray-400 transition hover:text-brand-400">查看更多 →</Link>
      )}
    </div>
  );
}

export function ScrollableRow({ children }: { children: React.ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const scroll = (dir: number) => {
    ref.current?.scrollBy({ left: dir * ref.current.clientWidth * 0.8, behavior: 'smooth' });
  };
  return (
    <div className="group/row relative">
      <div ref={ref} className="scroll-row flex gap-3 overflow-x-auto pb-2 pt-1 md:gap-4">
        {children}
      </div>
      <button type="button" aria-label="向左滚动" onClick={() => scroll(-1)}
        className="absolute -left-3 top-1/2 hidden -translate-y-1/2 rounded-full border border-white/10 bg-black/70 p-2 text-gray-300 opacity-0 shadow-lg transition hover:text-brand-400 group-hover/row:opacity-100 md:block">
        <IconChevronLeft />
      </button>
      <button type="button" aria-label="向右滚动" onClick={() => scroll(1)}
        className="absolute -right-3 top-1/2 hidden -translate-y-1/2 rounded-full border border-white/10 bg-black/70 p-2 text-gray-300 opacity-0 shadow-lg transition hover:text-brand-400 group-hover/row:opacity-100 md:block">
        <IconChevronRight />
      </button>
    </div>
  );
}

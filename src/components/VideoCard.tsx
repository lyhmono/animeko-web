'use client';

// 视频海报卡：评分锦旗角标、hover 播放遮罩、可选观看进度。
import Link from 'next/link';
import { useState } from 'react';
import { IconHeart, IconHeartFilled, IconPlay } from './Icons';

export interface VideoCardProps {
  href: string;
  title: string;
  cover?: string | null;
  score?: number | null;
  year?: string | null;
  badge?: string | null;
  progress?: number; // 0~1
  subtitle?: string;
  favorite?: boolean;
  onToggleFavorite?: () => void;
  wide?: boolean;
}

export function VideoCard({
  href, title, cover, score, year, badge, progress, subtitle, favorite, onToggleFavorite, wide = false,
}: VideoCardProps) {
  const [imgError, setImgError] = useState(false);
  const scoreColor = score != null && score >= 8 ? 'bg-amber-400 text-black'
    : score != null && score >= 6.5 ? 'bg-brand-500 text-white' : 'bg-gray-500/80 text-white';

  return (
    <div className="video-card relative w-28 shrink-0 sm:w-36 md:w-40 lg:w-44">
      <Link href={href} className="group block" title={title}>
        <div className={`relative overflow-hidden rounded-lg bg-gray-800 ${wide ? 'aspect-[3/2]' : 'aspect-[2/3]'}`}>
          {cover && !imgError ? (
            // 海报来自第三方站点，使用原生 img 避免 next/image 域名白名单维护成本
            // eslint-disable-next-line @next/next/no-img-element
            <img src={cover} alt={title} loading="lazy" onError={() => setImgError(true)}
              className="h-full w-full object-cover transition duration-300 group-hover:brightness-75" />
          ) : (
            <div className="flex h-full w-full items-center justify-center bg-gradient-to-br from-gray-800 to-gray-900 p-3 text-center text-xs text-gray-500">
              {title}
            </div>
          )}
          {/* 评分角标 */}
          {score != null && score > 0 && (
            <span className={`absolute right-1.5 top-1.5 rounded px-1.5 py-0.5 text-[10px] font-bold shadow ${scoreColor}`}>
              {score.toFixed(1)}
            </span>
          )}
          {year && (
            <span className="absolute left-1.5 top-1.5 rounded bg-black/60 px-1.5 py-0.5 text-[10px] text-gray-200">{year}</span>
          )}
          {badge && (
            <span className="absolute bottom-1.5 right-1.5 rounded bg-black/60 px-1.5 py-0.5 text-[10px] text-gray-200">{badge}</span>
          )}
          {/* hover 遮罩 */}
          <div className="card-overlay absolute inset-0 flex items-center justify-center bg-gradient-to-t from-black/80 via-black/20 to-transparent">
            <span className="flex h-12 w-12 items-center justify-center rounded-full bg-brand-500/90 text-white shadow-lg">
              <IconPlay className="ml-0.5 text-xl" />
            </span>
          </div>
          {/* 观看进度 */}
          {progress != null && progress > 0 && (
            <div className="absolute inset-x-0 bottom-0 h-1 bg-white/20">
              <div className="h-full bg-brand-500" style={{ width: `${Math.min(100, progress * 100)}%` }} />
            </div>
          )}
        </div>
        <div className="mt-1.5 px-0.5">
          <p className="card-title truncate text-center text-sm font-semibold">{title}</p>
          {subtitle && <p className="truncate text-center text-[11px] text-gray-500">{subtitle}</p>}
        </div>
      </Link>
      {onToggleFavorite && (
        <button type="button" aria-label={favorite ? '取消收藏' : '收藏'} onClick={(e) => { e.preventDefault(); onToggleFavorite(); }}
          className="card-overlay absolute bottom-10 left-1/2 -translate-x-1/2 rounded-full bg-black/70 p-2 text-white transition hover:bg-brand-500">
          {favorite ? <IconHeartFilled className="text-red-400" /> : <IconHeart />}
        </button>
      )}
    </div>
  );
}

export function VideoCardSkeleton() {
  return (
    <div className="w-28 shrink-0 sm:w-36 md:w-40 lg:w-44">
      <div className="aspect-[2/3] animate-pulse rounded-lg bg-white/5" />
      <div className="mx-auto mt-2 h-3 w-3/4 animate-pulse rounded bg-white/5" />
    </div>
  );
}

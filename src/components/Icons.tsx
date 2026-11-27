'use client';

// 轻量 SVG 图标集（无外部依赖）。
import type { SVGProps } from 'react';

function IconBase({ children, ...props }: SVGProps<SVGSVGElement>) {
  return (
    <svg viewBox="0 0 24 24" width="1em" height="1em" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>
      {children}
    </svg>
  );
}

export const IconHome = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><path d="M3 10.5 12 3l9 7.5" /><path d="M5 9.5V21h14V9.5" /><path d="M9 21v-6h6v6" /></IconBase>
);
export const IconSearch = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></IconBase>
);
export const IconCompass = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><circle cx="12" cy="12" r="9" /><path d="m15.5 8.5-2 5-5 2 2-5z" /></IconBase>
);
export const IconHeart = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><path d="M12 20.5C7 16.5 3 13.3 3 9.3 3 6.4 5.2 4.5 7.7 4.5c1.7 0 3.3.9 4.3 2.3 1-1.4 2.6-2.3 4.3-2.3 2.5 0 4.7 1.9 4.7 4.8 0 4-4 7.2-9 11.2z" /></IconBase>
);
export const IconHeartFilled = (p: SVGProps<SVGSVGElement>) => (
  <svg viewBox="0 0 24 24" width="1em" height="1em" fill="currentColor" aria-hidden="true" {...p}>
    <path d="M12 20.5C7 16.5 3 13.3 3 9.3 3 6.4 5.2 4.5 7.7 4.5c1.7 0 3.3.9 4.3 2.3 1-1.4 2.6-2.3 4.3-2.3 2.5 0 4.7 1.9 4.7 4.8 0 4-4 7.2-9 11.2z" />
  </svg>
);
export const IconDatabase = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><ellipse cx="12" cy="5" rx="8" ry="3" /><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5" /><path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3" /></IconBase>
);
export const IconSettings = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.9 2.9l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.9-2.9l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.9-2.9l.1.1a1.7 1.7 0 0 0 1.9.3h0a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5h0a1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.9 2.9l-.1.1a1.7 1.7 0 0 0-.3 1.9v0a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" /></IconBase>
);
export const IconPlay = (p: SVGProps<SVGSVGElement>) => (
  <svg viewBox="0 0 24 24" width="1em" height="1em" fill="currentColor" aria-hidden="true" {...p}>
    <path d="M8 5.5v13a1 1 0 0 0 1.5.87l11-6.5a1 1 0 0 0 0-1.74l-11-6.5A1 1 0 0 0 8 5.5z" />
  </svg>
);
export const IconChevronLeft = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><path d="m15 5-7 7 7 7" /></IconBase>
);
export const IconChevronRight = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><path d="m9 5 7 7-7 7" /></IconBase>
);
export const IconMenu = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><path d="M4 6h16M4 12h16M4 18h16" /></IconBase>
);
export const IconX = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><path d="m6 6 12 12M18 6 6 18" /></IconBase>
);
export const IconSun = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><circle cx="12" cy="12" r="4" /><path d="M12 2v2m0 16v2M2 12h2m16 0h2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></IconBase>
);
export const IconMoon = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><path d="M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5z" /></IconBase>
);
export const IconDownload = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><path d="M12 3v12m0 0 4-4m-4 4-4-4" /><path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" /></IconBase>
);
export const IconSubtitle = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><rect x="3" y="5" width="18" height="14" rx="2" /><path d="M7 12h4m-4 3h7m3-3h.01M17 15h.01" /></IconBase>
);
export const IconExternal = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><path d="M14 4h6v6" /><path d="M20 4 11 13" /><path d="M19 13v6a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h6" /></IconBase>
);
export const IconDanmaku = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><path d="M21 12a8 8 0 0 1-8 8H4l2.3-2.9A8 8 0 1 1 21 12z" /><path d="M8 10h8m-8 4h5" /></IconBase>
);
export const IconLink = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><path d="M10 14a5 5 0 0 0 7.1 0l2.1-2.1a5 5 0 0 0-7.1-7.1L11 5.9" /><path d="M14 10a5 5 0 0 0-7.1 0l-2.1 2.1a5 5 0 0 0 7.1 7.1l1.1-1.1" /></IconBase>
);
export const IconTrash = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><path d="M4 7h16M9 7V4h6v3m-8 0 1 13h8l1-13" /></IconBase>
);
export const IconRefresh = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><path d="M20 11a8 8 0 1 0-2.3 6.3" /><path d="M20 4v7h-7" /></IconBase>
);
export const IconCheck = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><path d="m4 12.5 5 5L20 6.5" /></IconBase>
);
export const IconClock = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 3" /></IconBase>
);
export const IconLogout = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" /><path d="m16 17 5-5-5-5m5 5H9" /></IconBase>
);
export const IconStar = (p: SVGProps<SVGSVGElement>) => (
  <svg viewBox="0 0 24 24" width="1em" height="1em" fill="currentColor" aria-hidden="true" {...p}>
    <path d="m12 2 3.1 6.3 6.9 1-5 4.9 1.2 6.8L12 17.8 5.8 21 7 14.2 2 9.3l6.9-1z" />
  </svg>
);
export const IconTv = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><rect x="3" y="6" width="18" height="13" rx="2" /><path d="m8 2 4 4 4-4" /></IconBase>
);
export const IconPlus = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><path d="M12 5v14M5 12h14" /></IconBase>
);
export const IconFolder = (p: SVGProps<SVGSVGElement>) => (
  <IconBase {...p}><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" /></IconBase>
);

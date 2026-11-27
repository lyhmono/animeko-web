'use client';

// 页面骨架：桌面侧边栏 + 移动顶部搜索 / 底部导航。
import { useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useTheme } from 'next-themes';
import { useBoot } from './BootGate';
import {
  IconCompass, IconDatabase, IconHeart, IconHome, IconLogout, IconMoon,
  IconSearch, IconSettings, IconSun,
} from './Icons';

const NAV_ITEMS = [
  { href: '/', label: '首页', icon: IconHome },
  { href: '/search', label: '搜索', icon: IconSearch },
  { href: '/discover', label: '发现', icon: IconCompass },
  { href: '/favorites', label: '收藏', icon: IconHeart },
  { href: '/sources', label: '数据源', icon: IconDatabase },
  { href: '/settings', label: '设置', icon: IconSettings },
];

function isActive(pathname: string, href: string): boolean {
  if (href === '/') return pathname === '/';
  return pathname.startsWith(href);
}

export function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme();
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  return (
    <button type="button" aria-label="切换明暗主题"
      className="rounded-full border border-white/10 bg-white/5 p-2 text-gray-300 transition hover:bg-white/10"
      onClick={() => setTheme(resolvedTheme === 'dark' ? 'light' : 'dark')}>
      {mounted && resolvedTheme === 'dark' ? <IconSun className="text-lg" /> : <IconMoon className="text-lg" />}
    </button>
  );
}

function SyncBadge() {
  const { syncState } = useBoot();
  const label = syncState.status === 'saving' ? '保存中'
    : syncState.status === 'pending' ? '待同步'
      : syncState.status === 'error' ? '同步异常' : '已同步';
  const color = syncState.status === 'error' ? 'bg-red-400'
    : syncState.status === 'ready' ? 'bg-brand-500' : 'bg-amber-400';
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-gray-400" title={syncState.error || '个人数据同步状态'}>
      <span className={`h-1.5 w-1.5 rounded-full ${color}`} />{label}
    </span>
  );
}

function SearchBox({ id, className = '', autoFocus = false }: { id: string; className?: string; autoFocus?: boolean }) {
  const router = useRouter();
  const [value, setValue] = useState('');
  return (
    <form role="search" className={className} onSubmit={(e) => {
      e.preventDefault();
      const q = value.trim();
      if (q) router.push(`/search?q=${encodeURIComponent(q)}`);
    }}>
      <label htmlFor={id} className="sr-only">搜索番剧</label>
      <div className="relative">
        <IconSearch className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-gray-500" />
        <input id={id} value={value} onChange={(e) => setValue(e.target.value)} autoFocus={autoFocus}
          placeholder="搜索番剧 / 数据源…"
          className="w-full rounded-full border border-white/10 bg-black/30 py-2 pl-9 pr-4 text-sm outline-none transition placeholder:text-gray-500 focus:border-brand-500/60 focus:ring-2 focus:ring-brand-500/20" />
      </div>
    </form>
  );
}

export function PageLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const { user, logout } = useBoot();
  const [collapsed, setCollapsed] = useState(false);

  useEffect(() => {
    try {
      setCollapsed(localStorage.getItem('sidebarCollapsed') === 'true');
    } catch { /* 忽略 */ }
  }, []);

  const toggleCollapsed = () => {
    setCollapsed((prev) => {
      try {
        localStorage.setItem('sidebarCollapsed', JSON.stringify(!prev));
      } catch { /* 忽略 */ }
      return !prev;
    });
  };

  return (
    <div className="min-h-screen">
      {/* === 桌面侧边栏 === */}
      <aside className={`fixed inset-y-0 left-0 z-40 hidden flex-col border-r border-white/10 bg-black/40 backdrop-blur-xl transition-all duration-300 md:flex ${collapsed ? 'w-16' : 'w-60'}`}>
        <div className="flex h-16 items-center justify-between px-4">
          {!collapsed && (
            <Link href="/" className="text-lg font-bold tracking-wide">
              Animeko<span className="text-brand-500">Web</span>
            </Link>
          )}
          <button type="button" onClick={toggleCollapsed} aria-label={collapsed ? '展开侧边栏' : '折叠侧边栏'}
            className="rounded-lg p-2 text-gray-400 transition hover:bg-white/10 hover:text-brand-400">
            <IconCompass className="text-lg" />
          </button>
        </div>
        {!collapsed && <SearchBox id="sidebar-search" className="px-3 pb-3" />}
        <nav className="flex-1 space-y-1 overflow-y-auto px-2" aria-label="主导航">
          {NAV_ITEMS.map((item) => {
            const active = isActive(pathname, item.href);
            return (
              <Link key={item.href} href={item.href} title={item.label}
                className={`flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm transition ${active ? 'bg-brand-500/15 font-semibold text-brand-400' : 'text-gray-400 hover:bg-white/5 hover:text-gray-100'}`}>
                <item.icon className="shrink-0 text-xl" />
                {!collapsed && item.label}
              </Link>
            );
          })}
        </nav>
        <div className="space-y-3 border-t border-white/10 p-3">
          <div className={`flex items-center ${collapsed ? 'justify-center' : 'justify-between'}`}>
            <ThemeToggle />
            {!collapsed && <SyncBadge />}
          </div>
          {!collapsed && (
            <button type="button" onClick={() => void logout()}
              className="flex w-full items-center gap-2 rounded-xl px-3 py-2 text-xs text-gray-500 transition hover:bg-white/5 hover:text-gray-200">
              <IconLogout /> 注销{user ? `（${user}）` : ''}
            </button>
          )}
        </div>
      </aside>

      {/* === 移动顶部 === */}
      <header className="fixed inset-x-0 top-0 z-40 flex h-14 items-center gap-3 border-b border-white/10 bg-black/60 px-4 backdrop-blur-xl md:hidden">
        <Link href="/" className="shrink-0 text-base font-bold">
          Animeko<span className="text-brand-500">Web</span>
        </Link>
        <SearchBox id="mobile-search" className="flex-1" />
        <ThemeToggle />
      </header>

      {/* === 主内容 === */}
      <main className={`px-4 pb-24 pt-[4.5rem] transition-all duration-300 md:px-8 md:pb-12 md:pt-8 ${collapsed ? 'md:pl-24' : 'md:pl-72'}`}>
        <div className="mx-auto max-w-page page-enter">{children}</div>
      </main>

      {/* === 移动底部导航 === */}
      <nav aria-label="底部导航"
        className="fixed inset-x-0 bottom-0 z-40 flex h-14 items-stretch border-t border-white/10 bg-black/70 backdrop-blur-xl md:hidden"
        style={{ paddingBottom: 'env(safe-area-inset-bottom)' }}>
        {NAV_ITEMS.filter((i) => ['/', '/discover', '/search', '/favorites', '/settings'].includes(i.href)).map((item) => {
          const active = isActive(pathname, item.href);
          return (
            <Link key={item.href} href={item.href}
              className={`flex flex-1 flex-col items-center justify-center gap-0.5 text-[10px] ${active ? 'text-brand-400' : 'text-gray-500'}`}>
              <item.icon className="text-xl" />
              {item.label}
            </Link>
          );
        })}
      </nav>
    </div>
  );
}

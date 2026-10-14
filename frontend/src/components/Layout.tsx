import { useEffect, useState } from 'react'
import { NavLink, Outlet } from 'react-router-dom'
import { getTheme, setTheme, syncThemeColor, type Theme } from '../lib/storage'
import { IconHeart, IconHome, IconLayers, IconMoon, IconSettings, IconSun } from '../lib/icons'

export default function Layout() {
  const [theme, setThemeState] = useState<Theme>(getTheme)

  // 设置页切换主题后同步本组件状态
  useEffect(() => {
    const onThemeChange = () => setThemeState(getTheme())
    window.addEventListener('animeko-theme-change', onThemeChange)
    return () => window.removeEventListener('animeko-theme-change', onThemeChange)
  }, [])

  const toggleTheme = () => {
    const next: Theme = theme === 'dark' ? 'light' : 'dark'
    setTheme(next)
    setThemeState(next)
    // 必须同步 DOM 属性：CSS 双主题由 :root[data-theme] 驱动，
    // 仅写 localStorage/state 不会让当前页面即时换肤
    document.documentElement.dataset.theme = next
    syncThemeColor(next)
  }

  return (
    <div className="app">
      <nav className="nav">
        <NavLink to="/" className="nav-logo">
          <span className="dot" />
          Animeko Web
        </NavLink>
        <div className="nav-links">
          <NavLink to="/" end title="首页">
            <IconHome size={16} className="nav-icon" />
            <span className="nav-text">首页</span>
          </NavLink>
          <NavLink to="/sources" title="数据源">
            <IconLayers size={16} className="nav-icon" />
            <span className="nav-text">数据源</span>
          </NavLink>
          <NavLink to="/favorites" title="收藏">
            <IconHeart size={16} className="nav-icon" />
            <span className="nav-text">收藏</span>
          </NavLink>
          <NavLink to="/settings" title="设置">
            <IconSettings size={16} className="nav-icon" />
            <span className="nav-text">设置</span>
          </NavLink>
          <button
            className="theme-toggle"
            onClick={toggleTheme}
            aria-label={theme === 'dark' ? '切换到亮色主题' : '切换到暗色主题'}
            title={theme === 'dark' ? '切换到亮色主题' : '切换到暗色主题'}
          >
            {theme === 'dark' ? <IconSun size={17} /> : <IconMoon size={17} />}
          </button>
        </div>
      </nav>
      <main className="main">
        <Outlet />
      </main>
      <footer className="footer">
        Animeko Web · 基于 animeko (AGPL-3.0) 架构的服务端化实现 · 数据与缓存仅存于本容器
      </footer>
    </div>
  )
}

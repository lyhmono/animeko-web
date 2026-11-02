import { useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import ErrorBoundary from './ErrorBoundary'
import {
  getSyncState,
  onSyncChange,
  getTheme,
  setTheme,
  syncThemeColor,
  type SyncState,
  type Theme,
} from '../lib/storage'
import { rememberedUser } from '../lib/auth'
import {
  IconCompass,
  IconHeart,
  IconHome,
  IconLayers,
  IconLogout,
  IconMoon,
  IconSettings,
  IconSun,
} from '../lib/icons'

const SYNC_TEXT: Record<SyncState['status'], string> = {
  ready: '已保存',
  pending: '有改动待保存',
  saving: '保存中…',
  error: '保存失败',
}

/** 读取失败与保存失败必须分开说：前者意味着写入已被禁止（防止空文档覆盖服务器数据），
 *  喊「保存失败」会让人以为多点几次就能救回来 */
function syncText(state: SyncState): string {
  if (state.status === 'error' && state.kind === 'load') return '数据未同步'
  return SYNC_TEXT[state.status]
}

function NavItems() {
  return (
    <>
      <NavLink to="/" end title="首页" data-testid="nav-home">
        <IconHome size={18} className="nav-icon" />
        <span className="nav-text">首页</span>
      </NavLink>
      <NavLink to="/discover" data-testid="nav-discover" title="发现">
        <IconCompass size={18} className="nav-icon" />
        <span className="nav-text">发现</span>
      </NavLink>
      <NavLink to="/favorites" data-testid="nav-favorites" title="收藏">
        <IconHeart size={18} className="nav-icon" />
        <span className="nav-text">收藏</span>
      </NavLink>
      <NavLink to="/sources" data-testid="nav-sources" title="数据源">
        <IconLayers size={18} className="nav-icon" />
        <span className="nav-text">数据源</span>
      </NavLink>
      <NavLink to="/settings" data-testid="nav-settings" title="设置">
        <IconSettings size={18} className="nav-icon" />
        <span className="nav-text">设置</span>
      </NavLink>
    </>
  )
}

export default function Layout() {
  const [theme, setThemeState] = useState<Theme>(getTheme)
  const [sync, setSyncState] = useState<SyncState>(getSyncState)
  const user = rememberedUser()
  const navigate = useNavigate()
  const location = useLocation()
  // 播放页要隐藏手机端的底部 tab：它固定在视口底部，会正好压住进度条与手势区
  const isPlayer = location.pathname === '/play'
  const [term, setTerm] = useState(() => new URLSearchParams(location.search).get('q') ?? '')
  useEffect(() => {
    setTerm(new URLSearchParams(location.search).get('q') ?? '')
  }, [location.search])

  // 个人数据是「写内存 + 去抖回传服务端」的，界面必须能说出到底存上了没有
  useEffect(() => onSyncChange(setSyncState), [])

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
    // 必须同步 DOM 属性：CSS 双主题由 :root[data-theme] 驱动
    document.documentElement.dataset.theme = next
    syncThemeColor(next)
    window.dispatchEvent(new CustomEvent<Theme>('animeko-theme-change', { detail: next }))
  }

  return (
    <div className={`app${isPlayer ? ' app-player' : ''}`}>
      {/* 桌面左侧媒体库导航；移动端它退化为底部 tab（样式见 styles-plex.css）。
          所有 testid 与文案保持原样，避免重做视觉时把功能测试一起重做。 */}
      <aside className="sidebar" data-testid="sidebar">
        <NavLink to="/" data-testid="nav-logo" className="nav-logo">
          <span className="dot" />
          Animeko Web
        </NavLink>
        <div className="nav-tabs" data-testid="nav-tabs">
          <NavItems />
        </div>
        <div className="sidebar-bottom">
          <div className="nav-actions" data-testid="nav-actions">
            <button
              className="theme-toggle"
              onClick={toggleTheme}
              aria-label={theme === 'dark' ? '切换到亮色主题' : '切换到暗色主题'}
              title={theme === 'dark' ? '切换到亮色主题' : '切换到暗色主题'}
            >
              {theme === 'dark' ? <IconSun size={17} /> : <IconMoon size={17} />}
            </button>
            <button
              className="theme-toggle"
              onClick={() => window.dispatchEvent(new Event('animeko-sign-out'))}
              aria-label="退出登录"
              title="退出登录"
            >
              <IconLogout size={17} />
            </button>
          </div>
        </div>
      </aside>

      <div className="app-body">
        <header className="topbar">
          <form
            className="nav-search"
            data-testid="nav-search"
            role="search"
            onSubmit={(e) => {
              e.preventDefault()
              const q = term.trim()
              if (q) navigate(`/search?q=${encodeURIComponent(q)}`)
            }}
          >
            <input
              type="search"
              aria-label="搜索番剧名称"
              placeholder="搜索番剧、数据源或 Bangumi 条目"
              value={term}
              onChange={(e) => setTerm(e.target.value)}
            />
          </form>
          <span
            className={`sync-pill ${sync.status}`}
            role="status"
            title={sync.error || sync.note || syncText(sync)}
          >
            {syncText(sync)}
          </span>
        </header>

        <main className="main" data-testid="main">
          {/* 页面级错误边界：App 顶层那个会把导航一起替换掉，
              一个页面崩了用户就得整页刷新才能去别处。这层让崩溃只影响内容区。 */}
          <ErrorBoundary resetKey={location.pathname + location.search}>
            <Outlet />
          </ErrorBoundary>
        </main>
        <footer className="footer">
          <span className="footer-left">
            Animeko Web · 基于 animeko (AGPL-3.0) 架构的服务端化实现 · 收藏与进度保存在服务器
          </span>
          <span className="footer-right">{user && <span className="footer-user">{user}</span>}</span>
        </footer>
      </div>
    </div>
  )
}
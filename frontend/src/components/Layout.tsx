import { useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
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

export default function Layout() {
  const [theme, setThemeState] = useState<Theme>(getTheme)
  const [sync, setSyncState] = useState<SyncState>(getSyncState)
  const user = rememberedUser()
  const navigate = useNavigate()
  const location = useLocation()
  // 顶栏搜索框：之前搜索框只在首页，进到 /search 之后想换个关键词必须回首页，
  // 而浏览器地址栏里就是搜索结果 —— 实测用户会以为「不能改搜索词」。
  const [term, setTerm] = useState(() => new URLSearchParams(location.search).get('q') ?? '')
  useEffect(() => {
    setTerm(new URLSearchParams(location.search).get('q') ?? '')
  }, [location.search])

  // 个人数据是「写内存 + 去抖回传服务端」的，界面必须能说出到底存上了没有，
  // 否则用户只能靠「收藏还在不在」来猜网络状况
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
    // 必须同步 DOM 属性：CSS 双主题由 :root[data-theme] 驱动，
    // 仅写 localStorage/state 不会让当前页面即时换肤
    document.documentElement.dataset.theme = next
    syncThemeColor(next)
    // 广播给设置页等其它订阅者，否则那里的暗色/亮色按钮还停在旧值上。
    // 带上 detail，同时也允许订阅方直接回读 getTheme()。
    window.dispatchEvent(new CustomEvent<Theme>('animeko-theme-change', { detail: next }))
  }

  return (
    <div className="app">
      <nav className="nav">
        <NavLink to="/" className="nav-logo">
          <span className="dot" />
          Animeko Web
        </NavLink>
        <form
          className="nav-search"
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
            placeholder="搜索番剧"
            value={term}
            onChange={(e) => setTerm(e.target.value)}
          />
        </form>
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
          <button
            className="theme-toggle"
            onClick={() => window.dispatchEvent(new Event('animeko-sign-out'))}
            aria-label="退出登录"
            title="退出登录"
          >
            <IconLogout size={17} />
          </button>
        </div>
      </nav>
      <main className="main">
        <Outlet />
      </main>
      <footer className="footer">
        <span className="footer-left">
          Animeko Web · 基于 animeko (AGPL-3.0) 架构的服务端化实现 · 收藏与进度保存在服务器
        </span>
        <span className="footer-right">
          {user && <span className="footer-user">{user}</span>}
          {/* 保存失败要点得出原因，否则「收藏不见了」这种问题根本没法排查 */}
          <span className={`sync-pill ${sync.status}`} title={sync.error || syncText(sync)}>
            {syncText(sync)}
          </span>
        </span>
      </footer>
    </div>
  )
}

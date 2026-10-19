import { useCallback, useEffect, useRef, useState } from 'react'
import { lazy, Suspense } from 'react'
import { Route, Routes } from 'react-router-dom'
import Layout from './components/Layout'
import ErrorBoundary from './components/ErrorBoundary'
import LoginPage from './pages/LoginPage'
import HomePage from './pages/HomePage'
import { errMsg } from './api'
import { onSessionLost, probeSession, signOut } from './lib/auth'
import {
  flushUserData,
  getSyncState,
  loadUserData,
  markUserDataStale,
  onSyncChange,
} from './lib/storage'

// 路由级代码分割：播放器（含 hls.js）等重依赖只随对应路由按需加载，
// 首页首包不再携带 hls.js 与全部页面代码
const SearchPage = lazy(() => import('./pages/SearchPage'))
const SubjectPage = lazy(() => import('./pages/SubjectPage'))
const SourceDetailPage = lazy(() => import('./pages/SourceDetailPage'))
const PlayerPage = lazy(() => import('./pages/PlayerPage'))
const SourcesPage = lazy(() => import('./pages/SourcesPage'))
const FavoritesPage = lazy(() => import('./pages/FavoritesPage'))
const DiscoverPage = lazy(() => import('./pages/DiscoverPage'))
const SettingsPage = lazy(() => import('./pages/SettingsPage'))
const NotFoundPage = lazy(() => import('./pages/NotFoundPage'))

type Boot =
  | { stage: 'loading' }
  | { stage: 'login' }
  | { stage: 'ready' }
  | { stage: 'error'; error: string }

export default function App() {
  const [boot, setBoot] = useState<Boot>({ stage: 'loading' })
  // 迟到的 await 不能把界面状态盖回去：登录成功、会话失效、重试都可能在
  // 上一次 enter() 还没跑完时就改了状态（真实症状是「登录页一闪又进了主界面」）
  const generation = useRef(0)

  // 先问服务端要不要登录，再把个人数据整份拉回内存。
  // 顺序不能反：未登录时 /api/data 是 401，而数据层在没读到文档时禁止回写；
  // 更早的实现会把「读失败」当成「文档是空的」，于是任何一次收藏点击都会
  // 把空文档 PUT 上去，服务器上的收藏当场清零。
  const enter = useCallback(async () => {
    const my = ++generation.current
    const fresh = (next: Boot) => {
      if (generation.current === my) setBoot(next)
    }
    fresh({ stage: 'loading' })
    try {
      const session = await probeSession()
      if (session.authEnabled && !session.authenticated) {
        fresh({ stage: 'login' })
        return
      }
      await loadUserData()
      // 读取失败也照样进主界面（浏览与搜索不需要同步），
      // 但写入已被数据层禁止，横幅会明说并给「重新读取」
      fresh({ stage: 'ready' })
    } catch (e) {
      fresh({ stage: 'error', error: errMsg(e) })
    }
  }, [])

  useEffect(() => {
    void enter()
  }, [enter])

  // 会话在服务端失效（重启容器、点了注销、30 天没用完）后任何请求都会撞 401，
  // 这时必须回到登录页而不是让界面停在半死不活的状态
  useEffect(
    () =>
      onSessionLost(() => {
        generation.current++
        markUserDataStale()
        setBoot({ stage: 'login' })
      }),
    [],
  )

  // 退出登录：先把待写数据收尾，再作废服务端会话
  useEffect(() => {
    const leave = async () => {
      generation.current++
      await flushUserData()
      await signOut()
      markUserDataStale()
      setBoot({ stage: 'login' })
    }
    window.addEventListener('animeko-sign-out', leave)
    return () => window.removeEventListener('animeko-sign-out', leave)
  }, [])

  if (boot.stage === 'loading') return <div className="boot-screen">正在连接服务器…</div>
  if (boot.stage === 'error') {
    return (
      <div className="boot-screen">
        <div className="card boot-card">
          <h2 className="card-title">连不上这台服务</h2>
          <p className="card-hint">{boot.error}</p>
          <div className="boot-actions">
            <button className="btn" onClick={() => void enter()}>
              重试
            </button>
          </div>
        </div>
      </div>
    )
  }
  if (boot.stage === 'login') return <LoginPage onSuccess={() => void enter()} />

  return (
    <ErrorBoundary>
      <Suspense fallback={<div className="loading">页面加载中…</div>}>
        <LoadFailureBanner />
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<HomePage />} />
            <Route path="/search" element={<SearchPage />} />
            <Route path="/subject/:id" element={<SubjectPage />} />
            <Route path="/source/:name" element={<SourceDetailPage />} />
            <Route path="/play" element={<PlayerPage />} />
            <Route path="/sources" element={<SourcesPage />} />
            <Route path="/discover" element={<DiscoverPage />} />
            <Route path="/favorites" element={<FavoritesPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Route>
        </Routes>
      </Suspense>
    </ErrorBoundary>
  )
}

/**
 * 个人数据读取/保存失败的公告条。
 * 这一层存在的意义：读取失败时写入是被禁止的（防止空文档覆盖服务器数据），
 * 那种「点了收藏但什么都没发生」必须由界面明说，而不是让用户猜。
 */
function LoadFailureBanner() {
  const [state, setState] = useState(getSyncState)
  useEffect(() => onSyncChange(setState), [])
  if (state.status !== 'error') return null
  return (
    <div className="error-box banner" role="alert">
      <span>{state.error}</span>
      <button
        className="btn btn-secondary btn-sm"
        onClick={() => void (state.kind === 'load' ? loadUserData() : flushUserData())}
      >
        {state.kind === 'load' ? '重新读取' : '重试保存'}
      </button>
    </div>
  )
}

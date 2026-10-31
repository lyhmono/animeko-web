import { useCallback, useEffect, useRef, useState } from 'react'
import { lazy, Suspense } from 'react'
import { Route, Routes } from 'react-router-dom'
import Layout from './components/Layout'
import ErrorBoundary from './components/ErrorBoundary'
import LoginPage from './pages/LoginPage'
import HomePage from './pages/HomePage'
import { errMsg } from './api'
import {
  invalidateSessionIdentity, onSessionLost, probeSession, rememberedUser, rememberUser, signOut, verifiedSessionUser,
} from './lib/auth'
import {
  discardRetainedUserData,
  exportPendingUserData,
  exportRetainedUserData,
  exportUserData,
  flushUserData,
  getSyncState,
  isUserDataLoaded,
  loadUserData,
  listRetainedUserData,
  markUserDataStale,
  onSyncChange,
  restoreRetainedUserData,
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
  | { stage: 'leaving' }
  | { stage: 'error'; error: string }
  | { stage: 'leave-error'; phase: 'save' | 'logout'; error: string }

export default function App() {
  const [boot, setBoot] = useState<Boot>({ stage: 'loading' })
  const [recoveryMessage, setRecoveryMessage] = useState('')
  // 迟到的 await 不能把界面状态盖回去：登录成功、会话失效、重试都可能在
  // 上一次 enter() 还没跑完时就改了状态（真实症状是「登录页一闪又进了主界面」）
  const generation = useRef(0)
  const leaving = useRef(false)
  const operation = useRef<AbortController | null>(null)

  // 先问服务端要不要登录，再把个人数据整份拉回内存。
  // 顺序不能反：未登录时 /api/data 是 401，而数据层在没读到文档时禁止回写；
  // 更早的实现会把「读失败」当成「文档是空的」，于是任何一次收藏点击都会
  // 把空文档 PUT 上去，服务器上的收藏当场清零。
  const enter = useCallback(async () => {
    const my = ++generation.current
    operation.current?.abort()
    const controller = new AbortController()
    operation.current = controller
    leaving.current = false
    const fresh = (next: Boot) => {
      if (generation.current === my) setBoot(next)
    }
    fresh({ stage: 'loading' })
    try {
      const session = await probeSession(controller.signal)
      if (generation.current !== my) return
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
    return () => {
      generation.current++
      operation.current?.abort()
      leaving.current = false
    }
  }, [enter])

  // 会话在服务端失效（重启容器、点了注销、30 天没用完）后任何请求都会撞 401，
  // 这时必须回到登录页而不是让界面停在半死不活的状态
  useEffect(
    () =>
      onSessionLost(() => {
        generation.current++
        operation.current?.abort()
        leaving.current = false
        const owner = verifiedSessionUser() || rememberedUser()
        invalidateSessionIdentity()
        markUserDataStale({ preservePending: true, owner })
        setBoot({ stage: 'login' })
      }),
    [],
  )

  // flush resolve 并不代表已保存，失败时先停在可恢复的退出错误页。
  const leave = useCallback(async (discard = false) => {
    if (leaving.current) return
    leaving.current = true
    const my = ++generation.current
    operation.current?.abort()
    const controller = new AbortController()
    operation.current = controller
    setBoot({ stage: 'leaving' })
    const owner = verifiedSessionUser() || rememberedUser()
    let phase: 'save' | 'logout' = 'save'
    try {
      if (discard) {
        // 先隔离待写副本并推进数据代次，不能等注销成功才停止旧重试。
        // 已发出的请求可能已被服务端处理；取消只阻止后续轮次与迟到回调。
        markUserDataStale({ preservePending: true, owner })
        setRecoveryMessage('已停止自动上传未同步改动；副本仅留在本标签页，只有明确恢复才会再次上传。已发出的请求无法撤销。')
      } else {
        if (!isUserDataLoaded()) {
          await loadUserData()
          if (generation.current !== my) return
        }
        const saved = await flushUserData()
        if (generation.current !== my) return
        if (!saved.ok && saved.pending) {
          if (saved.reason === 'session-changed') return
          setBoot({ stage: 'leave-error', phase, error: saved.error })
          return
        }
      }
      phase = 'logout'
      let failure = ''
      try {
        await signOut(controller.signal)
      } catch (e) {
        failure = errMsg(e)
      }
      if (generation.current !== my) return
      // 请求失败可能只是 ACK 丢失；成功返回也不能替代实际会话回读。
      const after = await probeSession(controller.signal).catch(() => null)
      if (generation.current !== my) return
      if (after && !after.authEnabled) {
        rememberUser('')
        invalidateSessionIdentity()
        markUserDataStale({ preservePending: !discard, owner })
        await enter()
        return
      }
      if (after && !after.authenticated) {
        rememberUser('')
        invalidateSessionIdentity()
        markUserDataStale({ preservePending: !discard, owner })
        setBoot({ stage: 'login' })
        return
      }
      setBoot({
        stage: 'leave-error', phase,
        error: failure || (after ? '注销后会话仍有效，请重试退出' : '无法确认注销结果，请重试；个人数据仍保留在本机'),
      })
    } catch (e) {
      if (generation.current === my) setBoot({ stage: 'leave-error', phase, error: errMsg(e) })
    } finally {
      if (generation.current === my) leaving.current = false
    }
  }, [enter])
  useEffect(() => {
    const onLeave = () => { void leave() }
    window.addEventListener('animeko-sign-out', onLeave)
    return () => window.removeEventListener('animeko-sign-out', onLeave)
  }, [leave])

  const retained = listRetainedUserData()
  const recoveryPanel = retained.length > 0 || recoveryMessage ? (
    <section className="error-box banner" aria-label="未同步副本恢复">
      {retained.length > 0 && <p>未同步改动已暂存在当前标签页内存。刷新或关页会丢失，请先导出；登录原账号后可明确选择恢复。</p>}
      {retained.map((entry) => (
        <div key={entry.id}>
          <span>原账号：{entry.owner || '身份未确认'}；{entry.complete ? '完整本机副本' : '局部改动副本，不是完整备份'}</span>
          <button className="btn btn-secondary btn-sm" onClick={() => {
            try { downloadBackup(exportRetainedUserData(entry.id), `animeko-unsynced-${entry.id}.json`) }
            catch (e) { setRecoveryMessage(errMsg(e)) }
          }}>导出未同步副本</button>
          <button className="btn btn-secondary btn-sm"
            disabled={boot.stage !== 'ready' || !entry.owner || entry.owner !== verifiedSessionUser() || entry.origin !== location.origin}
            onClick={() => { void (async () => {
              const my = generation.current
              try {
                restoreRetainedUserData(entry.id, verifiedSessionUser())
                const saved = await flushUserData()
                if (generation.current !== my) return
                setRecoveryMessage(saved.ok ? '未同步改动已恢复并保存到服务器' : `改动已恢复到本机，但尚未保存：${saved.error}`)
              } catch (e) {
                if (generation.current === my) setRecoveryMessage(errMsg(e))
              }
            })() }}
          >恢复未同步改动</button>
          <button className="btn btn-secondary btn-sm" onClick={() => {
            if (window.confirm('确认放弃这份保留的未同步副本？此操作无法恢复。')) {
              discardRetainedUserData(entry.id)
              setRecoveryMessage('已按确认放弃该副本')
            }
          }}>放弃该副本</button>
        </div>
      ))}
      {recoveryMessage && <p role="status">{recoveryMessage}</p>}
    </section>
  ) : null

  if (boot.stage === 'loading') return <div className="boot-screen">正在连接服务器…</div>
  if (boot.stage === 'leaving') return <div className="boot-screen" role="status" aria-live="polite">正在保存个人数据并退出…</div>
  if (boot.stage === 'leave-error') {
    return (
      <div className="boot-screen">
        <div className="card boot-card" role="alert">
          <h2 className="card-title">退出未完成，个人数据仍保留在本机</h2>
          <p className="card-hint">{boot.error}</p>
          <p className="card-hint">请先重试或导出备份，不要刷新或关闭这个标签页。</p>
          {!isUserDataLoaded() && boot.phase === 'save' && <p className="card-hint">当前仅有局部改动副本，不是完整备份；导出的操作记录不能直接作为完整备份导入。</p>}
          <div className="boot-actions">
            <button className="btn" onClick={() => void leave()}>重试保存并退出</button>
            {(isUserDataLoaded() || boot.phase === 'save') && <button className="btn btn-secondary" onClick={() => {
              try {
                if (isUserDataLoaded()) downloadBackup(exportUserData(), 'animeko-unsynced-backup.json')
                else {
                  downloadBackup(exportPendingUserData(), 'animeko-unsynced-partial.json')
                  setRecoveryMessage('已导出局部未同步副本，不是完整备份；本机改动仍保留，可继续重试读取并保存。')
                }
              } catch (e) {
                setBoot({ ...boot, error: errMsg(e) })
              }
            }}>{isUserDataLoaded() ? '导出备份' : '导出局部未同步副本'}</button>}
            {boot.phase === 'save' && <button className="btn btn-secondary" onClick={() => {
              if (window.confirm('确认停止上传未同步改动并退出？副本只保留在本标签页，不会自动恢复；已发出的请求无法撤销。')) void leave(true)
            }}>放弃未同步改动并退出</button>}
            <button className="btn btn-secondary" onClick={() => {
              generation.current++
              operation.current?.abort()
              leaving.current = false
              // 放弃后已清除可写基准：返回必须重读，不能恢复旧队列或写空文档。
              if (!isUserDataLoaded()) void enter()
              else setBoot({ stage: 'ready' })
            }}>返回站点</button>
          </div>
        </div>
        {recoveryPanel}
      </div>
    )
  }
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
  // 登录屏自己也要有兜底：它一崩，用户面对的是真正的白屏，
  // 连"输了口令点进入"这个动作都没有
  if (boot.stage === 'login') {
    return (
      <ErrorBoundary>
        {recoveryPanel}
        <LoginPage onSuccess={() => void enter()} />
      </ErrorBoundary>
    )
  }

  return (
    <ErrorBoundary>
      {recoveryPanel}
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

function downloadBackup(text: string, name: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }))
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = name
    link.click()
  } finally {
    URL.revokeObjectURL(url)
  }
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

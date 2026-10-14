import { lazy, Suspense } from 'react'
import { Route, Routes } from 'react-router-dom'
import Layout from './components/Layout'
import ErrorBoundary from './components/ErrorBoundary'
import HomePage from './pages/HomePage'
// 路由级代码分割：播放器（含 hls.js）等重依赖只随对应路由按需加载，
// 首页首包不再携带 hls.js 与全部页面代码
const SearchPage = lazy(() => import('./pages/SearchPage'))
const SubjectPage = lazy(() => import('./pages/SubjectPage'))
const SourceDetailPage = lazy(() => import('./pages/SourceDetailPage'))
const PlayerPage = lazy(() => import('./pages/PlayerPage'))
const SourcesPage = lazy(() => import('./pages/SourcesPage'))
const FavoritesPage = lazy(() => import('./pages/FavoritesPage'))
const SettingsPage = lazy(() => import('./pages/SettingsPage'))
const NotFoundPage = lazy(() => import('./pages/NotFoundPage'))

export default function App() {
  return (
    <ErrorBoundary>
      <Suspense fallback={<div className="loading">页面加载中…</div>}>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<HomePage />} />
            <Route path="/search" element={<SearchPage />} />
            <Route path="/subject/:id" element={<SubjectPage />} />
            <Route path="/source/:name" element={<SourceDetailPage />} />
            <Route path="/play" element={<PlayerPage />} />
            <Route path="/sources" element={<SourcesPage />} />
            <Route path="/favorites" element={<FavoritesPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Route>
        </Routes>
      </Suspense>
    </ErrorBoundary>
  )
}

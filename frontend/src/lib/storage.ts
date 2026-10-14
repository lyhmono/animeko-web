/**
 * 本地存储层：播放进度 / 追番收藏 / 搜索历史 / 最近观看。
 * 全部数据仅存浏览器 localStorage（key 前缀 animeko-web:），不上传服务端。
 * 所有读取均容错：JSON 损坏或字段缺失时回退默认值，绝不让坏数据阻断页面。
 */

const PREFIX = 'animeko-web:'

function readRaw(key: string): string | null {
  try {
    return localStorage.getItem(PREFIX + key)
  } catch {
    return null
  }
}

function writeRaw(key: string, value: string): void {
  try {
    localStorage.setItem(PREFIX + key, value)
  } catch {
    // 存储不可用（隐私模式/超额）时静默失败
  }
}

function removeRaw(key: string): void {
  try {
    localStorage.removeItem(PREFIX + key)
  } catch {
    /* ignore */
  }
}

function readJson<T>(key: string, fallback: T): T {
  const raw = readRaw(key)
  if (raw == null) return fallback
  try {
    return JSON.parse(raw) as T
  } catch {
    return fallback
  }
}

function writeJson(key: string, value: unknown): void {
  writeRaw(key, JSON.stringify(value))
}

// ---------- 主题 ----------

export type Theme = 'dark' | 'light'

/** 各主题对应的浏览器 UI 色（<meta name="theme-color">），与 styles.css 的 --bg 保持一致 */
const THEME_COLORS: Record<Theme, string> = { dark: '#0f1115', light: '#f5f6fa' }

/** 同步 address bar / PWA 主题色，避免亮色主题下浏览器 UI 仍是暗色 */
export function syncThemeColor(theme: Theme): void {
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLORS[theme])
}

export function getTheme(): Theme {
  return readRaw('theme') === 'light' ? 'light' : 'dark'
}

export function setTheme(theme: Theme): void {
  writeRaw('theme', theme)
}

// ---------- 播放进度 ----------

export interface ProgressEntry {
  source: string
  playUrl: string
  title: string
  /** 已播放秒数 */
  time: number
  /** 总时长秒数（0 表示未知） */
  duration: number
  ts: number
}

export function progressKeyOf(source: string, playUrl: string): string {
  return `progress:${source}:${playUrl}`
}

export function getProgress(source: string, playUrl: string): ProgressEntry | null {
  const entry = readJson<ProgressEntry | null>(progressKeyOf(source, playUrl), null)
  if (entry == null || typeof entry.time !== 'number' || !(entry.time > 5)) return null
  return entry
}

export function setProgress(entry: ProgressEntry): void {
  writeJson(progressKeyOf(entry.source, entry.playUrl), entry)
}

export function clearProgress(source: string, playUrl: string): void {
  removeRaw(progressKeyOf(source, playUrl))
}

// ---------- 追番收藏 ----------

export interface FavoriteSubject {
  id: number
  name: string
  name_cn: string
  cover: string
  date: string
  score: number | null
  ts: number
}

export function listFavorites(): FavoriteSubject[] {
  const list = readJson<FavoriteSubject[]>(`favorites`, [])
  if (!Array.isArray(list)) return []
  return list
    .filter((f) => f && typeof f.id === 'number')
    .sort((a, b) => (b.ts ?? 0) - (a.ts ?? 0))
}

export function isFavorite(id: number): boolean {
  return listFavorites().some((f) => f.id === id)
}

/** 收藏 ID 集合：列表页批量判断用，避免每条卡片全量解析 localStorage（O(n²)） */
export function favoriteIdSet(): Set<number> {
  return new Set(listFavorites().map((f) => f.id))
}

/** 切换收藏，返回操作后是否处于「已收藏」 */
export function toggleFavorite(subject: FavoriteSubject): boolean {
  const list = listFavorites()
  const exists = list.some((f) => f.id === subject.id)
  const next = exists ? list.filter((f) => f.id !== subject.id) : [subject, ...list]
  writeJson('favorites', next)
  return !exists
}

// ---------- 搜索历史 ----------

const HISTORY_LIMIT = 10

export function listSearchHistory(limit = HISTORY_LIMIT): string[] {
  const list = readJson<string[]>('searchHistory', [])
  if (!Array.isArray(list)) return []
  return list.filter((q) => typeof q === 'string' && q.trim()).slice(0, limit)
}

/** 写入搜索历史（去重、最新在前、上限 10 条） */
export function addSearchHistory(q: string): string[] {
  const keyword = q.trim()
  if (!keyword) return listSearchHistory()
  const next = [keyword, ...listSearchHistory().filter((h) => h !== keyword)].slice(0, HISTORY_LIMIT)
  writeJson('searchHistory', next)
  return next
}

export function clearSearchHistory(): void {
  removeRaw('searchHistory')
}

// ---------- 最近观看 ----------

export interface RecentItem {
  source: string
  playUrl: string
  title: string
  anime: string
  ep: string
  ts: number
}

const RECENT_LIMIT = 8

export function listRecent(limit = RECENT_LIMIT): RecentItem[] {
  const list = readJson<RecentItem[]>('recent', [])
  if (!Array.isArray(list)) return []
  return list
    .filter((r) => r && typeof r.playUrl === 'string' && r.playUrl.startsWith('http'))
    .slice(0, limit)
}

export function addRecent(item: RecentItem): RecentItem[] {
  const list = listRecent(RECENT_LIMIT).filter(
    (r) => !(r.source === item.source && r.playUrl === item.playUrl),
  )
  const next = [item, ...list].slice(0, RECENT_LIMIT)
  writeJson('recent', next)
  return next
}

export function clearRecent(): void {
  removeRaw('recent')
}

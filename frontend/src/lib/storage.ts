/**
 * 个人数据层：播放进度 / 追番收藏 / 搜索历史 / 最近观看。
 *
 * 数据存在服务端（{dataDir}/userdata.json），这样换设备、清缓存、开隐私模式都不会丢收藏。
 * 对外仍是一套同步函数：页面代码不该为了「读一次收藏」到处 await。
 * 实现是「启动时整份拉回内存 + 写入去抖回传」：
 *  - 读：命中内存，永远是同步的；
 *  - 写：立刻更新内存并记下改了哪些键，800ms 内多次写入合并成一次 PUT。
 *
 * 三条必须守住的底线（都是真实事故的形状）：
 *  1. 没读到服务端文档就绝不回写。否则一次读取失败 + 一次收藏点击
 *     就会把 `{favorites:[…]}` 整份覆盖上去，收藏与进度当场清空。
 *  2. 回写是条件写（If-Match 带读到的版本）。两个标签页/两台设备各自整份覆盖时，
 *     后写的会把先写的改动吃掉；版本不符就重读、把自己改过的键贴回去再写。
 *  3. 写失败必须保留待写状态并重试，界面也不能说「已保存」。
 */
import { api, ERR_DATA_STALE, errMsg, isApiError } from '../api'
import type { UserData } from '../types'

let doc: UserData = {}
let loaded = false
let baseEtag = ''

// ---------- 同步状态（给界面一个「到底存上了没有」的说法）----------

export type SyncStatus = 'ready' | 'pending' | 'saving' | 'error'

export interface SyncState {
  status: SyncStatus
  /** 出错的是读取还是写入：读取失败时界面要停下写入，而不是喊「保存失败」 */
  kind: 'load' | 'save' | null
  error: string
  /** 不是故障但该让用户知道的事（例如个人数据快撞到服务端上限） */
  note?: string
}

let sync: SyncState = { status: 'ready', kind: null, error: '', note: '' }

/** 服务端个人数据文档上限 512KB，到八成提醒一次 */
const DOC_SOFT_LIMIT = 0.8 * 512 * 1024
let sizeNote = ''
const syncListeners = new Set<(s: SyncState) => void>()

function setSync(next: SyncState): void {
  // note 为空时带上体积提醒：状态点每变一次都要重算，否则一次成功的保存
  // 就把「快满了」的提醒抹掉了，而那个提醒的意义正是「现在还能写，但快写不下了」
  sync = next.note || !sizeNote ? next : { ...next, note: sizeNote }
  syncListeners.forEach((l) => l(sync))
}

export function getSyncState(): SyncState {
  return sync
}

export function onSyncChange(listener: (s: SyncState) => void): () => void {
  syncListeners.add(listener)
  return () => syncListeners.delete(listener)
}

// ---------- 拉取 ----------

/**
 * 开机拉一次。失败时保持 loaded=false —— 这是「不许覆盖服务器数据」的唯一开关，
 * 所以它同时也是「现在不能写」的状态，界面要显示可重试的读取错误。
 */
export async function loadUserData(): Promise<void> {
  try {
    const remote = await api.loadDataWithEtag()
    doc = remote.doc
    baseEtag = remote.etag
    loaded = true
    pendingKeys.clear()
    pendingProgressKeys.clear()
    setSync({  status: 'ready', kind: null, error: ''  })
  } catch (e) {
    loaded = false
    doc = {}
    baseEtag = ''
    setSync({
      status: 'error',
      kind: 'load',
      error: `个人数据没读到，已暂停写入以免覆盖服务器上的收藏：${errMsg(e)}`,
    })
  }
}

// ---------- 回写 ----------

const DEBOUNCE_MS = 800
const RETRY_MS = 5000
/** 单个剧集一条记录，看久了会无限增长直到撞上限，所以留最新的一部分即可 */
const MAX_PROGRESS_ENTRIES = 400

const pendingKeys = new Set<string>()
/** progress 是一个大 map，整键覆盖会吃掉别的标签页刚写的其它剧集，所以细到条目级 */
const pendingProgressKeys = new Set<string>()

let timer: ReturnType<typeof setTimeout> | undefined
let running: Promise<void> | null = null

function scheduleFlush(key?: string, progressEntry?: string): void {
  if (key) pendingKeys.add(key)
  if (progressEntry) pendingProgressKeys.add(progressEntry)
  if (!loaded) return // 见文件头第 1 条：没有服务端文档就没有可覆盖的基准
  setSync({  status: 'pending', kind: null, error: ''  })
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => void flushUserData(), DEBOUNCE_MS)
}

/** 立即把内存里的改动推给服务端（去抖合并、版本比对、失败保留待写状态）。 */
export async function flushUserData(): Promise<void> {
  if (timer) {
    clearTimeout(timer)
    timer = undefined
  }
  if (!loaded) {
    if (sync.status !== 'error') {
      setSync({  status: 'error', kind: 'load', error: '还没读到服务器数据，写入已暂停'  })
    }
    return
  }
  // 正在写的那一轮会看到 pendingKeys，不用并发发第二个 PUT（并发 PUT 的到达顺序没有保证）
  if (running) {
    await running
    if (pendingKeys.size > 0) await flushUserData()
    return
  }
  const round = pushOnce().finally(() => {
    if (running === round) running = null
  })
  running = round
  await round
}

/**
 * 本轮真正发出去的**值**（按引用记）。
 *
 * 为什么需要：api.saveData 在调用那一刻就把 doc 序列化成了请求体，之后的改动
 * 只存在于内存里，不在这份请求体中。成功回来若无条件把这些键从 pendingKeys 删掉，
 * 「await 期间点的那两下」就被当成已同步 —— 界面显示收藏了两部，刷新后少一部。
 * 只有值仍然是发出去那个引用的键，才真的可以清掉。
 * （所有写入路径都是整体替换 `doc[key] = next`，所以引用比较足以判断「变过」。）
 */
interface Round {
  keys: string[]
  progressKeys: string[]
  sentValues: Map<string, unknown>
  sentProgress: Map<string, unknown>
}

function snapshotValues(round: Round): void {
  for (const key of round.keys) round.sentValues.set(key, doc[key])
  const progress = asMap(doc.progress)
  for (const entry of round.progressKeys) round.sentProgress.set(entry, progress[entry])
}

/** 发出去之后又被改过的顶层键 */
function changedKeys(round: Round): string[] {
  return round.keys.filter((key) => {
    if (key === 'progress') {
      const progress = asMap(doc.progress)
      return round.progressKeys.some((entry) => progress[entry] !== round.sentProgress.get(entry))
    }
    return doc[key] !== round.sentValues.get(key)
  })
}

/** 还欠着服务端一次写入：排一轮延迟推送（有在途请求时由 flushUserData 排队） */
function scheduleFollowUp(delay = DEBOUNCE_MS): void {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => void flushUserData(), delay)
}

async function pushOnce(): Promise<void> {
  let attempt = 0
  while (pendingKeys.size > 0 && attempt < 3) {
    attempt++
    const round: Round = {
      keys: [...pendingKeys],
      progressKeys: [...pendingProgressKeys],
      sentValues: new Map(),
      sentProgress: new Map(),
    }
    snapshotValues(round)
    setSync({  status: 'saving', kind: null, error: ''  })
    try {
      const saved = await api.saveData(doc, baseEtag)
      baseEtag = saved.etag
      // 服务端上限 512KB，撞到之后每次保存都是 400，而症状只是「收藏点不动」。
      // 提前到八成就说，用户还有机会自己清一清，而不是等写失败去猜原因
      sizeNote = saved.bytes > 0 && saved.bytes >= DOC_SOFT_LIMIT
        ? `个人数据已有 ${(saved.bytes / 1024).toFixed(0)}KB，接近服务端 512KB 上限，建议清理不再看的收藏与历史`
        : ''
      // 只清「发出去之后没再被动过」的键：await 期间新产生的改动必须留给下一轮
      const changed = changedKeys(round)
      const changedProgress = changed.includes('progress')
      round.keys.forEach((k) => { if (!changed.includes(k)) pendingKeys.delete(k) })
      round.progressKeys.forEach((k) => {
        if (asMap(doc.progress)[k] === round.sentProgress.get(k)) pendingProgressKeys.delete(k)
      })
      setSync({  status: 'ready', kind: null, error: ''  })
      // 确实有改动没被这份请求体带走 → 自己排下一轮，而不是等用户再点一次
      if (changed.length > 0 || changedProgress) scheduleFollowUp()
    } catch (e) {
      if (isApiError(e, 409, ERR_DATA_STALE)) {
        const merged = await mergeWithServer(round.keys, round.progressKeys)
        if (merged) continue // 换好基准，重试这一次写
        setSync({  status: 'error', kind: 'save', error: '本机改动与服务端冲突且无法合并，请刷新页面重试'  })
        return
      }
      // 超上限、格式错这类不会自己变好，别把浏览器变成打服务端的循环
      const fatal = isApiError(e, 400) || isApiError(e, 413)
      setSync({ status: 'error', kind: 'save', error: `保存失败：${errMsg(e)}` })
      if (!fatal) scheduleFollowUp(RETRY_MS)
      return
    }
  }
}

/**
 * 版本冲突：重读服务端文档，只把「本标签页改过的键」贴上去。
 * 这样另一台设备/另一个标签页的改动不会被整份覆盖，
 * 而我们自己的改动也不会丢。progress 再细一层到条目，避免互相吃掉别的剧集。
 */
async function mergeWithServer(keys: string[], progressKeys: string[]): Promise<boolean> {
  let fresh
  try {
    fresh = await api.loadDataWithEtag()
  } catch (e) {
    setSync({ status: 'error', kind: 'load', error: `冲突后重读失败：${errMsg(e)}` })
    return false
  }
  const merged: UserData = { ...fresh.doc }
  for (const key of keys) {
    if (key === 'progress') {
      const theirs = asMap(merged.progress)
      const mine = asMap(doc.progress)
      const out: Record<string, unknown> = { ...theirs }
      for (const entry of progressKeys) {
        if (entry in mine) out[entry] = mine[entry]
        else delete out[entry]
      }
      merged.progress = out
    } else if (Object.prototype.hasOwnProperty.call(doc, key)) {
      merged[key] = doc[key]
    } else {
      delete merged[key]
    }
  }
  doc = merged
  baseEtag = fresh.etag
  return true
}

// 关掉标签页 / 切后台时尽力把待写内容送出去（fetch 在页面卸载后可能被浏览器掐掉，
// 所以这只是「尽力」，真正的兜底是每次改动都会即时去抖回写）。
if (typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') void flushUserData()
  })
  window.addEventListener('pagehide', () => void flushUserData())
}

// ---------- 底层读写（键即文档字段）----------

export function readJson<T>(key: string, fallback: T): T {
  const value = doc[key]
  return value === undefined ? fallback : (value as T)
}

export function writeJson(key: string, value: unknown): void {
  doc[key] = value
  scheduleFlush(key)
}

function removeKey(key: string): void {
  if (!(key in doc)) return
  delete doc[key]
  scheduleFlush(key)
}

/** 服务端文档里没有 progress 时给一个空容器（返回的是活引用，调用方只读） */
function progressMap(): Record<string, unknown> {
  const raw = doc.progress
  return raw && typeof raw === 'object' && !Array.isArray(raw) ? (raw as Record<string, unknown>) : {}
}

function asMap(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {}
}

function writeProgressMap(map: Record<string, unknown>, entryKey: string): void {
  // 剪掉最久没动的条目：一条都不删的话，看够了集数就会撞上服务端 512KB 上限，
  // 之后每一次收藏/进度写入都会 400，等于整个数据层从此写不进去
  const entries = Object.entries(map).filter(([, v]) => asMap(v).ts != null)
  if (entries.length > MAX_PROGRESS_ENTRIES) {
    entries.sort((a, b) => (asMap(b[1]).ts as number) - (asMap(a[1]).ts as number))
    const kept: Record<string, unknown> = {}
    entries.slice(0, MAX_PROGRESS_ENTRIES).forEach(([k, v]) => {
      kept[k] = v
    })
    map = kept
  }
  doc.progress = map
  scheduleFlush('progress', entryKey)
}

// ---------- 主题（设备偏好，不进服务端文档）----------

export type Theme = 'dark' | 'light'

const THEME_COLORS: Record<Theme, string> = { dark: '#0f1115', light: '#f5f6fa' }

/**
 * 主题只存本机：它是「这台设备看着舒服与否」的偏好，不是收藏。
 * 放在 localStorage 还有一次必须在渲染前读到（否则首屏会闪一下错误配色）。
 */
const THEME_KEY = 'animeko-web:theme'

function localRead(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function localWrite(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    // 隐私模式/超额时静默失败：主题丢了不影响功能
  }
}

/** 同步 address bar / PWA 主题色，避免亮色主题下浏览器 UI 仍是暗色 */
export function syncThemeColor(theme: Theme): void {
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLORS[theme])
}

export function getTheme(): Theme {
  return localRead(THEME_KEY) === 'light' ? 'light' : 'dark'
}

export function setTheme(theme: Theme): void {
  localWrite(THEME_KEY, theme)
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

function progressKeyOf(source: string, playUrl: string): string {
  return `${source}:${playUrl}`
}

export function getProgress(source: string, playUrl: string): ProgressEntry | null {
  const entry = asMap(progressMap())[progressKeyOf(source, playUrl)] as ProgressEntry | undefined
  if (entry == null || typeof entry.time !== 'number' || !(entry.time > 5)) return null
  // 返回副本：调用方拿到文档里的活对象一改就绕过 flush，改动永远到不了服务器
  return { ...entry }
}

export function setProgress(entry: ProgressEntry): void {
  const key = progressKeyOf(entry.source, entry.playUrl)
  const map = { ...progressMap(), [key]: entry }
  writeProgressMap(map, key)
}

export function clearProgress(source: string, playUrl: string): void {
  const key = progressKeyOf(source, playUrl)
  const map = progressMap()
  if (!(key in map)) return
  const next = { ...map }
  delete next[key]
  writeProgressMap(next, key)
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
  const list = readJson<FavoriteSubject[]>('favorites', [])
  if (!Array.isArray(list)) return []
  // 逐字段归一化：文档可能来自旧版本、别的客户端，或被手工改过。
  // 只校验 id 的话，score 是字符串就会让 FavoritesPage 抛 toFixed is not a function，
  // 而唯一的错误边界在 <Routes> 之上，于是整站变成错误页。
  return list
    .filter((f) => f && typeof f.id === 'number')
    .map((f) => ({
      id: f.id,
      name: String(f.name ?? ''),
      name_cn: String(f.name_cn ?? ''),
      cover: String(f.cover ?? ''),
      date: String(f.date ?? ''),
      score: typeof f.score === 'number' ? f.score : null,
      ts: typeof f.ts === 'number' ? f.ts : 0,
    }))
    .sort((a, b) => b.ts - a.ts)
}

export function isFavorite(id: number): boolean {
  return listFavorites().some((f) => f.id === id)
}

/** 收藏 ID 集合：列表页批量判断用，避免每张卡片都重新解析一遍文档（O(n²)） */
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

// ---------- 数据源收藏 ----------

/**
 * 从「数据源搜索结果」进来的条目没有 Bangumi id，只能用 (源, 地址) 当键。
 * 单独存一个键而不是混进 favorites：两种形状的归一化规则不同，
 * 混在一起时 listFavorites 的 `typeof id === 'number'` 过滤会静默吃掉整批数据源收藏。
 */
export interface SourceFavorite {
  source: string
  url: string
  name: string
  cover: string
  ts: number
}

const SOURCE_FAV_KEY = 'favoritesSource'

function sourceFavKeyOf(source: string, url: string): string {
  return `${source}|${url}`
}

export function listSourceFavorites(): SourceFavorite[] {
  const list = readJson<SourceFavorite[]>(SOURCE_FAV_KEY, [])
  if (!Array.isArray(list)) return []
  return list
    .filter((f) => f && typeof f.url === 'string' && typeof f.source === 'string')
    .map((f) => ({
      source: f.source,
      url: f.url,
      name: String(f.name ?? ''),
      cover: String(f.cover ?? ''),
      ts: typeof f.ts === 'number' ? f.ts : 0,
    }))
    .sort((a, b) => b.ts - a.ts)
}

export function isSourceFavorite(source: string, url: string): boolean {
  return listSourceFavorites().some((f) => sourceFavKeyOf(f.source, f.url) === sourceFavKeyOf(source, url))
}

/** 切换数据源收藏，返回操作后是否处于「已收藏」 */
export function toggleSourceFavorite(item: SourceFavorite): boolean {
  const key = sourceFavKeyOf(item.source, item.url)
  const list = listSourceFavorites()
  const exists = list.some((f) => sourceFavKeyOf(f.source, f.url) === key)
  const next = exists ? list.filter((f) => sourceFavKeyOf(f.source, f.url) !== key) : [item, ...list]
  writeJson(SOURCE_FAV_KEY, next)
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
  removeKey('searchHistory')
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
    .map((r) => ({
      source: String(r.source ?? ''),
      playUrl: r.playUrl,
      title: String(r.title ?? ''),
      anime: String(r.anime ?? ''),
      ep: String(r.ep ?? ''),
      ts: typeof r.ts === 'number' ? r.ts : 0,
    }))
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

/**
 * 丢掉本机文档副本（注销、或服务端说会话已失效）。
 * 之后任何写入都只会改内存，直到 loadUserData 重新拉到属于新会话的文档为止 ——
 * 否则「用旧会话的残档去覆盖新会话的数据」这种事真的会发生。
 */
export function markUserDataStale(): void {
  if (timer) {
    clearTimeout(timer)
    timer = undefined
  }
  pendingKeys.clear()
  pendingProgressKeys.clear()
  loaded = false
  doc = {}
  baseEtag = ''
  setSync({  status: 'ready', kind: null, error: ''  })
}

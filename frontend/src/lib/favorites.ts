import { readJson, writeFavorites, writeSourceFavorites } from './sync'

/**
 * 追番状态。用字符串而不是数字：文档是 JSON、还可能被手改，
 * 数字枚举在换版本时会静默错位（旧版 1=想看、新版 1=在看），字符串不会。
 */
export type FavStatus = 'wish' | 'watching' | 'done' | 'onhold'

export const FAV_STATUS_LABEL: Record<FavStatus, string> = {
  wish: '想看',
  watching: '在看',
  done: '看过',
  onhold: '搁置',
}

export interface FavoriteSubject {
  id: number
  name: string
  name_cn: string
  cover: string
  date: string
  score: number | null
  ts: number
  /** 追番状态。旧数据没有这个字段，归一化时补成「想看」 */
  status: FavStatus
}

/**
 * 单条收藏的归一化。抽成独立函数是因为它有两个调用方：
 * 读文档（listFavorites）与导入备份（importUserData）——
 * 备份文件同样可能来自旧版本或被手工改过，两边必须用同一套规则。
 */
export function toFavorite(raw: unknown): FavoriteSubject | null {
  const f = raw as Partial<FavoriteSubject> | null
  if (!f || typeof f.id !== 'number') return null
  return {
    id: f.id,
    name: String(f.name ?? ''),
    name_cn: String(f.name_cn ?? ''),
    cover: String(f.cover ?? ''),
    date: String(f.date ?? ''),
    score: typeof f.score === 'number' ? f.score : null,
    ts: typeof f.ts === 'number' ? f.ts : 0,
    // 未知值一律落到「想看」：宁可归错一档，也不让一条收藏因为脏字段从列表里消失
    status: isFavStatus(f.status) ? f.status : 'wish',
  }
}

export function listFavorites(): FavoriteSubject[] {
  const list = readJson<FavoriteSubject[]>('favorites', [])
  if (!Array.isArray(list)) return []
  // 逐字段归一化：文档可能来自旧版本、别的客户端，或被手工改过。
  // 只校验 id 的话，score 是字符串就会让 FavoritesPage 抛 toFixed is not a function，
  // 而唯一的错误边界在 <Routes> 之上，于是整站变成错误页。
  return list
    .map(toFavorite)
    .filter((f): f is FavoriteSubject => f !== null)
    .sort((a, b) => b.ts - a.ts)
}

function isFavStatus(value: unknown): value is FavStatus {
  return value === 'wish' || value === 'watching' || value === 'done' || value === 'onhold'
}

export function isFavorite(id: number): boolean {
  return listFavorites().some((f) => f.id === id)
}

/** 收藏 ID 集合：列表页批量判断用，避免每张卡片都重新解析一遍文档（O(n²)） */
export function favoriteIdSet(): Set<number> {
  return new Set(listFavorites().map((f) => f.id))
}

/**
 * 切换收藏，返回操作后是否处于「已收藏」。
 *
 * 入参允许不带 status（调用方通常只知道条目信息）：新建收藏时缺省记「想看」，
 * 已收藏则原样保留原来的状态 —— 再点一次是取消，不该顺手把「看过」重置成「想看」。
 */
export function toggleFavorite(
  subject: Omit<FavoriteSubject, 'status'> & { status?: FavStatus },
): boolean {
  const list = listFavorites()
  const exists = list.some((f) => f.id === subject.id)
  const next = exists
    ? list.filter((f) => f.id !== subject.id)
    : [{ ...subject, status: subject.status ?? 'wish' }, ...list]
  writeFavorites(next, [subject.id])
  return !exists
}

/** 改追番状态。条目不在收藏里时什么都不做（返回 false）。 */
export function setFavoriteStatus(id: number, status: FavStatus): boolean {
  const list = listFavorites()
  const target = list.find((f) => f.id === id)
  if (!target) return false
  writeFavorites(
    list.map((f) => (f.id === id ? { ...f, status } : f)),
    [id],
  )
  return true
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

export const SOURCE_FAV_KEY = 'favoritesSource'

export function sourceFavKeyOf(source: string, url: string): string {
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
  writeSourceFavorites(next, [key])
  return !exists
}
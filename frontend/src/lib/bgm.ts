import type {
  BgmCalendarDay,
  BgmCalendarItem,
  BgmEpisode,
  BgmImages,
  BgmRating,
  BgmSubject,
  BgmSubjectPage,
} from '../types'

/**
 * Bangumi 响应的边界归一化。
 *
 * types.ts 把 BgmSubject.name_cn / summary、BgmEpisode.name_cn、BgmCalendarDay.weekday
 * 都声明为必有，而这份数据要穿过 Bangumi 官方 / 社区镜像 / 本机代理三层中的任意一层，
 * 谁都可能少给字段。少一个就足以让页面在 `.trim()`、`.length` 或 `weekday.cn` 上抛
 * TypeError，而唯一的 ErrorBoundary 挂在 <Routes> 之上 —— 一个缺失字段把整站变成错误页。
 *
 * 数据层早就是逐字段归一化的（storage.ts 的 toFavorite / listRecent），这里补的是同一个
 * 口径在 API 边界上缺的那一份：类型里写「必有」的东西，得有人真的保证它有。
 */

function asMap(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {}
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function numberOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function listOf(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

function toImages(value: unknown): BgmImages | null {
  if (value == null || typeof value !== 'object' || Array.isArray(value)) return null
  const raw = asMap(value)
  return {
    large: typeof raw.large === 'string' ? raw.large : null,
    common: typeof raw.common === 'string' ? raw.common : null,
    medium: typeof raw.medium === 'string' ? raw.medium : null,
    small: typeof raw.small === 'string' ? raw.small : null,
    grid: typeof raw.grid === 'string' ? raw.grid : null,
  }
}

function toRating(value: unknown): BgmRating | null {
  if (value == null || typeof value !== 'object' || Array.isArray(value)) return null
  const raw = asMap(value)
  return { rank: numberOrNull(raw.rank), total: numberOrNull(raw.total), score: numberOrNull(raw.score) }
}

export function toEpisode(value: unknown): BgmEpisode {
  const raw = asMap(value)
  return {
    id: numberOrNull(raw.id) ?? 0,
    name: text(raw.name),
    name_cn: text(raw.name_cn),
    sort: numberOrNull(raw.sort),
    type: numberOrNull(raw.type) ?? undefined,
    duration: typeof raw.duration === 'string' ? raw.duration : null,
    airdate: typeof raw.airdate === 'string' ? raw.airdate : null,
  }
}

export function toSubject(value: unknown): BgmSubject {
  const raw = asMap(value)
  return {
    id: numberOrNull(raw.id) ?? 0,
    name: text(raw.name),
    name_cn: text(raw.name_cn),
    summary: text(raw.summary),
    date: typeof raw.date === 'string' ? raw.date : null,
    score: numberOrNull(raw.score),
    rating: toRating(raw.rating),
    rank: numberOrNull(raw.rank),
    images: toImages(raw.images),
    eps: toEps(raw.eps),
  }
}

/** eps 是联合类型：搜索接口原样透传 Bangumi 的「集数」整数，详情接口才是数组 */
function toEps(value: unknown): BgmEpisode[] | number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (!Array.isArray(value)) return undefined
  return value.map(toEpisode)
}

export function toSubjects(value: unknown): BgmSubject[] {
  return listOf(value).map(toSubject)
}

export function toSubjectPage(value: unknown): BgmSubjectPage {
  const raw = asMap(value)
  return {
    total: numberOrNull(raw.total) ?? 0,
    data: toSubjects(raw.data),
    limit: numberOrNull(raw.limit) ?? 0,
    offset: numberOrNull(raw.offset) ?? 0,
  }
}

function toCalendarItem(value: unknown): BgmCalendarItem {
  const raw = asMap(value)
  return {
    id: numberOrNull(raw.id) ?? 0,
    name: text(raw.name),
    name_cn: text(raw.name_cn),
    score: numberOrNull(raw.score),
    rating: toRating(raw.rating),
    images: toImages(raw.images),
    eps: numberOrNull(raw.eps),
  }
}

export function toCalendarDays(value: unknown): BgmCalendarDay[] {
  return listOf(value).map((day) => {
    const raw = asMap(day)
    const weekday = asMap(raw.weekday)
    return {
      weekday: {
        id: numberOrNull(weekday.id) ?? 0,
        en: text(weekday.en),
        cn: text(weekday.cn),
      },
      items: listOf(raw.items).map(toCalendarItem),
    }
  })
}

import { readJson, writeJson } from './sync'

export interface RecentItem {
  source: string
  playUrl: string
  title: string
  anime: string
  ep: string
  ts: number
}

export const RECENT_LIMIT = 8

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
import { readJson, writeJson } from './sync'

export const HISTORY_LIMIT = 10

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
import { asMap, pruneByTs } from './userDataMerge'
import { progressMap, readJson, writeProgressMap, writeSubjectProgress } from './sync'

export interface SubjectProgressEntry {
  ep: number
  ts: number
}

export function getSubjectProgress(subjectId: number): SubjectProgressEntry | null {
  const map = asMap(readJson<Record<string, unknown>>('subjectProgress', {}))
  const entry = map[String(subjectId)] as SubjectProgressEntry | undefined
  if (entry == null || typeof entry.ep !== 'number' || !(entry.ep > 0)) return null
  return { ep: entry.ep, ts: typeof entry.ts === 'number' ? entry.ts : 0 }
}

export function setSubjectProgress(subjectId: number, ep: number): void {
  if (!(subjectId > 0) || !(ep > 0)) return
  const map = asMap(readJson<Record<string, unknown>>('subjectProgress', {}))
  const key = String(subjectId)
  const prev = map[key] as SubjectProgressEntry | undefined
  // 只前进不后退：从第 5 话回看第 2 话不该把「看到第 5 话」改小
  if (prev && typeof prev.ep === 'number' && prev.ep >= ep) return
  const next = { ...map, [key]: { ep, ts: Date.now() } }
  // 和 progress 一样要剪枝：这个键按条目增长，不剪的话迟早撞上服务端 512KB 上限，
  // 之后每一次写入都会 400 —— 整个数据层从此写不进去，而不只是进度丢失。
  const { next: pruned, removed } = pruneByTs(next)
  // removed 也作为 touched 传入：被淘汰的键必须让服务端同步删除，否则 409 合并时会复活
  writeSubjectProgress(pruned, [key, ...removed])
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
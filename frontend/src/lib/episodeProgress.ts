import type { BgmEpisode, EpisodeHit } from '../types'
import { isSpecialEpisodeName, normalizeTitle } from './titleMatch'

/** 线路也属于剧集身份；特典线路的“第1话”不能被当成本篇。 */
export function sourceEpisodeIsSpecial(episode: Pick<EpisodeHit, 'name' | 'channel'>): boolean {
  return [episode.name, episode.channel ?? ''].some((name) =>
    isSpecialEpisodeName(name) || /\b(?:op|ed|cm|mad)\d*\b/i.test(name),
  )
}

/** 仅确认集号一致或具体剧集标题一致的本篇映射，其余只保存源级断点。 */
export function confirmedBangumiEpisode(bangumi: BgmEpisode, source: EpisodeHit): number | undefined {
  const want = bangumi.sort
  if ((bangumi.type ?? 0) !== 0 || sourceEpisodeIsSpecial(source) ||
    want == null || !Number.isInteger(want) || want <= 0) return undefined
  if (source.sortNum === want) return want
  const name = normalizeTitle(bangumi.name_cn || bangumi.name)
  // “第5话”不是独立的标题证据，不能用它绕开明确冲突的数字集号。
  if (name.length >= 2 && !/^(?:第)?\d+[话集]?$/.test(name) && name === normalizeTitle(source.name)) return want
  return undefined
}

/** 从已经确认的当前映射推下一话；特殊集、断号或换线路立即中止推算。 */
export function nextBangumiEpisode(current: EpisodeHit | undefined, next: EpisodeHit | undefined,
  currentBep?: number): number | undefined {
  if (!current || !next || currentBep == null || !Number.isInteger(currentBep) || currentBep <= 0 ||
    sourceEpisodeIsSpecial(current) || sourceEpisodeIsSpecial(next) || current.channel !== next.channel ||
    current.sortNum == null || next.sortNum !== current.sortNum + 1) return undefined
  return currentBep + 1
}

import { describe, expect, it } from 'vitest'
import type { ChannelGroupHit, EpisodeHit, EpisodesResult } from '../types'
import { pickEpisode } from './titleMatch'

function ep(name: string, sortNum: number, channel: string | null): EpisodeHit {
  return {
    channel,
    name,
    sort: String(sortNum),
    sortNum,
    playUrl: `https://cdn.test/${encodeURIComponent(name)}.mp4`,
  }
}

function group(name: string, episodes: EpisodeHit[]): ChannelGroupHit {
  return { name, episodes }
}

function result(groups: ChannelGroupHit[], episodes = groups.flatMap((g) => g.episodes)): EpisodesResult {
  return { channels: groups.map((g) => g.name), episodes, groups }
}

describe('pickEpisode', () => {
  it('does not fall forward into another season', () => {
    // 本季只有第 1 集，另一季有第 6 集；点第 5 集时找不到精确匹配，
    // 旧实现会从扁平并集里取「第一个 >= 5」→ 跨季播第 6 集。
    const r = result([
      group('第一季', [ep('第1话', 1, '第一季')]),
      group('第二季', [ep('第6话', 6, '第二季')]),
    ])
    expect(pickEpisode(r, 5, '第5话', ['Show'])).toBeNull()
  })

  it('resolves an exact episode from the flat list when there are no channel groups', () => {
    const r = result([], [ep('第1话', 1, null), ep('第5话', 5, null)])
    const hit = pickEpisode(r, 5, '第5话', ['Show'])
    expect(hit?.sortNum).toBe(5)
    expect(hit?.name).toBe('第5话')
  })
})

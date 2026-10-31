import { describe, expect, it } from 'vitest'
import { isSpecialEpisodeName } from '../src/lib/titleMatch'
import { confirmedBangumiEpisode, nextBangumiEpisode } from '../src/lib/episodeProgress'
import { callback, jsxCallback, SUBJECT } from './playerTestHarness'
import type { BgmEpisode, EpisodeHit } from '../src/types'

function selected(name: string, sortNum: number | null, channel: string | null = '正片'): EpisodeHit {
  return { name, sortNum, sort: String(sortNum ?? name), channel, playUrl: 'https://source.test/' + encodeURIComponent(name) }
}

function manualSelection(ep: EpisodeHit, bangumi: BgmEpisode, next?: EpisodeHit) {
  let destination = ''
  const scope = {
    ep, flow: { episode: bangumi, matchedSource: 'fixture', sourceSubjectUrl: 'https://source.test/subject',
      pickingEpisodes: { episodes: next ? [ep, next] : [ep] } },
    subject: { name_cn: 'Show', name: 'Show' }, isSpecialEpisodeName, confirmedBangumiEpisode,
    goPlay: callback(SUBJECT, 'goPlay', { subjectId: 77, window: { location: { assign: (url: string) => { destination = url } } } }),
    nextOf: callback(SUBJECT, 'nextOf', { isSpecialEpisodeName, nextBangumiEpisode }),
  }
  jsxCallback<() => void>(SUBJECT, 'onClick', scope, 'flow.matchedSource')()
  return new URL(destination, 'https://site.test').searchParams
}

const main5: BgmEpisode = { id: 5, name: 'Chapter five', name_cn: '', sort: 5, type: 0 }

describe('FP-04 manual selection does not inherit an unverified main episode', () => {
  it('keeps SP selection and its successor source-only', () => {
    const p = manualSelection(selected('SP 特别篇', 6), main5, selected('第6话', 7))
    expect(p.has('bep')).toBe(false)
    expect(p.get('ep')).toBe('6')
    expect(JSON.parse(p.get('next')!).bep).toBeUndefined()
  })

  it('does not write main progress for a special channel with ordinary numeric names', () => {
    const p = manualSelection(selected('第5话', 5, '特典'), main5)
    expect(p.has('bep')).toBe(false)
  })

  it('does not map a different or unknown source number merely because the modal started at episode 5', () => {
    expect(manualSelection(selected('第6话', 6), main5).has('bep')).toBe(false)
    expect(manualSelection(selected('正片', null), main5).has('bep')).toBe(false)
  })

  it('preserves a verified ordinary episode and its continuous same-channel next mapping', () => {
    const p = manualSelection(selected('第5话', 5), main5, selected('第6话', 6))
    expect(p.get('bep')).toBe('5')
    expect(p.get('ep')).toBe('5')
    expect(JSON.parse(p.get('next')!).bep).toBe('6')
    expect(p.get('subject')).toBe('77')
    expect(p.get('surl')).toBe('https://source.test/subject')
  })

  it('keeps Bangumi non-main episodes source-only', () => {
    expect(manualSelection(selected('第1话', 1), { ...main5, sort: 1, type: 1 }).has('bep')).toBe(false)
  })
})

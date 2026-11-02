import { describe, expect, it } from 'vitest'
import { buildPlayParams, buildPlayUrl, type PlayEpisode } from './playUrl'

const ep: PlayEpisode = {
  source: 'fixture',
  play: 'https://source.test/ep5',
  title: 'Show 第5话',
  anime: 'Show',
  ep: '5',
}

describe('buildPlayUrl', () => {
  it('carries the five fields the player cannot work without', () => {
    const p = buildPlayParams(ep)
    expect(p.get('source')).toBe('fixture')
    expect(p.get('play')).toBe('https://source.test/ep5')
    expect(p.get('title')).toBe('Show 第5话')
    expect(p.get('anime')).toBe('Show')
    expect(p.get('ep')).toBe('5')
  })

  it('keeps the Bangumi episode number apart from the source episode number', () => {
    expect(buildPlayParams({ ...ep, bep: '7' }).get('bep')).toBe('7')
    expect(buildPlayParams(ep).has('bep')).toBe(false)
  })

  // 漏 subject 时自动切到下一话后 subjectId=0，条目页的「看到第几话」永久停在上一话，
  // 且没有任何报错。这条断言就是那道口子。
  it('writes subject only for a real subject id', () => {
    expect(buildPlayParams(ep, { subjectId: 77 }).get('subject')).toBe('77')
    expect(buildPlayParams(ep, { subjectId: 0 }).has('subject')).toBe(false)
    expect(buildPlayParams(ep).has('subject')).toBe(false)
  })

  // 漏 surl 时，从数据源详情页进去的连播只能连一集。
  it('writes surl only when there is one', () => {
    expect(buildPlayParams(ep, { subjectUrl: 'https://source.test/subject' }).get('surl')).toBe(
      'https://source.test/subject',
    )
    expect(buildPlayParams(ep, { subjectUrl: '' }).has('surl')).toBe(false)
  })

  it('serialises the successor episode and omits it when absent', () => {
    const next: PlayEpisode = { ...ep, play: 'https://source.test/ep6', ep: '6', bep: '8' }
    expect(JSON.parse(buildPlayParams(ep, { next }).get('next')!)).toEqual(next)
    expect(buildPlayParams(ep, { next: null }).has('next')).toBe(false)
  })

  it('produces a /play URL', () => {
    const url = buildPlayUrl(ep, { subjectId: 77 })
    expect(url.startsWith('/play?')).toBe(true)
    const p = new URL(url, 'https://site.test').searchParams
    expect(p.get('source')).toBe('fixture')
    expect(p.get('play')).toBe('https://source.test/ep5')
    expect(p.get('subject')).toBe('77')
  })
})

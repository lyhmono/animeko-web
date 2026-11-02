import { describe, expect, it } from 'vitest'
import { toCalendarDays, toEpisode, toSubject, toSubjectPage, toSubjects } from './bgm'

describe('toSubject', () => {
  it('fills the fields types.ts declares as required when upstream omits them', () => {
    const subject = toSubject({ id: 12, name: 'Show' })
    expect(subject).toMatchObject({ id: 12, name: 'Show', name_cn: '', summary: '' })
    // 页面会对这些字段调字符串方法；undefined 会抛 TypeError，
    // 而唯一的 ErrorBoundary 在 <Routes> 之上，整站会变错误页
    expect(() => subject.summary.trim()).not.toThrow()
    expect(subject.name_cn.length).toBe(0)
  })

  it('survives payloads that are not objects at all', () => {
    for (const raw of [null, undefined, 'text', 42, [], [{ id: 1 }]]) {
      expect(() => toSubject(raw)).not.toThrow()
      expect(toSubject(raw)).toMatchObject({ id: 0, name: '', name_cn: '', summary: '' })
    }
  })

  it('keeps the eps union honest: an integer stays an integer', () => {
    expect(toSubject({ id: 1, eps: 12 }).eps).toBe(12)
    expect(toSubject({ id: 1 }).eps).toBeUndefined()
    expect(toSubject({ id: 1, eps: [{ id: 5, name: 'Five' }] }).eps).toEqual([
      { id: 5, name: 'Five', name_cn: '', sort: null, type: undefined, duration: null, airdate: null },
    ])
  })

  it('normalises nested objects instead of passing junk through', () => {
    const subject = toSubject({ id: 1, images: { large: 42, common: 'c' }, rating: 'nope', score: 'x' })
    expect(subject.images).toEqual({ large: null, common: 'c', medium: null, small: null, grid: null })
    expect(subject.rating).toBeNull()
    expect(subject.score).toBeNull()
  })
})

describe('toEpisode', () => {
  it('fills name_cn, which the episode lists render directly', () => {
    expect(toEpisode({ id: 5, name: 'Five', sort: 5, type: 0 })).toMatchObject({
      id: 5, name: 'Five', name_cn: '', sort: 5, type: 0,
    })
    expect(toEpisode(null)).toMatchObject({ id: 0, name: '', name_cn: '', sort: null })
  })
})

describe('toSubjects', () => {
  it('turns a non-array into an empty list rather than throwing', () => {
    expect(toSubjects(null)).toEqual([])
    expect(toSubjects({ data: [] })).toEqual([])
    expect(toSubjects([{ id: 1 }, 'junk'])).toHaveLength(2)
  })
})

describe('toSubjectPage', () => {
  it('fills the paging envelope', () => {
    expect(toSubjectPage(null)).toEqual({ total: 0, data: [], limit: 0, offset: 0 })
    const page = toSubjectPage({ total: 'many', data: [{ id: 1 }], limit: 24, offset: 0 })
    expect(page).toMatchObject({ total: 0, limit: 24, offset: 0 })
    expect(page.data).toHaveLength(1)
  })
})

describe('toCalendarDays', () => {
  it('supplies weekday, which the calendar renders unconditionally', () => {
    const days = toCalendarDays([{ items: [{ id: 1, name: 'Show' }] }, null])
    expect(days).toHaveLength(2)
    expect(days[0].weekday).toEqual({ id: 0, en: '', cn: '' })
    expect(days[0].items).toEqual([
      { id: 1, name: 'Show', name_cn: '', score: null, rating: null, images: null, eps: null },
    ])
    expect(days[1].items).toEqual([])
    expect(() => days[0].weekday.cn.trim()).not.toThrow()
  })

  it('turns a non-array into an empty list', () => {
    expect(toCalendarDays(null)).toEqual([])
    expect(toCalendarDays({ weekday: {} })).toEqual([])
  })
})

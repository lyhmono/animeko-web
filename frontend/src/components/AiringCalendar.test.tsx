import { expect, it, vi } from 'vitest'
import { HookHarness, elements, settle, text } from '../test/uiHarness'

vi.mock('react', async (original) => ({
  ...await original<typeof import('react')>(), ...(await import('../test/uiHarness')).testHooks,
}))
vi.mock('../api', () => ({ api: { bangumiCalendar: vi.fn() } }))
vi.mock('../lib/storage', () => ({ favoriteIdSet: () => new Set<number>(), onDocChange: () => () => {} }))
import { api } from '../api'
import AiringCalendar from './AiringCalendar'

it('exposes weekday filters as ordinary pressed buttons and updates their content', async () => {
  vi.mocked(api.bangumiCalendar).mockResolvedValue([
    { weekday: { id: 1, en: 'Monday', cn: '星期一' }, items: [{ id: 1, name: 'monday-fixture', name_cn: '' }] },
    { weekday: { id: 2, en: 'Tuesday', cn: '星期二' }, items: [{ id: 2, name: 'tuesday-fixture', name_cn: '' }] },
  ])
  const harness = new HookHarness()
  harness.render(AiringCalendar)
  harness.commit()
  await settle()
  harness.render(AiringCalendar)
  const group = elements(harness.tree).find((el) => el.props['aria-label'] === '选择星期')!
  expect(group.props.role).toBe('group')
  const days = elements(group).filter((el) => el.type === 'button')
  expect(days).toHaveLength(2)
  expect(days.every((el) => el.props.role === undefined && el.props['aria-selected'] === undefined)).toBe(true)
  expect(days.filter((el) => el.props['aria-pressed'] === true)).toHaveLength(1)
  days[1].props.onClick()
  harness.render(AiringCalendar)
  const selected = elements(harness.tree).find((el) => el.props['aria-label'] === '选择星期')!
  expect(elements(selected).filter((el) => el.type === 'button').map((el) => el.props['aria-pressed'])).toEqual([false, true])
  expect(text(harness.tree)).toContain('tuesday-fixture')
  expect(text(harness.tree)).not.toContain('monday-fixture')
  harness.unmount()
})

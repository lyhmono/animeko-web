import { beforeEach, describe, expect, it, vi } from 'vitest'
import { HookHarness, elements, text, testHooks } from '../test/uiHarness'
import type { FavoriteSubject, SourceFavorite } from '../lib/storage'

vi.mock('react', async (original) => ({ ...await original<typeof import('react')>(), ...testHooks }))
vi.mock('../lib/storage', () => ({
  FAV_STATUS_LABEL: { wish: '想看', watching: '在看', done: '看过', onhold: '搁置' },
  listFavorites: vi.fn(), listSourceFavorites: vi.fn(), onDocChange: () => () => {},
  setFavoriteStatus: vi.fn(), toggleFavorite: vi.fn(), toggleSourceFavorite: vi.fn(),
}))
let Page: typeof import('./FavoritesPage').default
let storage: typeof import('../lib/storage')
const favorites = (count: number): FavoriteSubject[] => Array.from({ length: count }, (_, i) => ({
  id: i + 1, name: `favorite-${i + 1}`, name_cn: '', cover: '', date: '', score: null, ts: i, status: 'wish',
}))
const sourceFavorites = (count: number): SourceFavorite[] => Array.from({ length: count }, (_, i) => ({
  source: 'fixture', url: `https://fixture.invalid/${i}`, name: `source-favorite-${i}`, cover: '', ts: i,
}))
function mount() {
  const harness = new HookHarness()
  harness.render(Page)
  harness.commit()
  return harness
}
function grids(harness: HookHarness) {
  return elements(harness.tree).filter((el) => el.props.className === 'fav-grid')
}
function cards(tree: Parameters<typeof elements>[0]) {
  return elements(tree).filter((el) => el.props.className === 'fav-card')
}
function more(harness: HookHarness, name: string) {
  return elements(harness.tree).find((el) => el.type === 'button' && el.props['aria-label'] === name)
}
beforeEach(async () => {
  vi.resetModules()
  storage = await import('../lib/storage')
  vi.mocked(storage.listFavorites).mockReturnValue(favorites(1000))
  vi.mocked(storage.listSourceFavorites).mockReturnValue(sourceFavorites(53))
  Page = (await import('./FavoritesPage')).default
})

describe('bounded favorite rendering', () => {
  it('uses ordinary pressed filter buttons rather than incomplete tabs', () => {
    const harness = mount()
    const group = elements(harness.tree).find((el) => el.props['aria-label'] === '按追番状态筛选')!
    expect(group.props.role).toBe('group')
    const filters = elements(group).filter((el) => el.type === 'button')
    expect(filters).toHaveLength(5)
    expect(filters.every((el) => el.props.role === undefined && el.props['aria-selected'] === undefined)).toBe(true)
    expect(filters.map((el) => el.props['aria-pressed'])).toEqual([true, false, false, false, false])
    filters[1].props.onClick()
    harness.render(Page)
    const updated = elements(harness.tree).find((el) => el.props['aria-label'] === '按追番状态筛选')!
    expect(elements(updated).filter((el) => el.type === 'button').map((el) => el.props['aria-pressed'])).toEqual([false, true, false, false, false])
    harness.unmount()
  })

  it('mounts one page per collection and allows every later item to be reached', () => {
    const harness = mount()
    expect(cards(grids(harness)[0])).toHaveLength(24)
    expect(cards(grids(harness)[1])).toHaveLength(24)
    expect(text(harness.tree)).toContain('已显示 24 / 1000')
    expect(text(harness.tree)).toContain('已显示 24 / 53')
    for (let shown = 24; shown < 1000; shown += 24) {
      const load = more(harness, '加载更多追番收藏')
      expect(load?.type).toBe('button')
      load!.props.onClick()
      harness.render(Page)
      harness.commit()
      expect(cards(grids(harness)[0])).toHaveLength(Math.min(shown + 24, 1000))
    }
    expect(more(harness, '加载更多追番收藏')).toBeUndefined()
    expect(new Set(cards(grids(harness)[0]).map((el) => el.key)).size).toBe(1000)
    for (const expected of [48, 53]) {
      more(harness, '加载更多数据源收藏')!.props.onClick()
      harness.render(Page)
      harness.commit()
      expect(cards(grids(harness)[1])).toHaveLength(expected)
    }
    expect(more(harness, '加载更多数据源收藏')).toBeUndefined()
    expect(text(harness.tree)).toContain('source-favorite-52')
    harness.unmount()
  })

  it('retains expanded pages on return and keeps expansion independent by filter', () => {
    const items = favorites(80)
    items.forEach((item, i) => { item.status = i < 40 ? 'watching' : 'wish' })
    vi.mocked(storage.listFavorites).mockReturnValue(items)
    const harness = mount()
    expect(cards(grids(harness)[0])).toHaveLength(24)
    more(harness, '加载更多追番收藏')!.props.onClick()
    harness.render(Page)
    harness.commit()
    expect(cards(grids(harness)[0])).toHaveLength(40)
    const wish = elements(harness.tree).find((el) => el.type === 'button' && text(el).startsWith('想看'))!
    wish.props.onClick()
    harness.render(Page)
    harness.commit()
    expect(cards(grids(harness)[0])).toHaveLength(24)
    expect(text(grids(harness)[0])).toContain('favorite-41')
    more(harness, '加载更多追番收藏')!.props.onClick()
    harness.render(Page)
    harness.commit()
    harness.unmount()
    const returned = mount()
    expect(cards(grids(returned)[0])).toHaveLength(40)
    expect(text(grids(returned)[0])).toContain('favorite-41')
    returned.unmount()
  })
})

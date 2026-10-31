import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { HookHarness, deferred, elements, settle, text } from '../test/uiHarness'
import type { StoredSource, SubjectHit } from '../types'

const query = vi.hoisted(() => ({ q: 'cache-fixture' }))
vi.mock('react', async (importOriginal) => ({
  ...await importOriginal<typeof import('react')>(),
  ...(await import('../test/uiHarness')).testHooks,
}))
vi.mock('react-router-dom', async (importOriginal) => ({
  ...await importOriginal<typeof import('react-router-dom')>(),
  useSearchParams: () => [new URLSearchParams({ q: query.q })],
}))
vi.mock('../lib/storage', () => ({
  addSearchHistory: vi.fn(), favoriteIdSet: () => new Set<number>(), onDocChange: () => () => {},
}))
vi.mock('../api', async (importOriginal) => ({
  ...await importOriginal<typeof import('../api')>(),
  api: { searchBangumi: vi.fn(), sources: vi.fn(), sourceSearch: vi.fn() },
}))
import { api } from '../api'
import { notifySourcesChanged } from '../lib/sourceEvents'
import SearchPage from './SearchPage'

function source(name: string): StoredSource {
  return { factoryId: 'web-selector', version: 2, enabled: true, arguments: {
    name, description: '', iconUrl: '', searchConfig: {
      searchUrl: 'https://fixture.invalid', requestInterval: 0, subjectFormatId: 'a', channelFormatId: 'no-channel',
    },
  } }
}
function mount() {
  const harness = new HookHarness()
  harness.render(SearchPage)
  harness.commit()
  return harness
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal('window', new EventTarget())
  vi.mocked(api.searchBangumi).mockResolvedValue([])
  vi.mocked(api.sources).mockResolvedValue([source('old-source')])
  vi.mocked(api.sourceSearch).mockResolvedValue([{ name: 'old-hit', url: 'https://fixture.invalid/old' }])
})
afterEach(() => vi.unstubAllGlobals())

describe('search cache source revisions', () => {
  it('restores a snapshot on return, but refetches after source configuration changes', async () => {
    query.q = 'cache-fixture'
    const first = mount()
    await settle()
    first.render(SearchPage)
    expect(text(first.tree)).toContain('old-source')
    first.unmount()
    const returned = mount()
    await settle()
    expect(api.sources).toHaveBeenCalledTimes(1)
    returned.unmount()

    notifySourcesChanged()
    vi.mocked(api.sources).mockResolvedValue([source('new-source')])
    vi.mocked(api.sourceSearch).mockResolvedValue([{ name: 'new-hit', url: 'https://fixture.invalid/new' }])
    const changed = mount()
    await settle()
    changed.render(SearchPage)
    expect(api.sources).toHaveBeenCalledTimes(2)
    expect(text(changed.tree)).toContain('new-source')
    expect(text(changed.tree)).not.toContain('old-source')
    changed.unmount()
  })

  it('stops dispatching queued source searches when the page is left', async () => {
    query.q = 'departure-fixture'
    vi.mocked(api.sources).mockResolvedValue(Array.from({ length: 7 }, (_, index) => source(`source-${index}`)))
    const pending = deferred<SubjectHit[]>()
    vi.mocked(api.sourceSearch).mockReturnValue(pending.promise)
    const harness = mount()
    await settle()
    expect(api.sourceSearch).toHaveBeenCalledTimes(3)
    harness.unmount()
    pending.resolve([])
    await settle()
    expect(api.sourceSearch).toHaveBeenCalledTimes(3)
  })
})

describe('complete source result access', () => {
  it.each([false, true])('loads every hit in bounded batches and remembers expansion (covers=%s)', async (covers) => {
    query.q = `expansion-${covers}`
    const hits = Array.from({ length: 45 }, (_, index) => ({
      name: `hit-${index}`, url: `https://fixture.invalid/hit-${index}`, cover: covers ? 'https://fixture.invalid/poster.jpg' : '',
    }))
    vi.mocked(api.sources).mockResolvedValue([source('many-results')])
    vi.mocked(api.sourceSearch).mockResolvedValue(hits)
    const harness = mount()
    await settle()
    harness.render(SearchPage)
    const links = () => elements(harness.tree).filter((element) => element.props.to?.startsWith('/source/'))
    expect(links()).toHaveLength(20)
    const more = () => elements(harness.tree).find((element) => element.type === 'button' && element.props['aria-label'] === 'many-results：显示更多结果')
    expect(more()?.type).toBe('button')
    more()!.props.onClick()
    harness.render(SearchPage)
    expect(links()).toHaveLength(40)
    more()!.props.onClick()
    harness.render(SearchPage)
    expect(links()).toHaveLength(45)
    expect(more()).toBeUndefined()
    expect(text(harness.tree)).toContain('hit-44')
    harness.unmount()
    const returned = mount()
    await settle()
    returned.render(SearchPage)
    expect(elements(returned.tree).filter((element) => element.props.to?.startsWith('/source/'))).toHaveLength(45)
    expect(api.sourceSearch).toHaveBeenCalledTimes(1)
    returned.unmount()
  })
})

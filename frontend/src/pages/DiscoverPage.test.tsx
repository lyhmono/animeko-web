import { beforeEach, describe, expect, it, vi } from 'vitest'
import { HookHarness, button, deferred, elements, settle, text } from '../test/uiHarness'
import type { BgmSubjectPage } from '../types'

vi.mock('react', async (importOriginal) => ({
  ...await importOriginal<typeof import('react')>(),
  ...(await import('../test/uiHarness')).testHooks,
}))
vi.mock('../lib/storage', () => ({ favoriteIdSet: () => new Set<number>(), onDocChange: () => () => {} }))
vi.mock('../api', async (importOriginal) => ({
  ...await importOriginal<typeof import('../api')>(), api: { bangumiRanking: vi.fn() },
}))
import { api } from '../api'
import DiscoverPage from './DiscoverPage'

function page(offset: number): BgmSubjectPage {
  return { total: 72, limit: 24, offset, data: Array.from({ length: 24 }, (_, index) => ({
    id: offset + index + 1, name: `rank-${offset + index + 1}`, name_cn: '', summary: '',
  })) }
}
function mount() {
  const harness = new HookHarness()
  harness.render(DiscoverPage)
  harness.commit()
  return harness
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(api.bangumiRanking).mockImplementation((offset) => Promise.resolve(page(offset)))
})

describe('ranking return continuity', () => {
  it('preserves every loaded page after visiting a subject and returning', async () => {
    const first = mount()
    await settle()
    first.render(DiscoverPage)
    button(first.tree, '加载更多').props.onClick()
    await settle()
    first.render(DiscoverPage)
    expect(elements(first.tree).filter((element) => element.props.to?.startsWith('/subject/'))).toHaveLength(48)
    first.unmount()
    const returned = mount()
    await settle()
    returned.render(DiscoverPage)
    expect(elements(returned.tree).filter((element) => element.props.to?.startsWith('/subject/'))).toHaveLength(48)
    expect(api.bangumiRanking).toHaveBeenCalledTimes(2)
    returned.unmount()
  })

  it('provides an in-place retry for an initial load failure', async () => {
    const harness = mount()
    await settle()
    harness.render(DiscoverPage)
    // Refresh also gives callers an explicit way to discard a retained snapshot.
    const refresh = elements(harness.tree).find((element) => element.type === 'button' && element.props['aria-label'] === '刷新排行榜')
    expect(refresh?.type).toBe('button')
    vi.mocked(api.bangumiRanking).mockRejectedValueOnce(new Error('offline'))
    refresh!.props.onClick()
    await settle()
    harness.render(DiscoverPage)
    expect(text(harness.tree)).toContain('offline')
    button(harness.tree, '重试').props.onClick()
    await settle()
    harness.render(DiscoverPage)
    expect(text(harness.tree)).not.toContain('offline')
    expect(elements(harness.tree).filter((element) => element.props.to?.startsWith('/subject/'))).toHaveLength(24)
    harness.unmount()
  })

  it('coalesces repeated load-more clicks and aborts that request on departure', async () => {
    const harness = mount()
    await settle()
    harness.render(DiscoverPage)
    const pending = deferred<BgmSubjectPage>()
    vi.mocked(api.bangumiRanking).mockReturnValue(pending.promise)
    const before = vi.mocked(api.bangumiRanking).mock.calls.length
    const loadMore = button(harness.tree, '加载更多')
    loadMore.props.onClick()
    loadMore.props.onClick()
    expect(api.bangumiRanking).toHaveBeenCalledTimes(before + 1)
    harness.unmount()
    expect(vi.mocked(api.bangumiRanking).mock.calls[before][2]?.aborted).toBe(true)
    const writesAtDeparture = harness.writes
    pending.resolve(page(24))
    await settle()
    expect(harness.writes).toBe(writesAtDeparture)
  })
})

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { HookHarness, button, deferred, elements, settle, text } from '../test/uiHarness'
import type { BgmChannelInfo, BgmProbeResult } from '../types'

vi.mock('react', async (importOriginal) => ({
  ...await importOriginal<typeof import('react')>(),
  ...(await import('../test/uiHarness')).testHooks,
}))
vi.mock('../api', async (importOriginal) => ({
  ...await importOriginal<typeof import('../api')>(),
  api: { bangumiChannel: vi.fn(), probeBangumiChannel: vi.fn(), setBangumiChannel: vi.fn() },
}))
import { api } from '../api'
import BangumiChannelCard from './BangumiChannelCard'

const info: BgmChannelInfo = {
  channel: 'OFFICIAL', customUrls: [], effectiveBases: ['fixture-official'],
  defaultBases: ['fixture-official'], calendarSupported: true,
}
const results: BgmProbeResult[] = [{ base: 'old-probe-address', ok: true, calendarOk: true, latencyMs: 1, detail: 'old result' }]
function mount() {
  const harness = new HookHarness()
  harness.render(BangumiChannelCard)
  harness.commit()
  return harness
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(api.bangumiChannel).mockResolvedValue(info)
  vi.mocked(api.setBangumiChannel).mockResolvedValue(info)
})

describe('channel probe input generation', () => {
  it('never displays an old probe after selecting a different channel', async () => {
    const probe = deferred<BgmProbeResult[]>()
    vi.mocked(api.probeBangumiChannel).mockReturnValue(probe.promise)
    const harness = mount()
    await settle()
    harness.render(BangumiChannelCard)
    button(harness.tree, '测试连通性').props.onClick()
    elements(harness.tree).find((element) => element.type === 'input' && element.props.value === 'MIRROR')!.props.onChange()
    probe.resolve(results)
    await settle()
    harness.render(BangumiChannelCard)
    expect(text(harness.tree)).not.toContain('old-probe-address')
    harness.unmount()
  })

  it('invalidates a pending probe when saving and prevents late writes after unmount', async () => {
    const probe = deferred<BgmProbeResult[]>()
    vi.mocked(api.probeBangumiChannel).mockReturnValue(probe.promise)
    const harness = mount()
    await settle()
    harness.render(BangumiChannelCard)
    button(harness.tree, '测试连通性').props.onClick()
    button(harness.tree, '保存').props.onClick()
    await settle()
    probe.resolve(results)
    await settle()
    harness.render(BangumiChannelCard)
    expect(text(harness.tree)).not.toContain('old-probe-address')
    const late = deferred<BgmProbeResult[]>()
    vi.mocked(api.probeBangumiChannel).mockReturnValue(late.promise)
    button(harness.tree, '测试连通性').props.onClick()
    harness.unmount()
    const writesAtDeparture = harness.writes
    late.resolve(results)
    await settle()
    expect(harness.writes).toBe(writesAtDeparture)
  })
})

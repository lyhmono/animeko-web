import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { HookHarness, button, deferred, elements, settle, text } from '../test/uiHarness'
import type { StoredSource, SubjectHit } from '../types'
import { FocusElement, installFocusDom, key } from '../test/focusDom'

vi.mock('react', async (importOriginal) => ({
  ...await importOriginal<typeof import('react')>(),
  ...(await import('../test/uiHarness')).testHooks,
}))
vi.mock('../api', async (importOriginal) => ({
  ...await importOriginal<typeof import('../api')>(),
  api: {
    sources: vi.fn(), sourceSearch: vi.fn(), setSourceEnabled: vi.fn(),
    importSource: vi.fn(), deleteSource: vi.fn(), previewSubscription: vi.fn(), importSubscription: vi.fn(),
  },
}))

import { api } from '../api'
import { getSourceRevision } from '../lib/sourceEvents'
import SourcesPage from './SourcesPage'

function source(name: string, enabled = true): StoredSource {
  return {
    factoryId: 'web-selector', version: 2, enabled,
    arguments: { name, description: '', iconUrl: '', searchConfig: {
      searchUrl: 'https://fixture.invalid/search', requestInterval: 0,
      subjectFormatId: 'a', channelFormatId: 'no-channel',
    } },
  }
}
function mount() {
  const harness = new HookHarness()
  harness.render(SourcesPage)
  harness.commit()
  return harness
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal('window', new EventTarget())
  vi.mocked(api.sources).mockResolvedValue([source('one')])
  vi.mocked(api.setSourceEnabled).mockResolvedValue({ name: 'one', enabled: false })
  vi.mocked(api.sourceSearch).mockResolvedValue([])
})
afterEach(() => vi.unstubAllGlobals())

describe('source deletion modal focus', () => {
  it('owns focus, traps Tab, and restores the trigger after Escape', async () => {
    const dom = installFocusDom()
    const harness = mount()
    await settle()
    harness.render(SourcesPage)
    button(harness.tree, '删除').props.onClick()
    harness.render(SourcesPage)
    const modal = elements(harness.tree).find((el) => el.props.role === 'dialog')!
    expect(modal.props.ref).toBeDefined()
    expect(button(harness.tree, '取消').props.autoFocus).toBeUndefined()
    modal.props.ref.current = dom.modal
    harness.commit()
    expect(dom.document.activeElement === dom.cancel).toBe(true)
    expect(dom.background.inert).toBe(true)
    dom.confirm.focus()
    expect(key(dom.document, 'Tab').defaultPrevented).toBe(true)
    expect(dom.document.activeElement === dom.cancel).toBe(true)
    key(dom.document, 'Tab', true)
    expect(dom.document.activeElement === dom.confirm).toBe(true)
    key(dom.document, 'Escape')
    harness.render(SourcesPage)
    harness.commit()
    expect(elements(harness.tree).some((el) => el.props.role === 'dialog')).toBe(false)
    expect(dom.document.activeElement === dom.trigger).toBe(true)
    expect(dom.background.inert).toBe(false)
    harness.unmount()
  })

  it('keeps the pending deletion modal owned and restores a stable fallback when the trigger disappears', async () => {
    const dom = installFocusDom()
    const heading = new FocusElement(dom.document, 'h2')
    heading.setAttribute('data-modal-return-focus', '')
    dom.background.append(heading)
    const harness = mount()
    await settle()
    harness.render(SourcesPage)
    expect(elements(harness.tree).some((el) => el.type === 'h2' && 'data-modal-return-focus' in el.props)).toBe(true)
    button(harness.tree, '删除').props.onClick()
    harness.render(SourcesPage)
    elements(harness.tree).find((el) => el.props.role === 'dialog')!.props.ref.current = dom.modal
    harness.commit()
    const pending = deferred<{ deleted: string }>()
    vi.mocked(api.deleteSource).mockReturnValue(pending.promise)
    vi.mocked(api.sources).mockResolvedValue([])
    const confirm = button(harness.tree, '确认删除')
    confirm.props.onClick()
    confirm.props.onClick()
    harness.render(SourcesPage)
    expect(button(harness.tree, '删除中…').props.disabled).toBe(true)
    expect(api.deleteSource).toHaveBeenCalledOnce()
    key(dom.document, 'Escape')
    harness.render(SourcesPage)
    expect(elements(harness.tree).some((el) => el.props.role === 'dialog')).toBe(true)
    pending.resolve({ deleted: 'one' })
    await settle()
    dom.trigger.remove()
    harness.render(SourcesPage)
    harness.commit()
    expect(dom.document.activeElement === heading).toBe(true)
    harness.unmount()
  })
})

describe('source checks own their request lifetime', () => {
  it('aborts in-flight checks and never dispatches queued sources after unmount', async () => {
    vi.mocked(api.sources).mockResolvedValue(Array.from({ length: 7 }, (_, index) => source(`source-${index}`)))
    const pending = deferred<SubjectHit[]>()
    vi.mocked(api.sourceSearch).mockReturnValue(pending.promise)
    const harness = mount()
    await settle()
    harness.render(SourcesPage)
    button(harness.tree, '检查可用性').props.onClick()
    expect(api.sourceSearch).toHaveBeenCalledTimes(3)
    harness.unmount()
    const writesAtDeparture = harness.writes
    pending.resolve([])
    await settle()
    expect(api.sourceSearch).toHaveBeenCalledTimes(3)
    expect(vi.mocked(api.sourceSearch).mock.calls.every((call) => call[2]?.aborted)).toBe(true)
    expect(harness.writes).toBe(writesAtDeparture)
  })

  it('offers cancel and discards old results when a new batch starts', async () => {
    vi.mocked(api.sources).mockResolvedValue(Array.from({ length: 7 }, (_, index) => source(`source-${index}`)))
    const old = deferred<SubjectHit[]>()
    const next = deferred<SubjectHit[]>()
    vi.mocked(api.sourceSearch).mockReturnValue(old.promise)
    const harness = mount()
    await settle()
    harness.render(SourcesPage)
    button(harness.tree, '检查可用性').props.onClick()
    harness.render(SourcesPage)
    const cancel = elements(harness.tree).find((element) => element.type === 'button' && text(element) === '取消检查')
    expect(cancel?.type).toBe('button')
    cancel!.props.onClick()
    harness.render(SourcesPage)
    vi.mocked(api.sourceSearch).mockReturnValue(next.promise)
    button(harness.tree, '检查可用性').props.onClick()
    old.resolve([{ name: 'old', url: 'https://fixture.invalid/old' }])
    await settle()
    expect(api.sourceSearch).toHaveBeenCalledTimes(6)
    harness.render(SourcesPage)
    expect(text(harness.tree)).not.toContain('✓ 可用')
    harness.unmount()
    next.resolve([])
    await settle()
  })

  it('coalesces repeated single-source clicks and aborts the request on departure', async () => {
    const pending = deferred<SubjectHit[]>()
    vi.mocked(api.sourceSearch).mockReturnValue(pending.promise)
    const harness = mount()
    await settle()
    harness.render(SourcesPage)
    elements(harness.tree).find((element) => element.props.placeholder === '测试关键词')!.props.onChange({ target: { value: '动画' } })
    harness.render(SourcesPage)
    const test = button(harness.tree, '测试')
    test.props.onClick()
    test.props.onClick()
    expect(api.sourceSearch).toHaveBeenCalledTimes(1)
    harness.unmount()
    expect(vi.mocked(api.sourceSearch).mock.calls[0][2]?.aborted).toBe(true)
    const writesAtDeparture = harness.writes
    pending.resolve([])
    await settle()
    expect(harness.writes).toBe(writesAtDeparture)
  })
})

describe('source mutation notifications', () => {
  it.each(['toggle', 'delete', 'single import', 'subscription import'])('invalidates search caches after a successful %s', async (kind) => {
    const before = getSourceRevision()
    const harness = mount()
    await settle()
    harness.render(SourcesPage)
    if (kind === 'toggle') {
      elements(harness.tree).find((element) => element.props.role === 'switch')!.props.onClick()
    } else if (kind === 'delete') {
      vi.mocked(api.deleteSource).mockResolvedValue({ deleted: 'one' })
      button(harness.tree, '删除').props.onClick()
      harness.render(SourcesPage)
      button(harness.tree, '确认删除').props.onClick()
    } else if (kind === 'single import') {
      vi.mocked(api.importSource).mockResolvedValue(source('imported').arguments)
      elements(harness.tree).find((element) => element.props.placeholder?.startsWith('粘贴 animeko 数据源'))!.props.onChange({ target: { value: '{}' } })
      harness.render(SourcesPage)
      button(harness.tree, '导入').props.onClick()
    } else {
      vi.mocked(api.previewSubscription).mockResolvedValue({ subscription: 'fixture', fingerprint: 'f1', rejected: [], candidates: [
        { name: 'imported', description: '', tier: '', site: '', exists: false, enabled: true },
      ] })
      vi.mocked(api.importSubscription).mockResolvedValue({ added: ['imported'], updated: [], failed: [], rejected: [] })
      elements(harness.tree).find((element) => element.props.placeholder?.startsWith('订阅地址'))!.props.onChange({ target: { value: 'https://fixture.invalid/sub' } })
      harness.render(SourcesPage)
      button(harness.tree, '拉取订阅').props.onClick()
      await settle()
      harness.render(SourcesPage)
      button(harness.tree, '导入所选（1 个）').props.onClick()
    }
    await settle()
    expect(getSourceRevision()).toBe(before + 1)
    harness.unmount()
  })
})

describe('source list reload ordering', () => {
  it('keeps the newest mutation snapshot when an earlier list request arrives late', async () => {
    vi.mocked(api.sources).mockResolvedValue([source('one'), source('two')])
    const harness = mount()
    await settle()
    harness.render(SourcesPage)
    const earlier = deferred<StoredSource[]>()
    const latest = deferred<StoredSource[]>()
    vi.mocked(api.sources).mockReturnValueOnce(earlier.promise).mockReturnValueOnce(latest.promise)
    const switches = elements(harness.tree).filter((element) => element.props.role === 'switch')
    switches[0].props.onClick()
    switches[1].props.onClick()
    await settle()
    latest.resolve([source('one', false), source('two', false)])
    await settle()
    harness.render(SourcesPage)
    expect(elements(harness.tree).filter((element) => element.props.role === 'switch').map((element) => element.props['aria-checked'])).toEqual([false, false])
    earlier.resolve([source('one', false), source('two', true)])
    await settle()
    harness.render(SourcesPage)
    expect(elements(harness.tree).filter((element) => element.props.role === 'switch').map((element) => element.props['aria-checked'])).toEqual([false, false])
    harness.unmount()
  })

  it('keeps the changed source switch disabled until its reload finishes', async () => {
    const harness = mount()
    await settle()
    harness.render(SourcesPage)
    const pendingList = deferred<StoredSource[]>()
    vi.mocked(api.sources).mockReturnValueOnce(pendingList.promise)
    elements(harness.tree).find((element) => element.props.role === 'switch')!.props.onClick()
    await settle()
    harness.render(SourcesPage)
    expect(elements(harness.tree).find((element) => element.props.role === 'switch')!.props.disabled).toBe(true)
    pendingList.resolve([source('one', false)])
    await settle()
    harness.render(SourcesPage)
    expect(elements(harness.tree).find((element) => element.props.role === 'switch')!.props.disabled).toBe(false)
    harness.unmount()
  })
})

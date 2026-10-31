import { afterEach, describe, expect, it, vi } from 'vitest'
import { button, elements, HookHarness, settle } from '../src/test/uiHarness'
import { FocusObserver, installFocusDom, key } from '../src/test/focusDom'

vi.mock('react', async (importOriginal) => ({
  ...await importOriginal<typeof import('react')>(),
  ...(await import('../src/test/uiHarness')).testHooks,
}))
vi.mock('react-router-dom', () => ({ useParams: () => ({ id: '77' }), Link: 'a' }))
vi.mock('../src/api', () => ({
  errMsg: (error: unknown) => String(error),
  api: {
    bangumiSubject: vi.fn(async () => ({ id: 77, name: 'Show', name_cn: '', summary: '', images: { large: 'https://images.test/cover.jpg' },
      eps: [{ id: 5, name: 'Chapter five', name_cn: '', sort: 5, type: 0 }] })),
    sources: vi.fn(async () => [{ enabled: true, arguments: { name: 'fixture', description: '' } }]),
  },
}))
vi.mock('../src/lib/storage', () => ({
  getSubjectProgress: () => null, isFavorite: () => false, onDocChange: () => () => {}, toggleFavorite: () => false,
}))
import SubjectPage from '../src/pages/SubjectPage'
import Poster from '../src/components/Poster'

afterEach(() => vi.unstubAllGlobals())

async function openSubjectModal() {
  const dom = installFocusDom()
  vi.stubGlobal('window', dom.document)
  const harness = new HookHarness()
  harness.render(SubjectPage)
  harness.commit()
  await settle()
  button(harness.render(SubjectPage), '播放').props.onClick()
  const tree = harness.render(SubjectPage)
  const dialog = elements(tree).find((element) => element.props.role === 'dialog')!
  dialog.props.ref.current = dom.modal
  harness.commit()
  return { dom, harness }
}

describe('FP-09 SubjectPage delegates actual modal focus ownership', () => {
  it('contains Tab, makes the background inert, and restores the episode trigger on Escape', async () => {
    const { dom, harness } = await openSubjectModal()
    expect(dom.background.inert).toBe(true)
    dom.confirm.focus()
    expect(key(dom.document, 'Tab').defaultPrevented).toBe(true)
    expect(dom.document.activeElement === dom.cancel).toBe(true)
    expect(key(dom.document, 'Tab', true).defaultPrevented).toBe(true)
    expect(dom.document.activeElement === dom.confirm).toBe(true)
    key(dom.document, 'Escape')
    harness.render(SubjectPage)
    harness.commit()
    expect(elements(harness.tree).some((element) => element.props.role === 'dialog')).toBe(false)
    expect(dom.document.activeElement === dom.trigger).toBe(true)
    expect(dom.background.inert).toBe(false)
    harness.unmount()
  })

  it('recovers focus when a state transition removes the previously focused control', async () => {
    const { dom, harness } = await openSubjectModal()
    dom.cancel.focus()
    dom.cancel.remove()
    FocusObserver.mutate()
    expect(dom.document.activeElement === dom.confirm).toBe(true)
    harness.unmount()
    expect(dom.document.activeElement === dom.trigger).toBe(true)
  })
})

describe('FP-12 subject first-screen poster priority', () => {
  it('opts in only the actual main subject poster to eager/high loading', async () => {
    const { harness } = await openSubjectModal()
    const poster = elements(harness.tree).find((element) => element.type === Poster)!
    expect(poster.props.src).toBe('https://images.test/cover.jpg')
    expect(poster.props.loading).toBe('eager')
    expect(poster.props.fetchPriority).toBe('high')
    harness.unmount()
  })
})

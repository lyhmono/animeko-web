import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { HookHarness, button, deferred, elements, settle } from '../test/uiHarness'

vi.mock('react', async (original) => ({
  ...await original<typeof import('react')>(),
  ...(await import('../test/uiHarness')).testHooks,
}))
vi.mock('../api', () => ({ api: { health: vi.fn().mockResolvedValue({}) }, errMsg: String }))
vi.mock('../lib/auth', () => ({ rememberedUser: () => '' }))
vi.mock('../lib/storage', () => ({
  exportUserData: vi.fn(), flushUserData: vi.fn().mockResolvedValue(undefined),
  isUserDataLoaded: vi.fn().mockReturnValue(true), getDirectPlayback: () => false,
  getSyncState: () => ({ status: 'ready' }), importUserData: vi.fn().mockReturnValue({}),
  setDirectPlayback: vi.fn(), getTheme: () => 'dark', onSyncChange: () => () => {},
  setTheme: vi.fn(), syncThemeColor: vi.fn(),
}))
import { importUserData, isUserDataLoaded } from '../lib/storage'
import SettingsPage from './SettingsPage'

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(isUserDataLoaded).mockReturnValue(true)
  vi.stubGlobal('window', new EventTarget())
})
afterEach(() => vi.unstubAllGlobals())

it('opens the file input from a native keyboard-operable button', () => {
  const harness = new HookHarness()
  harness.render(SettingsPage)
  const trigger = elements(harness.tree).find((el) => el.type === 'button' && el.props.children === '导入备份')
  expect(trigger?.type).toBe('button')
  expect(trigger!.props.type).toBe('button')
  const input = elements(harness.tree).find((el) => el.type === 'input' && el.props.type === 'file')!
  const click = vi.fn()
  input.props.ref.current = { click }
  trigger!.props.onClick()
  expect(click).toHaveBeenCalledOnce()
})

it('disables both import controls until loaded and throughout a pending import', async () => {
  const harness = new HookHarness()
  vi.mocked(isUserDataLoaded).mockReturnValue(false)
  harness.render(SettingsPage)
  expect(button(harness.tree, '导入备份').props.disabled).toBe(true)
  let input = elements(harness.tree).find((el) => el.type === 'input' && el.props.type === 'file')!
  expect(input.props.disabled).toBe(true)
  vi.mocked(isUserDataLoaded).mockReturnValue(true)
  harness.render(SettingsPage)
  expect(button(harness.tree, '导入备份').props.disabled).toBe(false)
  const pending = deferred<string>()
  input = elements(harness.tree).find((el) => el.type === 'input' && el.props.type === 'file')!
  const target = { files: [{ size: 2, text: () => pending.promise }], value: 'backup.json' }
  input.props.onChange({ target })
  expect(target.value).toBe('')
  harness.render(SettingsPage)
  expect(button(harness.tree, '导入中…').props.disabled).toBe(true)
  expect(elements(harness.tree).find((el) => el.type === 'input' && el.props.type === 'file')!.props.disabled).toBe(true)
  pending.resolve('{}')
  await settle()
  harness.render(SettingsPage)
  expect(importUserData).toHaveBeenCalledOnce()
  expect(button(harness.tree, '导入备份').props.disabled).toBe(false)
})

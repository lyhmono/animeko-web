import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/** 执行真实 App 的 hooks/事件回调；仅替换渲染器，DOM/浏览器验收由主审负责。 */
const hooks = vi.hoisted(() => {
  type Slot = { value?: unknown; deps?: readonly unknown[]; cleanup?: () => void }
  let slots: Slot[] = []
  let cursor = 0
  let effects: (() => void)[] = []
  const equal = (left?: readonly unknown[], right?: readonly unknown[]) =>
    !!left && !!right && left.length === right.length && left.every((item, index) => Object.is(item, right[index]))
  return {
    useState<T>(initial: T | (() => T)) {
      const index = cursor++
      const slot = slots[index] ?? (slots[index] = { value: typeof initial === 'function' ? (initial as () => T)() : initial })
      return [slot.value as T, (next: T | ((prev: T) => T)) => {
        slot.value = typeof next === 'function' ? (next as (prev: T) => T)(slot.value as T) : next
      }] as const
    },
    useRef<T>(initial: T) {
      const index = cursor++
      const slot = slots[index] ?? (slots[index] = { value: { current: initial } })
      return slot.value as { current: T }
    },
    useCallback<T>(callback: T, deps: readonly unknown[]) {
      const index = cursor++
      const slot = slots[index] ?? (slots[index] = {})
      if (!equal(slot.deps, deps)) { slot.value = callback; slot.deps = deps }
      return slot.value as T
    },
    useEffect(effect: () => void | (() => void), deps?: readonly unknown[]) {
      const index = cursor++
      const slot = slots[index] ?? (slots[index] = {})
      if (!equal(slot.deps, deps)) {
        slot.deps = deps
        effects.push(() => { slot.cleanup?.(); slot.cleanup = effect() || undefined })
      }
    },
    render<T>(component: () => T) {
      cursor = 0
      const tree = component()
      const ready = effects
      effects = []
      ready.forEach((effect) => effect())
      return tree
    },
    state() { return slots[0]?.value as { stage: string; error?: string; phase?: string } },
    dispose() { slots.forEach((slot) => slot.cleanup?.()); slots = []; effects = []; cursor = 0 },
  }
})
vi.mock('react', async (importOriginal) => ({ ...await importOriginal<typeof import('react')>(), ...hooks }))
vi.mock('./components/Layout', () => ({ default: () => null }))
vi.mock('./components/ErrorBoundary', () => ({ default: () => null }))
vi.mock('./pages/HomePage', () => ({ default: () => null }))
vi.mock('./pages/LoginPage', () => ({ default: () => null }))
vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api')>()
  return { ...actual, api: { ...actual.api, health: vi.fn(), login: vi.fn(), logout: vi.fn(), loadDataWithEtag: vi.fn(), saveData: vi.fn() } }
})

import App from './App'
import { api, ApiError, UNAUTHORIZED_EVENT } from './api'
import { rememberedUser, rememberUser, signIn, verifiedSessionUser } from './lib/auth'
import { discardRetainedUserData, exportUserData, listFavorites, listRetainedUserData, markUserDataStale, toggleFavorite } from './lib/storage'
const health = vi.mocked(api.health)
const logout = vi.mocked(api.logout)
const load = vi.mocked(api.loadDataWithEtag)
const save = vi.mocked(api.saveData)
const favorite = { id: 2, name: 'Unsaved', name_cn: '', cover: '', date: '', score: null, ts: 20 }
async function settle() { for (let index = 0; index < 30; index++) await Promise.resolve() }
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
function text(node: unknown): string {
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(text).join('')
  if (node && typeof node === 'object' && 'props' in node) return text((node.props as { children?: unknown }).children)
  return ''
}
function buttons(node: unknown): { name: string; click?: () => void; disabled?: boolean }[] {
  if (Array.isArray(node)) return node.flatMap(buttons)
  if (!node || typeof node !== 'object' || !('props' in node)) return []
  const element = node as { type: unknown; props: { children?: unknown; onClick?: () => void; disabled?: boolean } }
  return [
    ...(element.type === 'button' ? [{ name: text(element.props.children), click: element.props.onClick, disabled: element.props.disabled }] : []),
    ...buttons(element.props.children),
  ]
}
function property(node: unknown, name: string): unknown {
  if (Array.isArray(node)) return node.map((item) => property(item, name)).find((value) => value !== undefined)
  if (!node || typeof node !== 'object' || !('props' in node)) return undefined
  const props = node.props as Record<string, unknown>
  return props[name] ?? property(props.children, name)
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('window', Object.assign(new EventTarget(), { confirm: vi.fn(() => false) }))
  vi.stubGlobal('location', { origin: 'https://animeko.test' })
  const values = new Map<string, string>()
  vi.stubGlobal('sessionStorage', { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => values.set(key, value), removeItem: (key: string) => values.delete(key) })
  markUserDataStale()
  listRetainedUserData().forEach((entry) => discardRetainedUserData(entry.id))
  health.mockReset().mockResolvedValue({ status: 'ok', version: 'fixture', authEnabled: true, authenticated: true })
  logout.mockReset().mockResolvedValue({ authenticated: false })
  vi.mocked(api.login).mockReset().mockResolvedValue({ authenticated: true, user: 'alice' })
  load.mockReset().mockResolvedValue({ doc: {}, etag: 'base' })
  save.mockReset().mockResolvedValue({ etag: 'saved', bytes: 100 })
  rememberUser('alice')
})
afterEach(() => {
  hooks.dispose()
  markUserDataStale()
  listRetainedUserData().forEach((entry) => discardRetainedUserData(entry.id))
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('App logout transaction', () => {
  it.each([[false, false], [true, false], [false, true]])('stops discarded uploads before logout settles (in flight: %s, logout fails: %s)', async (inFlight, logoutFails) => {
    const actual = await vi.importActual<typeof import('./api')>('./api')
    load.mockImplementation(actual.api.loadDataWithEtag)
    save.mockImplementation(actual.api.saveData)
    const lateWrite = deferred<Response>()
    const requests: RequestInit[] = []
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      if (init.method !== 'PUT') return new Response(JSON.stringify({ favorites: [{ ...favorite, id: 1 }] }), { headers: { ETag: 'base' } })
      requests.push(init)
      if (inFlight && requests.length === 2) return lateWrite.promise
      return new Response(JSON.stringify({ error: 'UNAVAILABLE' }), { status: 503 })
    }))
    hooks.render(App)
    await settle()
    toggleFavorite(favorite)
    window.dispatchEvent(new Event('animeko-sign-out'))
    await settle()
    expect(hooks.state()).toMatchObject({ stage: 'leave-error', phase: 'save' })
    expect(requests).toHaveLength(1)
    if (inFlight) {
      await vi.advanceTimersByTimeAsync(5_000)
      expect(requests).toHaveLength(2)
    }

    const logoutAck = deferred<Awaited<ReturnType<typeof api.logout>>>()
    logout.mockImplementationOnce(() => logoutAck.promise)
    vi.mocked(window.confirm).mockReturnValue(true)
    const discard = buttons(hooks.render(App)).find((button) => button.name === '放弃未同步改动并退出')!
    expect(discard).toBeDefined()
    discard.click!()
    await settle()
    expect(logout).toHaveBeenCalledTimes(1)
    if (inFlight) expect(requests[1]!.signal!.aborted).toBe(true)
    lateWrite.resolve(new Response(JSON.stringify({ error: 'UNAVAILABLE' }), { status: 503 }))
    await vi.advanceTimersByTimeAsync(10_000)
    expect(requests).toHaveLength(inFlight ? 2 : 1)

    // 注销回执到达但会话仍有效：返回时重读，不自动恢复已放弃上传的副本。
    if (logoutFails) logoutAck.reject(new Error('logout offline'))
    else logoutAck.resolve({ authenticated: false })
    await settle()
    expect(hooks.state()).toMatchObject({ stage: 'leave-error', phase: 'logout' })
    const back = buttons(hooks.render(App)).find((button) => button.name === '返回站点')!
    expect(back).toBeDefined()
    back.click!()
    await settle()
    expect(hooks.state().stage).toBe('ready')
    expect(listFavorites().map((item) => item.id)).toEqual([1])
    expect(listRetainedUserData()).toEqual([expect.objectContaining({ owner: 'alice', complete: true })])
    await vi.advanceTimersByTimeAsync(10_000)
    expect(requests).toHaveLength(inFlight ? 2 : 1)
  })

  it('retries the initial read before saving and exiting, merging local edits into the remote document', async () => {
    const actual = await vi.importActual<typeof import('./api')>('./api')
    load.mockImplementation(actual.api.loadDataWithEtag)
    save.mockImplementation(actual.api.saveData)
    let offline = true
    const requests: string[] = []
    const writes: Record<string, unknown>[] = []
    const writeAck = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      requests.push(init.method ?? 'GET')
      if (offline) return new Response(JSON.stringify({ error: 'OFFLINE' }), { status: 503 })
      if (init.method === 'PUT') {
        writes.push(JSON.parse(String(init.body)))
        return writeAck.promise
      }
      return new Response(JSON.stringify({ favorites: [{ ...favorite, id: 1 }], remoteOnly: 'keep me' }), { headers: { ETag: 'remote-base' } })
    }))
    hooks.render(App)
    await settle()
    toggleFavorite(favorite)
    window.dispatchEvent(new Event('animeko-sign-out'))
    await settle()
    expect(hooks.state()).toMatchObject({ stage: 'leave-error', phase: 'save' })
    expect(writes).toEqual([])
    expect(logout).not.toHaveBeenCalled()

    offline = false
    requests.length = 0
    const retry = buttons(hooks.render(App)).find((button) => button.name === '重试保存并退出')!
    expect(retry).toBeDefined()
    retry.click!()
    await settle()
    expect(requests).toEqual(['GET', 'PUT'])
    expect(writes[0]!.remoteOnly).toBe('keep me')
    expect((writes[0]!.favorites as { id: number }[]).map((item) => item.id).sort()).toEqual([1, 2])
    expect(logout).not.toHaveBeenCalled()
    health.mockResolvedValue({ status: 'ok', version: 'fixture', authEnabled: true, authenticated: false })
    writeAck.resolve(new Response(JSON.stringify({ bytes: 100 }), { headers: { ETag: 'saved' } }))
    await settle()
    expect(logout).toHaveBeenCalledTimes(1)
    expect(hooks.state().stage).toBe('login')
  })

  it('keeps failed-read retries recoverable and returns to login on recovery auth loss', async () => {
    load.mockRejectedValue(new Error('offline'))
    hooks.render(App)
    await settle()
    toggleFavorite(favorite)
    window.dispatchEvent(new Event('animeko-sign-out'))
    await settle()
    const retry = () => {
      const action = buttons(hooks.render(App)).find((button) => button.name === '重试保存并退出')!
      expect(action).toBeDefined()
      action.click!()
    }
    retry()
    await settle()
    expect(hooks.state()).toMatchObject({ stage: 'leave-error', phase: 'save' })
    expect(listFavorites().map((item) => item.id)).toEqual([2])
    expect(save).not.toHaveBeenCalled()
    expect(logout).not.toHaveBeenCalled()

    const actual = await vi.importActual<typeof import('./api')>('./api')
    load.mockImplementation(actual.api.loadDataWithEtag)
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ error: 'UNAUTHORIZED' }), { status: 401 })))
    retry()
    await settle()
    expect(hooks.state().stage).toBe('login')
    expect(listRetainedUserData()).toEqual([expect.objectContaining({ owner: 'alice', complete: false })])
    expect(save).not.toHaveBeenCalled()
    expect(logout).not.toHaveBeenCalled()
  })

  it('exports a clearly labelled partial unsynced copy from the failed-read exit screen without losing pending edits', async () => {
    load.mockRejectedValue(new Error('still offline'))
    hooks.render(App)
    await settle()
    toggleFavorite(favorite)
    window.dispatchEvent(new Event('animeko-sign-out'))
    await settle()
    expect(hooks.state()).toMatchObject({ stage: 'leave-error', phase: 'save' })
    expect(() => exportUserData()).toThrow('还没从服务器读到')

    const blobs: Blob[] = []
    vi.spyOn(URL, 'createObjectURL').mockImplementation((blob) => { blobs.push(blob as Blob); return 'blob:partial' })
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const link = { href: '', download: '', click: vi.fn() }
    vi.stubGlobal('document', { createElement: vi.fn(() => link) })
    const tree = hooks.render(App)
    const action = buttons(tree).find((button) => button.name === '导出局部未同步副本')!
    expect(action).toBeDefined()
    expect(text(tree)).toContain('不是完整备份')
    action.click!()
    expect(link.click).toHaveBeenCalledTimes(1)
    expect(link.download).toBe('animeko-unsynced-partial.json')
    expect(revoke).toHaveBeenCalledWith('blob:partial')
    const backup = JSON.parse(await blobs[0]!.text())
    expect(backup).toMatchObject({ format: 'animeko-unsynced-partial', complete: false, warning: expect.stringContaining('不是完整备份') })
    expect(backup.pending.doc.favorites.map((item: { id: number }) => item.id)).toEqual([2])
    expect(backup.pending.favIds).toEqual([2])
    expect(listFavorites().map((item) => item.id)).toEqual([2])
    expect(() => exportUserData()).toThrow('还没从服务器读到')
    expect(save).not.toHaveBeenCalled()
    expect(logout).not.toHaveBeenCalled()
    expect(text(hooks.render(App))).toContain('已导出局部未同步副本')
  })

  it('does not call logout or clear pending data after a failed save', async () => {
    hooks.render(App)
    await settle()
    expect(hooks.state().stage).toBe('ready')
    toggleFavorite(favorite)
    save.mockRejectedValueOnce(new ApiError(400, 'too large', 'DATA_TOO_LARGE'))

    window.dispatchEvent(new Event('animeko-sign-out'))
    await settle()
    expect(logout).not.toHaveBeenCalled()
    expect(listFavorites().map((item) => item.id)).toEqual([2])
    expect(hooks.state()).toMatchObject({ stage: 'leave-error', phase: 'save', error: expect.stringContaining('too large') })
    const actions = buttons(hooks.render(App))
    expect(actions.map((button) => button.name)).toEqual(expect.arrayContaining(['重试保存并退出', '导出备份', '放弃未同步改动并退出', '返回站点']))
    actions.find((button) => button.name === '放弃未同步改动并退出')?.click?.()
    await settle()
    expect(window.confirm).toHaveBeenCalled()
    expect(logout).not.toHaveBeenCalled() // 未明确确认放弃，仍不能清档或注销。
  })

  it('catches logout failure, probes actual session state and retains a retryable exit', async () => {
    hooks.render(App)
    await settle()
    toggleFavorite(favorite)
    logout.mockRejectedValueOnce(new Error('logout network failure'))
    window.dispatchEvent(new Event('animeko-sign-out'))
    await settle()

    expect(health).toHaveBeenCalledTimes(2)
    expect(hooks.state()).toMatchObject({ stage: 'leave-error', phase: 'logout', error: expect.stringContaining('logout network failure') })
    expect(listFavorites().map((item) => item.id)).toEqual([2])
    expect(rememberedUser()).toBe('alice')
  })

  it('makes repeated exit events single-flight while saving', async () => {
    hooks.render(App)
    await settle()
    toggleFavorite(favorite)
    const ack = deferred<Awaited<ReturnType<typeof api.saveData>>>()
    save.mockImplementationOnce(() => ack.promise)
    health.mockResolvedValue({ status: 'ok', version: 'fixture', authEnabled: true, authenticated: false })
    window.dispatchEvent(new Event('animeko-sign-out'))
    window.dispatchEvent(new Event('animeko-sign-out'))
    window.dispatchEvent(new Event('animeko-sign-out'))
    await settle()
    const duringSave = hooks.state().stage
    ack.resolve({ etag: 'saved', bytes: 100 })
    await settle()

    expect(logout).toHaveBeenCalledTimes(1)
    expect(duringSave).toBe('leaving')
    expect(save).toHaveBeenCalledTimes(1)
    expect(hooks.state().stage).toBe('login')
  })

  it('does not let an old post-logout probe clear a newer logged-in document', async () => {
    hooks.render(App)
    await settle()
    const oldProbe = deferred<Awaited<ReturnType<typeof api.health>>>()
    health.mockImplementationOnce(() => oldProbe.promise)
    window.dispatchEvent(new Event('animeko-sign-out'))
    await settle()
    expect(health).toHaveBeenCalledTimes(2)

    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))
    const onSuccess = property(hooks.render(App), 'onSuccess') as () => void
    expect(onSuccess).toBeTypeOf('function')
    rememberUser('bob')
    load.mockResolvedValueOnce({ doc: { favorites: [{ ...favorite, id: 3 }] }, etag: 'new-session' })
    onSuccess()
    await settle()
    expect(hooks.state().stage).toBe('ready')

    oldProbe.resolve({ status: 'ok', version: 'fixture', authEnabled: true, authenticated: false })
    await settle()
    expect(hooks.state().stage).toBe('ready')
    expect(listFavorites().map((item) => item.id)).toEqual([3])
    expect(rememberedUser()).toBe('bob')
  })

  it('keeps unsynced data recoverable behind the login screen when the session is lost', async () => {
    await signIn('alice', 'fixture-only')
    hooks.render(App)
    await settle()
    toggleFavorite(favorite)
    window.dispatchEvent(new Event(UNAUTHORIZED_EVENT))

    expect(hooks.state().stage).toBe('login')
    expect(listFavorites()).toEqual([])
    expect(listRetainedUserData()).toEqual([expect.objectContaining({ owner: 'alice', complete: true })])
    expect(verifiedSessionUser()).toBe('')
    const actions = buttons(hooks.render(App))
    expect(actions.map((button) => button.name)).toEqual(expect.arrayContaining(['导出未同步副本', '恢复未同步改动', '放弃该副本']))
    expect(actions.find((button) => button.name === '恢复未同步改动')?.disabled).toBe(true)
  })
})

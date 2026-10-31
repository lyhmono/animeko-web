import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, api: { ...actual.api, health: vi.fn(), login: vi.fn(), logout: vi.fn() } }
})
import { api } from '../api'
import * as auth from './auth'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((yes) => { resolve = yes })
  return { promise, resolve }
}
beforeEach(() => {
  const values = new Map<string, string>()
  vi.stubGlobal('sessionStorage', { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => values.set(key, value), removeItem: (key: string) => values.delete(key) })
  vi.mocked(api.login).mockReset().mockResolvedValue({ authenticated: true, user: 'alice' })
  vi.mocked(api.logout).mockReset().mockResolvedValue({ authenticated: false })
  vi.mocked(api.health).mockReset().mockResolvedValue({ status: 'ok', version: 'fixture', authEnabled: true, authenticated: true })
  auth.rememberUser('alice')
})
afterEach(() => { vi.unstubAllGlobals() })

describe('auth operation generation', () => {
  it('does not let an older logout completion erase the newly verified login identity', async () => {
    const ack = deferred<Awaited<ReturnType<typeof api.logout>>>()
    vi.mocked(api.logout).mockImplementationOnce(() => ack.promise)
    const oldLogout = auth.signOut().catch((error: unknown) => error)
    vi.mocked(api.login).mockResolvedValueOnce({ authenticated: true, user: 'bob' })
    await expect(auth.signIn('bob', 'fixture-only')).resolves.toBe('')
    ack.resolve({ authenticated: false })
    await oldLogout

    expect(auth.rememberedUser()).toBe('bob')
    expect(Reflect.get(auth, 'verifiedSessionUser')?.()).toBe('bob')
  })
})

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, UNAUTHORIZED_EVENT } from './api'
import { invalidateSessionIdentity, signIn, signOut } from './lib/auth'

let stopBody: (() => void) | undefined

/** 真正的 Response body；只有 fetch 信号中止才让半开正文结束。 */
function hangingResponse(status = 200) {
  let body!: ReadableStreamDefaultController<Uint8Array>
  const stream = new ReadableStream<Uint8Array>({ start(controller) { body = controller } })
  stopBody = () => {
    try { body.error(new DOMException('fixture cleanup', 'AbortError')) } catch { /* already closed */ }
  }
  let signal!: AbortSignal
  vi.stubGlobal('fetch', vi.fn((_path: string, init: RequestInit) => {
    signal = init.signal as AbortSignal
    signal.addEventListener('abort', () => body.error(new DOMException('aborted', 'AbortError')), { once: true })
    return Promise.resolve(new Response(stream, { status, headers: { 'Content-Type': 'application/json' } }))
  }))
  return { signal: () => signal }
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('window', new EventTarget())
})
afterEach(() => {
  stopBody?.()
  stopBody = undefined
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('API request lifetime', () => {
  it('keeps the total deadline while a successful JSON body is stalled', async () => {
    const response = hangingResponse()
    const outcome = api.searchBangumi('stalled').then(() => 'success', (error: unknown) => error)
    await vi.advanceTimersByTimeAsync(60_001)

    expect(response.signal().aborted).toBe(true)
    expect(await outcome).toEqual(expect.objectContaining({ message: expect.stringContaining('请求超时') }))
    expect(vi.getTimerCount()).toBe(0)
  })

  const signalMethods = [
    { name: 'health', method: api.health, args: [] },
    { name: 'login', method: api.login, args: ['name', 'test-only'] },
    { name: 'logout', method: api.logout, args: [] },
    { name: 'loadDataWithEtag', method: api.loadDataWithEtag, args: [] },
    { name: 'saveData', method: api.saveData, args: [{}, 'base'] },
    { name: 'bangumiSubject', method: api.bangumiSubject, args: [1] },
    { name: 'bangumiChannel', method: api.bangumiChannel, args: [] },
    { name: 'setBangumiChannel', method: api.setBangumiChannel, args: ['OFFICIAL', []] },
    { name: 'probeBangumiChannel', method: api.probeBangumiChannel, args: [undefined] },
    { name: 'sources', method: api.sources, args: [] },
    { name: 'importSource', method: api.importSource, args: ['{}'] },
    { name: 'deleteSource', method: api.deleteSource, args: ['source'] },
    { name: 'setSourceEnabled', method: api.setSourceEnabled, args: ['source', false] },
    { name: 'sourceEpisodes', method: api.sourceEpisodes, args: ['source', 'https://source.test/1'] },
    { name: 'resolveVideo', method: api.resolveVideo, args: ['source', 'https://source.test/play'] },
    { name: 'previewSubscription', method: api.previewSubscription, args: [{ text: '{}' }] },
    { name: 'importSubscription', method: api.importSubscription, args: [{ text: '{}', names: [] }] },
    // 已有 signal 方法作为正向对照，签名不变。
    { name: 'searchBangumi', method: api.searchBangumi, args: ['q'] },
    { name: 'bangumiRanking', method: api.bangumiRanking, args: [0, 24] },
    { name: 'bangumiCalendar', method: api.bangumiCalendar, args: [] },
    { name: 'sourceSearch', method: api.sourceSearch, args: ['source', 'q'] },
  ]
  it.each(signalMethods)('$name forwards optional cancellation through a stalled body', async ({ method, args }) => {
    const response = hangingResponse()
    const outer = new AbortController()
    const outcome = (Reflect.apply(method, api, [...args, outer.signal]) as Promise<unknown>)
      .then(() => 'success', (error: unknown) => error)
    await vi.advanceTimersByTimeAsync(0)
    outer.abort(new DOMException('caller canceled', 'AbortError'))

    expect(response.signal().aborted).toBe(true)
    expect(await outcome).toEqual(expect.objectContaining({ name: 'AbortError' }))
    expect(vi.getTimerCount()).toBe(0)
  })

  it.each([
    { name: 'document text', start: () => api.loadDataWithEtag(), status: 200 },
    { name: 'save acknowledgement', start: () => api.saveData({}, 'base'), status: 200 },
    { name: 'error JSON', start: () => api.sources(), status: 401 },
  ])('bounds $name reading as part of the same deadline', async ({ start, status }) => {
    const lost = vi.fn()
    window.addEventListener(UNAUTHORIZED_EVENT, lost)
    hangingResponse(status)
    const outcome = start().then(() => 'success', (error: unknown) => error)
    await vi.advanceTimersByTimeAsync(60_001)
    const error = await outcome
    expect(error).not.toBeInstanceOf(ApiError)
    expect(error).toEqual(expect.objectContaining({ message: expect.stringContaining('请求超时') }))
    expect(lost).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('does not acknowledge a save whose body fails during transport', async () => {
    const failure = new TypeError('connection terminated during body')
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(
      new ReadableStream({ start(controller) { controller.error(failure) } }),
      { headers: { ETag: '"unconfirmed"' } },
    ))))
    await expect(api.saveData({}, 'base')).rejects.toBe(failure)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('keeps old-server empty acknowledgements compatible and cleans caller listeners', async () => {
    const outer = new AbortController()
    const remove = vi.spyOn(outer.signal, 'removeEventListener')
    const fetcher = vi.fn((_path: string, _init?: RequestInit) => Promise.resolve(new Response('', { headers: { ETag: 'W/"new"' } })))
    vi.stubGlobal('fetch', fetcher)
    await expect(api.saveData({}, 'base', outer.signal)).resolves.toEqual({ etag: 'new', bytes: 0 })
    expect(fetcher.mock.calls[0]?.[1]).toEqual(expect.objectContaining({
      method: 'PUT', headers: { 'Content-Type': 'application/json', 'If-Match': '"base"' },
    }))
    expect(remove).toHaveBeenCalledWith('abort', expect.any(Function))
    expect(vi.getTimerCount()).toBe(0)
  })

  it.each(['logout', 'session-loss'])('cancels pending responses on %s without stale authentication events', async (mode) => {
    const lost = vi.fn()
    window.addEventListener(UNAUTHORIZED_EVENT, lost)
    const old = hangingResponse(401)
    const outcome = api.sources().catch((error: unknown) => error)
    await vi.advanceTimersByTimeAsync(0)
    if (mode === 'logout') {
      vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify({ authenticated: false })))))
      await signOut()
    } else {
      invalidateSessionIdentity()
    }
    expect(old.signal().aborted).toBe(true)
    expect(await outcome).toEqual(expect.objectContaining({ name: 'AbortError' }))
    expect(lost).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('a failed login does not discard requests belonging to the existing session', async () => {
    const old = hangingResponse()
    const outcome = api.sources().catch((error: unknown) => error)
    await vi.advanceTimersByTimeAsync(0)
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('{"error":"wrong credential"}', { status: 401 }))))
    await expect(signIn('bob', 'wrong-fixture')).resolves.toContain('401')
    expect(old.signal().aborted).toBe(false)
    stopBody?.()
    await outcome
  })

  it('cancels old-session body reads after a verified new login instead of broadcasting stale 401', async () => {
    const lost = vi.fn()
    window.addEventListener(UNAUTHORIZED_EVENT, lost)
    const old = hangingResponse(401)
    const outcome = api.sources().then(() => 'success', (error: unknown) => error)
    await vi.advanceTimersByTimeAsync(0)
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify({ authenticated: true, user: 'bob' }), {
      headers: { 'Content-Type': 'application/json' },
    }))))
    await expect(signIn('bob', 'fixture-only')).resolves.toBe('')

    expect(old.signal().aborted).toBe(true)
    expect(await outcome).toEqual(expect.objectContaining({ name: 'AbortError' }))
    expect(lost).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })
})

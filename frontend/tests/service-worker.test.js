import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { describe, expect, it, vi } from 'vitest'

const workerSource = readFileSync(new URL('../public/sw.js', import.meta.url), 'utf8')
const origin = 'https://animeko.test'

function worker({ broken = '', response = new Response('<div id="root"></div>', { headers: { 'Content-Type': 'text/html' } }) } = {}) {
  const handlers = new Map()
  const cache = {
    match: vi.fn(async () => { if (broken === 'match') throw new Error('cache unavailable'); return undefined }),
    put: vi.fn(async () => { if (broken === 'put') throw new Error('quota exceeded') }),
    add: vi.fn(async () => {}),
  }
  const caches = { open: vi.fn(async () => { if (broken === 'open') throw new Error('disabled storage'); return cache }) }
  class WorkerRequest extends Request { constructor(url, options) { super(new URL(url, origin), options) } }
  const fetcher = vi.fn(async () => response)
  const context = vm.createContext({
    self: { location: { origin }, clients: { claim: vi.fn() }, addEventListener: (name, callback) => handlers.set(name, callback) },
    caches, fetch: fetcher, Request: WorkerRequest, Response, URL, console,
  })
  vm.runInContext(workerSource, context, { filename: 'public/sw.js' })
  return { context, handlers, cache, caches, fetcher, response }
}
const request = (path) => new Request(origin + path)

async function invoke(state, strategy) {
  if (strategy === 'navigate') return state.context.networkFirstNavigation(request('/'))
  if (strategy === 'cache-first') return state.context.cacheFirst(request('/assets/new.js'), 'test-assets')
  const event = { waitUntil: vi.fn() }
  return state.context.staleWhileRevalidate(request('/favicon.svg'), 'test-assets', event)
}

describe('Service Worker failure isolation', () => {
  for (const strategy of ['navigate', 'cache-first', 'stale-while-revalidate']) {
    it.each(['open', 'match', 'put'])(`${strategy}: returns the successful network response when cache %s fails`, async (broken) => {
      const state = worker({ broken })
      const response = await invoke(state, strategy)
      expect(response).toBe(state.response)
      expect(response.status).toBe(200)
    })
  }

  it.each(['image/svg+xml', 'application/json', 'text/plain'])('does not replace the offline shell with %s', async (type) => {
    const state = worker({ response: new Response('not the app', { headers: { 'Content-Type': type } }) })
    expect(await state.context.networkFirstNavigation(request('/favicon.svg'))).toBe(state.response)
    expect(state.cache.put).not.toHaveBeenCalled()
  })

  it('only caches valid application navigation paths as the offline shell', async () => {
    const state = worker()
    await state.context.networkFirstNavigation(request('/unrelated-document.html'))
    expect(state.cache.put).not.toHaveBeenCalled()
    await state.context.networkFirstNavigation(request('/subject/12'))
    expect(state.cache.put).toHaveBeenCalledTimes(1)
  })

  it.each(['no-store', 'private'])('the install precache respects %s', async (policy) => {
    const state = worker({ response: new Response('<html>private</html>', { headers: { 'Content-Type': 'text/html', 'Cache-Control': policy } }) })
    let work
    state.handlers.get('install')({ waitUntil: (promise) => { work = promise } })
    await work
    expect(state.cache.put).not.toHaveBeenCalled()
    expect(state.cache.add).not.toHaveBeenCalled()
  })

  it('does not persist partial responses', () => {
    const state = worker()
    expect(state.context.cacheable(new Response('partial', { status: 206 }))).toBe(false)
  })

  it.each(['/api/data', '//api/data', '/%61pi/data', '/%ZZ/data'])('does not intercept API or ambiguous path %s', (path) => {
    const state = worker()
    const respondWith = vi.fn()
    state.handlers.get('fetch')({ request: { method: 'GET', url: origin + path, mode: 'navigate' }, respondWith })
    expect(respondWith).not.toHaveBeenCalled()
  })
})

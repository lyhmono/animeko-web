import { createServer } from 'node:http'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, UNAUTHORIZED_EVENT } from './api'

const realFetch = globalThis.fetch
let server

beforeEach(() => {
  vi.stubGlobal('window', new EventTarget())
})
afterEach(async () => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  if (server) {
    server.closeAllConnections()
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()))
    server = undefined
  }
})

/** 只监听 loopback，真的发头与半截 JSON；不替换 Response/body/AbortSignal。 */
async function stalledHttp(status) {
  server = createServer((_req, res) => {
    res.writeHead(status, { 'Content-Type': 'application/json', ETag: '"pending"' })
    res.write('{"')
    // 不 end：正文只能由客户端取消或夹具清理结束。
  })
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  const address = server.address()
  if (!address || typeof address === 'string') throw new Error('missing loopback address')
  let headersRead
  const headers = new Promise((resolve) => { headersRead = resolve })
  let signal
  const base = `http://127.0.0.1:${address.port}`
  vi.stubGlobal('fetch', async (input, init) => {
    signal = init.signal
    const response = await realFetch(new URL(String(input), base), init)
    headersRead()
    return response
  })
  return { headers, signal: () => signal }
}

describe('API body lifetime over real loopback HTTP', () => {
  it.each([
    { name: 'JSON', status: 200, start: () => api.searchBangumi('fixture') },
    { name: 'data text', status: 200, start: () => api.loadDataWithEtag() },
    { name: 'save ACK', status: 200, start: () => api.saveData({}, 'base') },
    { name: '401 error body', status: 401, start: () => api.sources() },
  ])('terminates a stalled $name body at the API deadline', async ({ start, status }) => {
    const lost = vi.fn()
    window.addEventListener(UNAUTHORIZED_EVENT, lost)
    const fixture = await stalledHttp(status)
    // 网络与正文真实；只让应用的 60 秒截止时钟跳转，避免回归每次等待一分钟。
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const outcome = start().then(() => 'success', (error) => error)
    await fixture.headers
    await vi.advanceTimersByTimeAsync(60_001)
    expect(fixture.signal().aborted).toBe(true)
    expect(await outcome).toEqual(expect.objectContaining({ message: expect.stringContaining('请求超时') }))
    expect(lost).not.toHaveBeenCalled()
  })

  it('releases a real response body when the caller aborts after headers', async () => {
    const fixture = await stalledHttp(200)
    const controller = new AbortController()
    const outcome = api.sourceSearch('fixture', 'q', controller.signal)
      .then(() => 'success', (error) => error)
    await fixture.headers
    controller.abort()
    expect(await outcome).toEqual(expect.objectContaining({ name: 'AbortError' }))
    expect(fixture.signal().aborted).toBe(true)
  })
})

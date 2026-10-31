import { afterEach, describe, expect, it, vi } from 'vitest'
import { elements, HookHarness, settle } from '../src/test/uiHarness'
import { deferred, effect, VIDEO } from './playerTestHarness'

const imports = vi.hoisted(() => ({ count: 0 }))
vi.mock('react', async (importOriginal) => ({
  ...await importOriginal<typeof import('react')>(),
  ...(await import('../src/test/uiHarness')).testHooks,
}))
vi.mock('hls.js', () => { imports.count++; return { default: HlsDouble } })

class HlsDouble {
  static Events = { ERROR: 'hlsError' }
  static ErrorTypes = { MEDIA_ERROR: 'mediaError' }
  static ErrorDetails = { MANIFEST_LOAD_ERROR: 'manifestLoadError' }
  static instances: HlsDouble[] = []
  static isSupported() { return true }
  listeners = new Map<string, (...args: any[]) => void>()
  loadSource = vi.fn()
  attachMedia = vi.fn()
  recoverMediaError = vi.fn()
  destroy = vi.fn()
  constructor(public config: unknown) { HlsDouble.instances.push(this) }
  on(event: string, handler: (...args: any[]) => void) { this.listeners.set(event, handler) }
  emit(data: object) { this.listeners.get('hlsError')?.('hlsError', data) }
}

class MediaDouble extends EventTarget {
  src = ''
  currentSrc = ''
  currentTime = 0
  duration = 300
  readyState = 0
  seeking = false
  error = null
  paused = true
  volume = 1
  muted = false
  playbackRate = 1
  style = { filter: '' }
  buffered = { length: 0 }
  constructor(private nativeHls = false) { super() }
  canPlayType() { return this.nativeHls ? 'maybe' : '' }
  removeAttribute() { this.src = ''; this.currentSrc = '' }
  load() { this.readyState = 0; this.currentTime = 0 }
  play = vi.fn(async () => {})
}

function options() {
  return { isCurrent: () => true, onHlsActive: vi.fn(), onError: vi.fn(),
    fetchMedia: vi.fn<typeof fetch>(async () => new Response('', { headers: { 'Content-Type': 'video/mp4' } })) }
}
async function playback() {
  // Baseline has no independent loading boundary; missing functionality is asserted, not made up.
  const module = await vi.importActual<any>('../src/lib/playback').catch(() => ({}))
  expect(typeof module.attachPlayback).toBe('function')
  return module
}

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); HlsDouble.instances.length = 0 })

describe('FP-11 hls.js loads only for an HLS resource that needs it', () => {
  it('the actual player route leaves hls.js unloaded for MP4 and native HLS', async () => {
    vi.useFakeTimers()
    const Player = (await import('../src/components/VideoPlayer')).default
    expect(imports.count).toBe(0)
    const document = new EventTarget()
    Object.assign(document, { activeElement: null, pictureInPictureEnabled: false })
    vi.stubGlobal('document', document)
    vi.stubGlobal('location', new URL('https://site.test/'))
    const window = new EventTarget()
    vi.stubGlobal('window', window)
    for (const [src, native] of [['https://media.test/v.mp4', false], ['https://media.test/v.m3u8', true]] as const) {
      const harness = new HookHarness()
      const media = new MediaDouble(native)
      const tree = harness.render(() => Player({ src }))
      elements(tree).find((element) => element.type === 'video')!.props.ref.current = media
      elements(tree).find((element) => element.props.className === 'video-shell')!.props.ref.current = { contains: () => false }
      harness.commit()
      expect(media.src).toBe(src)
      expect(imports.count).toBe(0)
      harness.unmount()
    }
  })

  it('does not import HLS for direct or proxied binary media or native HLS', async () => {
    const { attachPlayback } = await playback()
    const load = vi.fn(async () => ({ default: HlsDouble }))
    for (const [src, native] of [
      ['https://media.test/a.mp4?referer=index.m3u8', false],
      ['/api/proxy?url=' + encodeURIComponent('https://media.test/a.webm'), false],
      ['https://media.test/master.m3u8', true],
    ] as const) {
      const media = new MediaDouble(native)
      const dispose = attachPlayback(media, src, options(), load)
      expect(media.src).toBe(src)
      expect(load).not.toHaveBeenCalled()
      dispose()
    }
  })

  it('does not attach a delayed import after the generation is disposed or changed', async () => {
    const { attachPlayback } = await playback()
    const pending = deferred<{ default: typeof HlsDouble }>()
    const load = vi.fn(() => pending.promise)
    const context = options()
    const dispose = attachPlayback(new MediaDouble(), 'https://media.test/a.m3u8', context, load)
    dispose()
    pending.resolve({ default: HlsDouble })
    await settle()
    expect(load).toHaveBeenCalledOnce()
    expect(HlsDouble.instances).toHaveLength(0)
    expect(context.onError).not.toHaveBeenCalled()
  })

  it('keeps one media recovery and destroys the active engine exactly once', async () => {
    const { attachPlayback } = await playback()
    const context = options()
    const dispose = attachPlayback(new MediaDouble(), 'https://media.test/a.m3u8', context,
      async () => ({ default: HlsDouble }))
    await settle()
    const hls = HlsDouble.instances[0]
    expect(hls).toBeDefined()
    hls.emit({ fatal: true, type: 'mediaError', details: 'decodeFailure' })
    hls.emit({ fatal: true, type: 'mediaError', details: 'decodeFailure' })
    expect(hls.recoverMediaError).toHaveBeenCalledOnce()
    expect(context.onError).toHaveBeenCalledOnce()
    dispose()
    dispose()
    expect(hls.destroy).toHaveBeenCalledOnce()
    hls.emit({ fatal: true, type: 'networkError', details: 'manifestLoadError', response: { code: 401 } })
    expect(context.onError).toHaveBeenCalledOnce()
  })

  it('identifies a proxy session error body without trusting a direct upstream response', async () => {
    const { attachPlayback } = await playback()
    vi.stubGlobal('location', new URL('https://site.test/'))
    for (const [src, expected] of [
      ['/api/proxy?url=' + encodeURIComponent('https://media.test/a.m3u8') + '&sid=test', 'PROXY_SESSION_EXPIRED'],
      ['https://media.test/a.m3u8', undefined],
    ] as const) {
      const context = options()
      const dispose = attachPlayback(new MediaDouble(), src, context, async () => ({ default: HlsDouble }))
      await settle()
      HlsDouble.instances.slice(-1)[0].emit({ fatal: true, type: 'networkError', details: 'manifestLoadError',
        response: { code: 410 }, networkDetails: { responseText: '{"error":"PROXY_SESSION_EXPIRED"}' } })
      expect(context.onError.mock.calls[0][1]).toBe(410)
      expect(context.onError.mock.calls[0][2]?.code).toBe(expected)
      dispose()
    }
  })

  it('uses actual MIME evidence for extensionless HLS but never imports for extensionless MP4', async () => {
    const { attachPlayback } = await playback()
    for (const type of ['application/vnd.apple.mpegurl', 'video/mp4']) {
      const context = options()
      context.fetchMedia.mockResolvedValue(new Response('#EXTM3U', { headers: { 'Content-Type': type } }))
      const load = vi.fn(async () => ({ default: HlsDouble }))
      const media = new MediaDouble()
      const dispose = attachPlayback(media, '/api/proxy?url=' + encodeURIComponent('https://media.test/play?id=5'), context, load)
      await settle()
      expect(context.fetchMedia).toHaveBeenCalledOnce()
      expect(load).toHaveBeenCalledTimes(type === 'video/mp4' ? 0 : 1)
      if (type === 'video/mp4') expect(media.src).toContain('/api/proxy?')
      dispose()
    }
  })
})

describe('FP-11 loading generation ownership at the layout boundary', () => {
  it('invalidates an old pending import before the next passive load cleanup has run', async () => {
    const { attachPlayback } = await playback()
    const pending = deferred<{ default: typeof HlsDouble }>()
    const mediaGenerationRef = { current: 1 }
    const readyGenerationRef = { current: 1 }
    const context = { ...options(), isCurrent: () => mediaGenerationRef.current === 1 }
    const dispose = attachPlayback(new MediaDouble(), 'https://media.test/old.m3u8', context, () => pending.promise)
    // Execute the real layout invalidator, then resolve import before passive cleanup.
    effect(VIDEO, 'readyGenerationRef.current = -1', { mediaGenerationRef, readyGenerationRef }, 'useLayoutEffect')()
    pending.resolve({ default: HlsDouble })
    await settle()
    expect(HlsDouble.instances).toHaveLength(0)
    dispose()
  })

  it('aborts an extensionless MIME probe and releases its timer immediately on disposal', async () => {
    vi.useFakeTimers()
    const { attachPlayback } = await playback()
    const response = deferred<Response>()
    const context = options()
    context.fetchMedia.mockReturnValue(response.promise)
    const load = vi.fn(async () => ({ default: HlsDouble }))
    const dispose = attachPlayback(new MediaDouble(), 'https://media.test/stream', context, load)
    const signal = context.fetchMedia.mock.calls[0][1]?.signal
    expect(vi.getTimerCount()).toBe(1)
    dispose()
    expect(signal?.aborted).toBe(true)
    expect(vi.getTimerCount()).toBe(0)
    response.resolve(new Response('#EXTM3U', { headers: { 'Content-Type': 'application/vnd.apple.mpegurl' } }))
    await settle()
    expect(load).not.toHaveBeenCalled()
  })
})

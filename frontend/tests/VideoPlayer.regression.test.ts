import { afterEach, describe, expect, it, vi } from 'vitest'
import { callback, deferred, effect, effectDependencies, PLAYER, VIDEO } from './playerTestHarness'
import { attachPlayback } from '../src/lib/playback'

function canPlayScope(src: string, currentSrc: string) {
  const play = vi.fn(async () => {})
  const scope = {
    videoRef: { current: { src, currentSrc, readyState: 4, play } },
    autoplayTriedRef: { current: false }, resumeGateRef: { current: false },
    mediaGenerationRef: { current: 1 }, readyGenerationRef: { current: -1 },
    setAutoplayBlocked: vi.fn(), setBuffering: vi.fn(),
  }
  const onCanPlay = callback<() => void>(VIDEO, 'onCanPlay', scope)
  return { scope, play, onCanPlay }
}

afterEach(() => vi.useRealTimers())

describe('FP-02 selected resources include ManagedMediaSource children', () => {
  it('autoplays a selected source child without a video src attribute', () => {
    const { play, onCanPlay, scope } = canPlayScope('', 'blob:managed-media-source')
    onCanPlay()
    onCanPlay()
    expect(play).toHaveBeenCalledOnce()
    expect(scope.autoplayTriedRef.current).toBe(true)
  })

  it('shows the blocked-play action when MMS autoplay is rejected', async () => {
    const { play, onCanPlay, scope } = canPlayScope('', 'blob:managed-media-source')
    play.mockRejectedValue(new Error('NotAllowedError'))
    onCanPlay()
    await Promise.resolve()
    expect(scope.setAutoplayBlocked).toHaveBeenCalledWith(true)
  })

  it('still waits for a selected resource and the user resume decision', () => {
    const empty = canPlayScope('', '')
    empty.onCanPlay()
    expect(empty.play).not.toHaveBeenCalled()
    const gated = canPlayScope('', 'blob:managed-media-source')
    gated.scope.resumeGateRef.current = true
    gated.onCanPlay()
    expect(gated.play).not.toHaveBeenCalled()
    expect(gated.scope.autoplayTriedRef.current).toBe(false)
  })

  it('does not consume a user resume request before an asynchronous HLS attach is ready', () => {
    const { scope, play, onCanPlay } = canPlayScope('', '')
    scope.videoRef.current.readyState = 0
    effect(VIDEO, 'if (!playRequest) return', { ...scope, playRequest: 1 })()
    expect(play).not.toHaveBeenCalled()
    expect(scope.autoplayTriedRef.current).toBe(false)
    scope.videoRef.current.currentSrc = 'blob:new-managed-source'
    scope.videoRef.current.readyState = 4
    onCanPlay()
    expect(play).toHaveBeenCalledOnce()
  })

  it('does not show an old autoplay rejection over a newer media generation', async () => {
    const { scope, play, onCanPlay } = canPlayScope('', 'blob:old-managed-source')
    const pending = deferred<void>()
    play.mockReturnValue(pending.promise)
    onCanPlay()
    scope.mediaGenerationRef.current++
    pending.reject(new Error('old NotAllowedError'))
    await Promise.resolve()
    expect(scope.setAutoplayBlocked).not.toHaveBeenCalledWith(true)
  })
})

describe('FP-03 native failures are final and same-address retries reload', () => {
  function nativeErrorScope(fallback = true) {
    const proxy = '/api/proxy?url=https%3A%2F%2Fmedia.test%2Fbroken.mp4'
    const scope = {
      video: { error: { code: 3 } },
      useFallbackRef: { current: fallback }, fallbackSrcRef: { current: proxy },
      activeSrcRef: { current: fallback ? proxy : 'https://media.test/broken.mp4' },
      hlsActiveRef: { current: false }, readyGenerationRef: { current: 1 }, setBuffering: vi.fn(),
      setUseFallback: vi.fn(), setError: vi.fn(), onErrorRef: { current: vi.fn() },
    }
    return { scope, onMediaError: callback<() => void>(VIDEO, 'onMediaError', scope) }
  }

  it('does not invent HTTP 410 or an endless reparsing overlay for native decode errors', () => {
    const { scope, onMediaError } = nativeErrorScope()
    onMediaError()
    onMediaError()
    expect(scope.onErrorRef.current.mock.calls).toHaveLength(2)
    expect(scope.onErrorRef.current.mock.calls[0][1]).toBeUndefined()
    expect(scope.setError.mock.calls.slice(-1)[0][0]).toContain('MEDIA_ERROR_3')
    expect(scope.setError.mock.calls.slice(-1)[0][0]).not.toContain('正在重新解析')
  })

  it('uses a distinct fallback at most once before reporting a final native error', () => {
    const { scope, onMediaError } = nativeErrorScope(false)
    onMediaError()
    expect(scope.setUseFallback).toHaveBeenCalledOnce()
    expect(scope.onErrorRef.current).not.toHaveBeenCalled()
    scope.useFallbackRef.current = true
    scope.activeSrcRef.current = scope.fallbackSrcRef.current
    onMediaError()
    onMediaError()
    expect(scope.setUseFallback).toHaveBeenCalledOnce()
    expect(scope.onErrorRef.current.mock.calls.every((call) => call[1] === undefined)).toBe(true)
  })

  it('submitting the same manual URL creates two actual media load generations', () => {
    let reloadRequest = 0
    const url = 'https://media.test/same.mp4'
    const manualScope = {
      manualUrl: url, title: 'Manual', animeName: '', ep: '',
      proxyUrlFor: (media: string) => `/api/proxy?${new URLSearchParams({ url: media })}`,
      directUrlFor: (media: string) => media, getDirectPlayback: () => true,
      setFallbackSrc: vi.fn(), setSrc: vi.fn(), setError: vi.fn(),
      setStartAt: vi.fn(), setResumeTime: vi.fn(), setNextCountdown: vi.fn(), setAutoNext: vi.fn(),
      successorRef: { current: null }, chainSeqRef: { current: 0 },
      chainAbortRef: { current: null }, resolveSeqRef: { current: 0 }, resolveAbortRef: { current: null },
      setResolving: vi.fn(),
      addRecent: vi.fn(), setSearchParams: vi.fn(),
      setReloadRequest: (update: (n: number) => number) => { reloadRequest = update(reloadRequest) },
    }
    const submit = callback<() => void>(PLAYER, 'playManualUrl', manualScope)
    const assignments: string[] = []
    const video = {
      currentTime: 0,
      set src(value: string) { assignments.push(value) },
      removeAttribute: vi.fn(), load: vi.fn(),
    }
    const loadScope = {
      videoRef: { current: video }, activeSrc: url, playKey: 'manual|' + url, reloadRequest,
      loadedPlayKeyRef: { current: 'manual|' + url }, currentPlayKeyRef: { current: 'manual|' + url },
      hlsActiveRef: { current: false }, mediaRecoveredRef: { current: false },
      setError: vi.fn(), startAtAppliedRef: { current: false }, autoplayTriedRef: { current: false },
      setAutoplayBlocked: vi.fn(), applyBrightness: vi.fn(), resumeAtRef: { current: 0 },
      mediaGenerationRef: { current: 0 }, readyGenerationRef: { current: -1 },
      attachPlayback, setPlaying: vi.fn(), setCurrent: vi.fn(), setDuration: vi.fn(), setBufferedEnd: vi.fn(), setBuffering: vi.fn(),
    }
    const runLoad = () => effect(VIDEO, 'loadedPlayKeyRef.current = playKey', loadScope)()
    let cleanup = runLoad()
    for (let attempt = 0; attempt < 2; attempt++) {
      const previous = effectDependencies(VIDEO, 'loadedPlayKeyRef.current = playKey', loadScope)
      submit()
      loadScope.reloadRequest = reloadRequest
      const next = effectDependencies(VIDEO, 'loadedPlayKeyRef.current = playKey', loadScope)
      expect(next.some((value, index) => !Object.is(value, previous[index]))).toBe(true)
      if (typeof cleanup === 'function') cleanup()
      cleanup = runLoad()
    }
    expect(assignments).toEqual([url, url, url])
    if (typeof cleanup === 'function') cleanup()
  })
})

describe('FP-06 progress writes belong to a ready media generation', () => {
  function timeScope(time = 0) {
    return {
      video: { currentTime: time, duration: 300, currentSrc: 'blob:current-media', readyState: 4, seeking: false, error: null },
      setCurrent: vi.fn(), onProgressRef: { current: vi.fn() },
      resumeGateRef: { current: false }, resumeAtRef: { current: 0 },
      startAtRef: { current: 0 }, startAtAppliedRef: { current: true },
      mediaGenerationRef: { current: 1 }, readyGenerationRef: { current: 1 },
    }
  }

  it('suppresses cleanup resets, pending resume, stale generations and unready media', () => {
    const cases = [
      (scope: ReturnType<typeof timeScope>) => { scope.resumeGateRef.current = true },
      (scope: ReturnType<typeof timeScope>) => { scope.resumeAtRef.current = 120; scope.startAtAppliedRef.current = false },
      (scope: ReturnType<typeof timeScope>) => { scope.startAtRef.current = 120; scope.startAtAppliedRef.current = false },
      (scope: ReturnType<typeof timeScope>) => { scope.readyGenerationRef.current = -1 },
      (scope: ReturnType<typeof timeScope>) => { scope.video.readyState = 0; scope.video.currentSrc = '' },
      (scope: ReturnType<typeof timeScope>) => { scope.video.seeking = true },
    ]
    for (const prepare of cases) {
      const scope = timeScope()
      prepare(scope)
      callback<() => void>(VIDEO, 'onTime', scope)()
      expect(scope.onProgressRef.current).not.toHaveBeenCalled()
    }
  })

  it('still saves deliberate zero and backward seeks after readiness and resume selection', () => {
    const scope = timeScope()
    const onTime = callback<() => void>(VIDEO, 'onTime', scope)
    onTime()
    scope.video.currentTime = 120
    onTime()
    scope.video.currentTime = 30
    onTime()
    expect(scope.onProgressRef.current.mock.calls).toEqual([[0, 300], [120, 300], [30, 300]])
  })

  it('closes the write gate before load emits a reset and preserves the last valid resume point', () => {
    const scope = timeScope(120)
    const onTime = callback<() => void>(VIDEO, 'onTime', scope)
    const video = {
      ...scope.video, src: '',
      removeAttribute: vi.fn(),
      load: () => {
        scope.video.currentTime = 0
        scope.video.readyState = 0
        scope.video.currentSrc = ''
        onTime()
      },
    }
    const loadScope = {
      ...scope, videoRef: { current: video }, activeSrc: 'https://media.test/current.mp4', reloadRequest: 0,
      playKey: 'fixture|current', loadedPlayKeyRef: { current: 'fixture|current' }, currentPlayKeyRef: { current: 'fixture|current' },
      hlsActiveRef: { current: false }, mediaRecoveredRef: { current: false }, setError: vi.fn(),
      autoplayTriedRef: { current: false }, setAutoplayBlocked: vi.fn(), applyBrightness: vi.fn(),
      attachPlayback, setPlaying: vi.fn(), setCurrent: vi.fn(), setDuration: vi.fn(), setBufferedEnd: vi.fn(), setBuffering: vi.fn(),
    }
    const cleanup = effect(VIDEO, 'loadedPlayKeyRef.current = playKey', loadScope)()
    scope.readyGenerationRef.current = scope.mediaGenerationRef.current
    if (typeof cleanup !== 'function') throw new Error('Expected real media cleanup')
    cleanup()
    // A failed new source never emits canplay; the reset must not replace persisted 120s with 0.
    onTime()
    expect(scope.onProgressRef.current).not.toHaveBeenCalled()
    expect(scope.resumeAtRef.current).toBe(120)
    expect(scope.readyGenerationRef.current).not.toBe(scope.mediaGenerationRef.current)
  })

  it('does not clear a persisted breakpoint or queue auto-next from a cleanup ended event', () => {
    const pageScope = { source: 'fixture', playUrl: 'https://source.test/current', target: null,
      clearProgress: vi.fn(), resolveChain: vi.fn(async () => {}), setNextCountdown: vi.fn() }
    const scope = { ...timeScope(300), setPlaying: vi.fn(),
      onEndedRef: { current: callback<() => void>(PLAYER, 'handleEnded', pageScope) } }
    const onEnd = callback<() => void>(VIDEO, 'onEnd', scope)
    scope.readyGenerationRef.current = -1
    onEnd()
    expect(pageScope.clearProgress).not.toHaveBeenCalled()
    expect(pageScope.resolveChain).not.toHaveBeenCalled()
    scope.readyGenerationRef.current = scope.mediaGenerationRef.current
    scope.resumeGateRef.current = true
    onEnd()
    expect(pageScope.clearProgress).not.toHaveBeenCalled()
    scope.resumeGateRef.current = false
    onEnd()
    expect(pageScope.clearProgress).toHaveBeenCalledOnce()
    expect(pageScope.resolveChain).toHaveBeenCalledOnce()
  })
})

describe('FP-07 keyboard actions respect focused native controls', () => {
  class ElementDouble {
    tagName = 'INPUT'
    isContentEditable = false
    constructor(public type = 'range', public inside = true) {}
    closest() { return null }
  }
  function keyScope() {
    const scope = {
      HTMLElement: ElementDouble, videoRef: { current: {} },
      containerRef: { current: { contains: (target: ElementDouble) => target.inside } },
      isTypingTarget: callback(VIDEO, 'isTypingTarget', { HTMLElement: ElementDouble }),
      seekBy: vi.fn(), changeVolume: vi.fn(), togglePlay: vi.fn(), setMuted: vi.fn(), toggleFullscreen: vi.fn(),
      revealControls: vi.fn(),
    }
    return { scope, onKey: callback<(event: object) => void>(VIDEO, 'onKey', scope) }
  }

  it('leaves all range arrow keys to the native slider without preventDefault or global seek/volume', () => {
    for (const key of ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown']) {
      const { scope, onKey } = keyScope()
      const preventDefault = vi.fn()
      onKey({ target: new ElementDouble(), key, preventDefault })
      expect(preventDefault).not.toHaveBeenCalled()
      expect(scope.seekBy).not.toHaveBeenCalled()
      expect(scope.changeVolume).not.toHaveBeenCalled()
    }
  })

  it('keeps K on a range but does not hijack inputs or keyboard focus outside the player', () => {
    const { scope, onKey } = keyScope()
    onKey({ target: new ElementDouble(), key: 'K', preventDefault: vi.fn() })
    expect(scope.togglePlay).toHaveBeenCalledOnce()
    onKey({ target: new ElementDouble('text'), key: 'K', preventDefault: vi.fn() })
    onKey({ target: new ElementDouble('range', false), key: 'K', preventDefault: vi.fn() })
    expect(scope.togglePlay).toHaveBeenCalledOnce()
  })

  it('retains arrow shortcuts when the player shell itself holds focus', () => {
    const { scope, onKey } = keyScope()
    const shell = new ElementDouble('')
    shell.tagName = 'DIV'
    onKey({ target: shell, key: 'ArrowRight', preventDefault: vi.fn() })
    onKey({ target: shell, key: 'ArrowUp', preventDefault: vi.fn() })
    expect(scope.seekBy).toHaveBeenCalledWith(5)
    expect(scope.changeVolume).toHaveBeenCalledWith(0.05)
  })
})

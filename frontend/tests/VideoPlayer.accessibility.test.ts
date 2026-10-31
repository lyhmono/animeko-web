import { afterEach, describe, expect, it, vi } from 'vitest'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import VideoPlayer from '../src/components/VideoPlayer'
import { callback, VIDEO } from './playerTestHarness'

afterEach(() => vi.useRealTimers())

describe('FP-08 controls remain available to keyboard focus', () => {
  it('does not hide focused controls, then hides after focus leaves the player', () => {
    vi.useFakeTimers()
    let focusedInside = true
    const scope = {
      setControlsVisible: vi.fn(), hideTimerRef: { current: undefined },
      videoRef: { current: { paused: false } }, document: { activeElement: {} },
      containerRef: { current: { contains: () => focusedInside } },
    }
    const reveal = callback<() => void>(VIDEO, 'revealControls', scope)
    reveal()
    vi.advanceTimersByTime(3000)
    expect(scope.setControlsVisible).not.toHaveBeenCalledWith(false)
    focusedInside = false
    reveal()
    vi.advanceTimersByTime(3000)
    expect(scope.setControlsVisible).toHaveBeenLastCalledWith(false)
  })

  it('reveals controls when a keyboard or native action pauses playback', () => {
    const scope = { setPlaying: vi.fn(), revealControls: vi.fn() }
    callback<() => void>(VIDEO, 'onPause', scope)()
    expect(scope.setPlaying).toHaveBeenCalledWith(false)
    expect(scope.revealControls).toHaveBeenCalledOnce()
  })

  it('offers a named focusable shell for reaching hidden controls without a pointer', () => {
    const html = renderToStaticMarkup(createElement(VideoPlayer, { src: 'https://media.test/clip.mp4' }))
    expect(html).toMatch(/<div[^>]*class="video-shell[^>]*tabindex="0"/)
    expect(html).toContain('aria-label="视频播放器"')
  })
})

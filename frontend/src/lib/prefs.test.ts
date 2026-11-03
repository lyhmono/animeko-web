import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getPlaybackSettings, savePlaybackSettings } from './prefs'

const store = new Map<string, string>()

beforeEach(() => {
  store.clear()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => {
      store.set(key, value)
    },
  })
})

describe('playback settings', () => {
  it('defaults to auto-next and a 90s opening skip', () => {
    expect(getPlaybackSettings()).toEqual({ autoNext: true, opSkipSeconds: 90 })
  })

  it('persists partial updates and keeps unrelated fields', () => {
    savePlaybackSettings({ autoNext: false })
    expect(getPlaybackSettings()).toEqual({ autoNext: false, opSkipSeconds: 90 })
    savePlaybackSettings({ opSkipSeconds: 120 })
    expect(getPlaybackSettings()).toEqual({ autoNext: false, opSkipSeconds: 120 })
  })

  it('clamps invalid skip seconds but keeps the stored auto-next flag', () => {
    savePlaybackSettings({ autoNext: false, opSkipSeconds: 9999 })
    expect(getPlaybackSettings()).toEqual({ autoNext: false, opSkipSeconds: 90 })
  })

  it('falls back to defaults for broken payloads', () => {
    store.set('animeko-web:playback-settings', '{bad json')
    expect(getPlaybackSettings()).toEqual({ autoNext: true, opSkipSeconds: 90 })
  })
})
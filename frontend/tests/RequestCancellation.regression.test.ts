import { describe, expect, it, vi } from 'vitest'
import { callback, deferred, DETAIL, effect, PLAYER, SUBJECT } from './playerTestHarness'
import { pickBestHit, pickEpisode } from '../src/lib/titleMatch'
import { confirmedBangumiEpisode } from '../src/lib/episodeProgress'
import { nextBangumiEpisode, sourceEpisodeIsSpecial } from '../src/lib/episodeProgress'
import type { EpisodesResult, SubjectHit, VideoSource } from '../src/types'
import { settle } from '../src/test/uiHarness'

const episodes: EpisodesResult = { channels: ['正片'], groups: [], episodes: [] }

describe('FP-10 SourceDetailPage cancels its in-flight list request', () => {
  it('aborts on cleanup while still discarding a response from a non-cooperative transport', async () => {
    const pending = deferred<EpisodesResult>()
    const sourceEpisodes = vi.fn<(name: string, url: string, signal?: AbortSignal) => Promise<EpisodesResult>>(() => pending.promise)
    const scope = {
      name: 'fixture', subjectUrl: 'https://source.test/subject',
      api: { sourceEpisodes }, setResult: vi.fn(), setError: vi.fn(), setChannel: vi.fn(), setFaved: vi.fn(),
      isSourceFavorite: () => false, errMsg: String,
    }
    const cleanup = effect(DETAIL, 'let cancelled = false', scope)()
    const signal = sourceEpisodes.mock.calls[0]?.[2] as AbortSignal | undefined
    expect(signal).toBeInstanceOf(AbortSignal)
    if (typeof cleanup !== 'function') throw new Error('Expected request cleanup')
    cleanup()
    expect(signal?.aborted).toBe(true)
    pending.resolve(episodes)
    await pending.promise
    await Promise.resolve()
    expect(scope.setResult.mock.calls).toEqual([[null]])
    expect(scope.setError.mock.calls).toEqual([['']])
  })
})

describe('FP-10 SubjectPage cancels each complete matching generation', () => {
  function matchScope(search: Promise<SubjectHit[]>, list = Promise.resolve(episodes)) {
    let flow: unknown = {}
    const scope = {
      subject: { name: 'Show', name_cn: '' }, matchGenRef: { current: 0 },
      matchAbortRef: { current: null as AbortController | null },
      setFlow: vi.fn((update: unknown) => { flow = typeof update === 'function' ? update(flow) : update }),
      api: {
        sourceSearch: vi.fn<(name: string, q: string, signal?: AbortSignal) => Promise<SubjectHit[]>>(() => search),
        sourceEpisodes: vi.fn<(name: string, url: string, signal?: AbortSignal) => Promise<EpisodesResult>>(() => list),
      },
      pickBestHit, pickEpisode, confirmedBangumiEpisode, goPlay: vi.fn(), nextOf: vi.fn(), errMsg: String,
    }
    return { scope, close: callback<() => void>(SUBJECT, 'closeFlow', scope),
      match: callback<(source: string, ep: object, auto: boolean) => Promise<void>>(SUBJECT, 'matchSource', scope) }
  }

  it('aborts cancelled search and never queues episodes even if the transport resolves late', async () => {
    const search = deferred<SubjectHit[]>()
    const { scope, close, match } = matchScope(search.promise)
    const pending = match('fixture', { sort: 5, type: 0 }, true)
    const signal = scope.api.sourceSearch.mock.calls[0][2]
    expect(signal).toBeInstanceOf(AbortSignal)
    close()
    expect(signal?.aborted).toBe(true)
    search.resolve([{ name: 'Show', url: 'https://source.test/subject' }])
    await pending
    expect(scope.api.sourceEpisodes).not.toHaveBeenCalled()
    expect(scope.goPlay).not.toHaveBeenCalled()
  })

  it('uses the same signal for episodes and search, and aborts selection before navigation', async () => {
    const list = deferred<EpisodesResult>()
    const { scope, close, match } = matchScope(Promise.resolve([{ name: 'Show', url: 'https://source.test/subject' }]), list.promise)
    const pending = match('fixture', { sort: 5, type: 0 }, true)
    await settle()
    expect(scope.api.sourceEpisodes).toHaveBeenCalledOnce()
    const signal = scope.api.sourceEpisodes.mock.calls[0]?.[2]
    expect(signal).toBeInstanceOf(AbortSignal)
    expect(signal).toBe(scope.api.sourceSearch.mock.calls[0][2])
    close()
    expect(signal?.aborted).toBe(true)
    list.resolve({ ...episodes, episodes: [{ name: '第5话', sort: '5', sortNum: 5, channel: null, playUrl: 'https://source.test/play5' }] })
    await pending
    expect(scope.goPlay).not.toHaveBeenCalled()
  })

  it('aborts the old search when a new matching generation is started', async () => {
    const search = deferred<SubjectHit[]>()
    const { scope, close, match } = matchScope(search.promise)
    const first = match('first', { sort: 5, type: 0 }, true)
    const oldSignal = scope.api.sourceSearch.mock.calls[0][2]
    const second = match('second', { sort: 5, type: 0 }, true)
    expect(oldSignal?.aborted).toBe(true)
    expect(scope.api.sourceSearch.mock.calls[1][2]?.aborted).toBe(false)
    close()
    search.resolve([])
    await Promise.all([first, second])
    expect(scope.api.sourceEpisodes).not.toHaveBeenCalled()
  })

  it('aborts metadata/source reads and any active match when the subject effect cleans up', async () => {
    const data = deferred<object>()
    const sources = deferred<object[]>()
    const scope = {
      subjectId: 77, matchGenRef: { current: 0 }, matchAbortRef: { current: new AbortController() },
      setError: vi.fn(), setSubject: vi.fn(), setFaved: vi.fn(), setWatchedEp: vi.fn(), setSourcesError: vi.fn(),
      setSources: vi.fn(), setFlow: vi.fn(), isFavorite: () => false, getSubjectProgress: () => null, errMsg: String,
      api: { bangumiSubject: vi.fn<(id: number, signal?: AbortSignal) => Promise<object>>(() => data.promise),
        sources: vi.fn<(signal?: AbortSignal) => Promise<object[]>>(() => sources.promise) },
    }
    const cleanup = effect(SUBJECT, 'let cancelled = false', scope)()
    const dataSignal = scope.api.bangumiSubject.mock.calls[0][1]
    const sourcesSignal = scope.api.sources.mock.calls[0][0]
    expect(dataSignal).toBeInstanceOf(AbortSignal)
    expect(sourcesSignal).toBe(dataSignal)
    if (typeof cleanup !== 'function') throw new Error('Expected subject cleanup')
    cleanup()
    expect(dataSignal?.aborted).toBe(true)
    data.resolve({ id: 77 })
    sources.resolve([])
    await Promise.all([data.promise, sources.promise])
    await Promise.resolve()
    expect(scope.setSubject.mock.calls).toEqual([[null]])
  })
})

describe('FP-10 PlayerPage cancels resolution and successor lookup', () => {
  function resolveScope(promise: Promise<VideoSource>) {
    return {
      source: 'fixture', playUrl: 'https://source.test/play5', title: 'Show', animeName: 'Show', ep: '5',
      resolveSeqRef: { current: 0 }, resolveAbortRef: { current: null as AbortController | null },
      resetForRef: { current: null }, retriedKeyRef: { current: null }, lastWriteRef: { current: null },
      api: { resolveVideo: vi.fn<(name: string, url: string, signal?: AbortSignal) => Promise<VideoSource>>(() => promise) },
      setError: vi.fn(), setFallbackSrc: vi.fn(), setSrc: vi.fn(), setResolving: vi.fn(), setReloadRequest: vi.fn(),
      getDirectPlayback: () => false, addRecent: vi.fn(), directUrlFor: (url: string) => url,
      proxyUrlFor: (url: string) => '/api/proxy?url=' + encodeURIComponent(url), errMsg: String,
    }
  }

  it('aborts resolver cleanup without adding recent history or changing the new media', async () => {
    const pending = deferred<VideoSource>()
    const scope = resolveScope(pending.promise)
    const cleanup = effect(PLAYER, 'let alive = true', scope)()
    const signal = scope.api.resolveVideo.mock.calls[0][2]
    expect(signal).toBeInstanceOf(AbortSignal)
    if (typeof cleanup !== 'function') throw new Error('Expected resolver cleanup')
    cleanup()
    expect(signal?.aborted).toBe(true)
    pending.resolve({ url: 'https://media.test/old.mp4', proxyPath: '/api/proxy?url=old' })
    await settle()
    expect(scope.setSrc).not.toHaveBeenCalled()
    expect(scope.addRecent).not.toHaveBeenCalled()
    expect(scope.setError.mock.calls).toEqual([['']])
  })

  it('cancels pending source work immediately when the user submits manual media', async () => {
    const pending = deferred<VideoSource>()
    const scope = {
      ...resolveScope(pending.promise), manualUrl: 'https://media.test/manual.mp4',
      setStartAt: vi.fn(), setResumeTime: vi.fn(), setNextCountdown: vi.fn(), setAutoNext: vi.fn(),
      successorRef: { current: null }, chainSeqRef: { current: 0 }, chainAbortRef: { current: new AbortController() },
      setSearchParams: vi.fn(),
    }
    effect(PLAYER, 'let alive = true', scope)()
    const resolverSignal = scope.api.resolveVideo.mock.calls[0][2]
    const chainSignal = scope.chainAbortRef.current.signal
    callback<() => void>(PLAYER, 'playManualUrl', scope)()
    expect(resolverSignal?.aborted).toBe(true)
    expect(chainSignal.aborted).toBe(true)
    pending.resolve({ url: 'https://media.test/old.mp4', proxyPath: '/api/proxy?url=old' })
    await settle()
    expect(scope.setSrc.mock.calls).toHaveLength(1)
    expect(scope.addRecent.mock.calls).toHaveLength(1)
    expect(scope.addRecent.mock.calls[0][0].source).toBe('manual')
  })

  it('aborts chain lookup on effect cleanup and never starts a countdown from a late response', async () => {
    const pending = deferred<EpisodesResult>()
    const scope = {
      source: 'fixture', subjectUrl: 'https://source.test/subject', playUrl: 'https://source.test/play5',
      animeName: 'Show', title: 'Show', bep: '5',
      chainSeqRef: { current: 0 }, chainAbortRef: { current: null as AbortController | null },
      api: { sourceEpisodes: vi.fn<(name: string, url: string, signal?: AbortSignal) => Promise<EpisodesResult>>(() => pending.promise) },
      setAutoNext: vi.fn(), successorRef: { current: null }, setNextCountdown: vi.fn(),
      nextBangumiEpisode, sourceEpisodeIsSpecial,
    }
    const lookup = callback<() => Promise<void>>(PLAYER, 'resolveChain', scope)()
    const signal = scope.api.sourceEpisodes.mock.calls[0][2]
    expect(signal).toBeInstanceOf(AbortSignal)
    const cleanup = effect(PLAYER, 'chainAbortRef.current?.abort()', scope)()
    if (typeof cleanup !== 'function') throw new Error('Expected chain cleanup')
    cleanup()
    expect(signal?.aborted).toBe(true)
    pending.resolve({ ...episodes, episodes: [
      { name: '第5话', sort: '5', sortNum: 5, channel: null, playUrl: 'https://source.test/play5' },
      { name: '第6话', sort: '6', sortNum: 6, channel: null, playUrl: 'https://source.test/play6' },
    ] })
    await lookup
    expect(scope.setNextCountdown).not.toHaveBeenCalledWith(5)
    expect(scope.setAutoNext.mock.calls.every((call) => call[0] === null)).toBe(true)
  })
})

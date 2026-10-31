import { describe, expect, it, vi } from 'vitest'
import { callback, deferred, effect, jsxCallback, PLAYER } from './playerTestHarness'
import { isSpecialEpisodeName } from '../src/lib/titleMatch'
import { nextBangumiEpisode, sourceEpisodeIsSpecial } from '../src/lib/episodeProgress'
import type { EpisodeHit } from '../src/types'

type MediaErrorHandler = (message: string, status?: number, context?: { code?: string }) => void | Promise<void>

function mediaErrorScope(source = 'fixture') {
  const events: string[] = []
  const errors: string[] = []
  const nonce = { value: 0 }
  const scope = {
    source, playUrl: 'https://media.test/play/5',
    UNAUTHORIZED_EVENT: 'animeko-unauthorized',
    playGenerationRef: { current: 1 }, mediaErrorSeqRef: { current: 0 },
    mediaProbeAbortRef: { current: null as AbortController | null },
    retriedKeyRef: { current: null as string | null },
    probeSession: vi.fn<(signal?: AbortSignal) => Promise<{ authEnabled: boolean; authenticated: boolean }>>(async () => ({ authEnabled: true, authenticated: true })),
    window: { dispatchEvent: (event: Event) => { events.push(event.type) } },
    setError: (message: string) => { errors.push(message) },
    setResolveNonce: (update: (n: number) => number) => { nonce.value = update(nonce.value) },
  }
  const onError = jsxCallback<MediaErrorHandler>(PLAYER, 'onError', scope)
  return { events, errors, nonce, scope, onError }
}

describe('FP-01 media authentication is not site authentication', () => {
  it('keeps an upstream 401 in the player when the site session is valid', async () => {
    const { events, errors, scope, onError } = mediaErrorScope()
    await onError('上游拒绝媒体：401', 401)
    expect(scope.probeSession).toHaveBeenCalledOnce()
    expect(events).toEqual([])
    expect(errors.slice(-1)[0]).toBe('上游拒绝媒体：401')
  })

  it('broadcasts only after the site probe confirms an expired authenticated session', async () => {
    const { events, errors, scope, onError } = mediaErrorScope()
    const probe = deferred<{ authEnabled: boolean; authenticated: boolean }>()
    scope.probeSession.mockReturnValue(probe.promise)
    const pending = onError('上游拒绝媒体', 401)
    expect(events).toEqual([])
    probe.resolve({ authEnabled: true, authenticated: false })
    await pending
    expect(events).toEqual(['animeko-unauthorized'])
    expect(errors.slice(-1)[0]).toContain('登录已过期')
  })

  it('does not log out after a failed probe or with authentication disabled', async () => {
    const failed = mediaErrorScope()
    failed.scope.probeSession.mockRejectedValue(new Error('offline'))
    await failed.onError('媒体 401', 401)
    expect(failed.events).toEqual([])
    expect(failed.errors.slice(-1)[0]).toBe('媒体 401')
    const disabled = mediaErrorScope()
    disabled.scope.probeSession.mockResolvedValue({ authEnabled: false, authenticated: false })
    await disabled.onError('媒体 401', 401)
    expect(disabled.events).toEqual([])
  })

  it('discards the delayed probe after switching the playback generation', async () => {
    const { events, errors, scope, onError } = mediaErrorScope()
    const probe = deferred<{ authEnabled: boolean; authenticated: boolean }>()
    scope.probeSession.mockReturnValue(probe.promise)
    const pending = onError('旧媒体 401', 401)
    scope.playGenerationRef.current++
    probe.resolve({ authEnabled: true, authenticated: false })
    await pending
    expect(events).toEqual([])
    expect(errors).not.toContain('登录已过期，请重新登录')
  })

  it('does not reinterpret upstream 403/410 as an expired proxy session', async () => {
    const { nonce, errors, onError } = mediaErrorScope()
    await onError('源站 403', 403)
    await onError('源站 410', 410)
    expect(nonce.value).toBe(0)
    expect(errors.slice(-1)[0]).toBe('源站 410')
  })

  it('reparses an explicitly identified proxy session once, never for manual media', async () => {
    const named = mediaErrorScope()
    await named.onError('代理会话过期', 410, { code: 'PROXY_SESSION_EXPIRED' })
    await named.onError('代理再次失败', 403, { code: 'PROXY_SESSION_TARGET_MISMATCH' })
    expect(named.nonce.value).toBe(1)
    expect(named.errors.slice(-1)[0]).toBe('代理再次失败')
    const manual = mediaErrorScope('manual')
    await manual.onError('手动媒体失败', 410, { code: 'PROXY_SESSION_EXPIRED' })
    expect(manual.nonce.value).toBe(0)
    expect(manual.errors.slice(-1)[0]).toBe('手动媒体失败')
  })

  it('aborts the actual site probe at layout invalidation before an old health 401 can broadcast', async () => {
    const { events, scope, onError } = mediaErrorScope()
    const probe = deferred<{ authEnabled: boolean; authenticated: boolean }>()
    scope.probeSession.mockReturnValue(probe.promise)
    const pending = onError('旧媒体 401', 401)
    const signal = scope.probeSession.mock.calls[0][0]
    expect(signal).toBeInstanceOf(AbortSignal)
    effect(PLAYER, 'playGenerationRef.current++', scope, 'useLayoutEffect')()
    expect(signal?.aborted).toBe(true)
    probe.resolve({ authEnabled: true, authenticated: false })
    await pending
    expect(events).toEqual([])
  })

  it('cancels the old probe when a newer final media error replaces it', async () => {
    const { events, errors, scope, onError } = mediaErrorScope()
    const probe = deferred<{ authEnabled: boolean; authenticated: boolean }>()
    scope.probeSession.mockReturnValue(probe.promise)
    const pending = onError('旧媒体 401', 401)
    const signal = scope.probeSession.mock.calls[0][0]
    await onError('新的解码错误', undefined)
    expect(signal?.aborted).toBe(true)
    probe.resolve({ authEnabled: true, authenticated: false })
    await pending
    expect(events).toEqual([])
    expect(errors.slice(-1)[0]).toBe('新的解码错误')
  })
})

describe('FP-05 special episodes stop the entire inferred Bangumi chain', () => {
  function ep(name: string, sortNum: number, channel = '正片'): EpisodeHit {
    return { name, sortNum, sort: String(sortNum), channel, playUrl: 'https://source.test/' + encodeURIComponent(name) }
  }
  async function chain(list: EpisodeHit[], playUrl = list[0].playUrl, bep = '5') {
    let autoNext: { play: string; title: string; bep?: string } | null = null
    const successorRef = { current: null as { play: string; title: string; bep?: string } | null }
    const scope = {
      source: 'fixture', subjectUrl: 'https://source.test/subject', playUrl, bep, animeName: 'Show', title: 'Show',
      chainSeqRef: { current: 0 },
      chainAbortRef: { current: null },
      api: { sourceEpisodes: vi.fn(async () => ({ groups: [{ name: list[0].channel, episodes: list }], episodes: list })) },
      setAutoNext: (value: typeof autoNext) => { autoNext = value }, successorRef, setNextCountdown: vi.fn(),
      isSpecialEpisodeName, sourceEpisodeIsSpecial, nextBangumiEpisode,
    }
    await callback<() => Promise<void>>(PLAYER, 'resolveChain', scope)()
    return { autoNext: autoNext as { play: string; title: string; bep?: string } | null, successorRef, scope }
  }

  it('does not revive a stripped SP mapping in the precomputed successor URL', async () => {
    const { autoNext, successorRef } = await chain([ep('第5话', 5), ep('SP 特别篇', 6), ep('第6话', 7)])
    expect(autoNext?.bep).toBeUndefined()
    expect(successorRef.current?.bep).toBeUndefined()
    let assigned = ''
    callback<() => void>(PLAYER, 'navToNext', {
      target: autoNext, successorRef, subjectId: 77, subjectUrl: 'https://source.test/subject',
      nextToParams: callback(PLAYER, 'nextToParams', {}),
      window: { location: { assign: (url: string) => { assigned = url } } },
    })()
    const p = new URL(assigned, 'https://site.test').searchParams
    expect(p.has('bep')).toBe(false)
    expect(JSON.parse(p.get('next')!).bep).toBeUndefined()
    expect(p.get('subject')).toBe('77')
  })

  it('cannot infer from a special current episode even if a legacy URL still supplies bep', async () => {
    const { autoNext, successorRef } = await chain([ep('SP 特别篇', 6), ep('第6话', 7), ep('第7话', 8)], undefined, '6')
    expect(autoNext?.bep).toBeUndefined()
    expect(successorRef.current?.bep).toBeUndefined()
  })

  it('retains continuous ordinary mapping, but never infers through a source numbering gap', async () => {
    const normal = await chain([ep('第5话', 5), ep('第6话', 6), ep('第7话', 7)])
    expect(normal.autoNext?.bep).toBe('6')
    expect(normal.successorRef.current?.bep).toBe('7')
    const gap = await chain([ep('第5话', 5), ep('第7话', 7), ep('第8话', 8)])
    expect(gap.autoNext?.bep).toBeUndefined()
    expect(gap.successorRef.current?.bep).toBeUndefined()
  })

  it('stops when the current play URL no longer belongs to the source list', async () => {
    const result = await chain([ep('第5话', 5), ep('第6话', 6)], 'https://source.test/deleted')
    expect(result.autoNext).toBeNull()
    expect(result.scope.setNextCountdown).not.toHaveBeenCalled()
  })
})

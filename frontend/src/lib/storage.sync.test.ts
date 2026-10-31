import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { UserData } from '../types'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, api: { ...actual.api, loadDataWithEtag: vi.fn(), saveData: vi.fn() } }
})
import { api, ApiError, ERR_DATA_STALE } from '../api'
import * as storage from './storage'
import {
  addSearchHistory, clearSearchHistory, listSearchHistory,
  flushUserData, getSubjectProgress, getSyncState, listFavorites, loadUserData, markUserDataStale,
  setSubjectProgress, toggleFavorite,
} from './storage'

const favorite = (id: number) => ({ id, name: `Subject ${id}`, name_cn: '', cover: '', date: '', score: null, ts: id, status: 'wish' as const })
const load = vi.mocked(api.loadDataWithEtag)
const save = vi.mocked(api.saveData)

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

/** 序列化在发出 PUT 那一刻冻结，模拟服务器的整档 CAS，而非保留 doc 活引用。 */
function server(initial: UserData) {
  let remote = structuredClone(initial)
  let etag = 'e1'
  let revision = 1
  const writes: { doc: UserData; etag: string; signal?: AbortSignal }[] = []
  load.mockImplementation(async () => ({ doc: structuredClone(remote), etag }))
  const commit = (input: unknown, base: string, signal?: AbortSignal) => {
    const sent = JSON.parse(JSON.stringify(input)) as UserData
    writes.push({ doc: sent, etag: base, signal })
    if (base !== etag) throw new ApiError(409, 'stale data', ERR_DATA_STALE)
    remote = sent
    etag = `e${++revision}`
    return { etag, bytes: JSON.stringify(sent).length }
  }
  save.mockImplementation(async (input, base, signal) => commit(input, base, signal))
  return {
    commit, writes, doc: () => remote, etag: () => etag,
    externalWrite(next: UserData) { remote = structuredClone(next); etag = `e${++revision}` },
  }
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('location', { origin: 'https://animeko.test' })
  markUserDataStale()
  storage.listRetainedUserData().forEach((entry) => Reflect.get(storage, 'discardRetainedUserData')?.(entry.id))
  load.mockReset()
  save.mockReset()
})
afterEach(() => {
  markUserDataStale()
  storage.listRetainedUserData().forEach((entry) => Reflect.get(storage, 'discardRetainedUserData')?.(entry.id))
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('storage session isolation', () => {
  it('cannot acknowledge a new session deletion with an old session PUT response', async () => {
    const remote = server({ favorites: [favorite(1)] })
    const ack = deferred<Awaited<ReturnType<typeof api.saveData>>>()
    let oldSaved!: Awaited<ReturnType<typeof api.saveData>>
    save.mockImplementationOnce((input, base, signal) => {
      oldSaved = remote.commit(input, base, signal)
      return ack.promise // 已提交但 ACK 暂不交付；取消不保证服务器没有处理请求。
    })
    await loadUserData()
    toggleFavorite(favorite(1))
    const oldFlush = flushUserData()

    markUserDataStale()
    remote.externalWrite({ favorites: [favorite(1)] })
    const newBase = remote.etag()
    await loadUserData()
    toggleFavorite(favorite(1))
    const newFlush = flushUserData()
    ack.resolve(oldSaved)
    await Promise.all([oldFlush, newFlush])
    await vi.advanceTimersByTimeAsync(10_000)

    expect(remote.doc().favorites).toEqual([])
    expect(listFavorites()).toEqual([])
    expect(remote.writes).toHaveLength(2)
    expect(remote.writes[1]?.etag).toBe(newBase)
    expect(getSyncState().status).toBe('ready')
  })

  it('reports a failed flush without dropping the unsaved data', async () => {
    const remote = server({})
    await loadUserData()
    toggleFavorite(favorite(2))
    save.mockRejectedValueOnce(new ApiError(400, 'document too large', 'DATA_TOO_LARGE'))

    const result = await flushUserData()
    expect(result).toMatchObject({ ok: false, pending: true, reason: 'save', error: expect.stringContaining('document too large') })
    expect(listFavorites().map((item) => item.id)).toEqual([2])
    expect(remote.doc()).toEqual({})
    expect(getSyncState().status).toBe('error')
    expect(vi.getTimerCount()).toBe(0)
  })

  it('automatically retries a failed 409 reread without telling the user to refresh away pending data', async () => {
    const remote = server({ favorites: [favorite(1)] })
    await loadUserData()
    toggleFavorite(favorite(2))
    remote.externalWrite({ favorites: [favorite(1), favorite(3)] })
    load.mockRejectedValueOnce(new Error('temporary reread outage'))

    const result = await flushUserData()
    expect(result).toMatchObject({ ok: false, pending: true })
    expect(getSyncState()).toMatchObject({ status: 'error', kind: 'load', error: expect.stringContaining('temporary reread outage') })
    expect(getSyncState().error).not.toContain('刷新')
    expect(vi.getTimerCount()).toBe(1)
    expect(listFavorites().map((item) => item.id).sort()).toEqual([1, 2])

    await vi.advanceTimersByTimeAsync(5_000)
    expect((remote.doc().favorites as { id: number }[]).map((item) => item.id).sort()).toEqual([1, 2, 3])
    expect(getSyncState().status).toBe('ready')
    expect(vi.getTimerCount()).toBe(0)
  })

  it('keeps the furthest same-subject episode when resolving a 409', async () => {
    const remote = server({ subjectProgress: { '1': { ep: 2, ts: 10 } } })
    await loadUserData()
    setSubjectProgress(1, 3)
    remote.externalWrite({ subjectProgress: { '1': { ep: 10, ts: 100 } } })
    await expect(flushUserData()).resolves.toEqual({ ok: true, pending: false })

    expect(getSubjectProgress(1)).toEqual({ ep: 10, ts: 100 })
    expect(remote.doc().subjectProgress).toEqual({ '1': { ep: 10, ts: 100 } })
  })

  it.each([undefined, 'after-clear'])('preserves explicit history clear through failed-read recovery (new search: %s)', async (newSearch) => {
    const remote = server({ searchHistory: ['remote history'] })
    load.mockRejectedValueOnce(new Error('initial read offline'))
    await loadUserData()
    addSearchHistory('local before clear')
    clearSearchHistory()
    if (newSearch) addSearchHistory(newSearch)
    expect(remote.writes).toHaveLength(0)

    await loadUserData()
    await flushUserData()
    expect(listSearchHistory()).toEqual(newSearch ? [newSearch] : [])
    expect(remote.doc().searchHistory).toEqual(newSearch ? [newSearch] : undefined)
  })

  it('exports partial operations including deletion intent without consuming the recovery queue', async () => {
    const remote = server({ favorites: [favorite(1)], searchHistory: ['remote history'] })
    load.mockRejectedValueOnce(new Error('initial read offline'))
    await loadUserData()
    toggleFavorite(favorite(2))
    clearSearchHistory()

    const backup = JSON.parse(storage.exportPendingUserData())
    expect(backup).toMatchObject({ complete: false, format: 'animeko-unsynced-partial' })
    expect(backup.pending.keys.sort()).toEqual(['favorites', 'searchHistory'])
    expect(backup.pending.historyClear).toEqual({})
    expect(backup.pending.doc.favorites).toEqual([favorite(2)])
    expect(() => storage.exportUserData()).toThrow()
    expect(remote.writes).toHaveLength(0)

    await loadUserData()
    await flushUserData()
    expect((remote.doc().favorites as { id: number }[]).map((item) => item.id).sort()).toEqual([1, 2])
    expect(remote.doc().searchHistory).toBeUndefined()
  })

  it('quarantines unsynced data on session loss instead of destroying or exposing the old document', async () => {
    server({ favorites: [favorite(1)] })
    await loadUserData()
    toggleFavorite(favorite(2))

    markUserDataStale({ preservePending: true, owner: 'alice' })
    expect(listFavorites()).toEqual([]) // 受保护界面不能继续读旧身份文档。
    const retained = storage.listRetainedUserData()
    expect(retained).toEqual([expect.objectContaining({ owner: 'alice', origin: 'https://animeko.test', complete: true })])
    const backup = storage.exportRetainedUserData(retained[0]!.id)
    expect(JSON.parse(backup).favorites.map((item: { id: number }) => item.id).sort()).toEqual([1, 2])
  })

  it('restores only touched operations after explicit same-owner recovery, preserving new server data', async () => {
    const remote = server({ favorites: [favorite(1)] })
    await loadUserData()
    toggleFavorite(favorite(1))
    toggleFavorite(favorite(2))
    markUserDataStale({ preservePending: true, owner: 'alice' })
    const retained = storage.listRetainedUserData().slice(-1)[0]!
    remote.externalWrite({ favorites: [favorite(1), favorite(3)] })
    await loadUserData()
    expect(listFavorites().map((item) => item.id).sort()).toEqual([1, 3]) // 不自动恢复。

    Reflect.get(storage, 'restoreRetainedUserData')?.(retained.id, 'alice')
    await flushUserData()
    expect((remote.doc().favorites as { id: number }[]).map((item) => item.id).sort()).toEqual([2, 3])
    expect(listFavorites().map((item) => item.id).sort()).toEqual([2, 3])
    expect(storage.listRetainedUserData()).toEqual([])
  })
})

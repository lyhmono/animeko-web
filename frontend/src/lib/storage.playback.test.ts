import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, api: { ...actual.api, loadDataWithEtag: vi.fn(), saveData: vi.fn() } }
})

import { api } from '../api'
import { flushUserData, getSyncState, loadUserData, onSyncChange, setProgress, toggleFavorite } from './storage'

/** 播放页每 |time-last|>=4 秒写一次进度，这里照它的节奏打点 */
function tick(time: number): void {
  setProgress({ source: 's', playUrl: 'https://x/e1', title: 't', time, duration: 1200, ts: Date.now() })
}

function favorite(id: number): void {
  toggleFavorite({ id, name: 'Show', name_cn: '', cover: '', date: '', score: null, ts: Date.now() })
}

beforeEach(async () => {
  vi.useFakeTimers()
  vi.mocked(api.loadDataWithEtag).mockResolvedValue({ doc: {}, etag: 'e1' })
  vi.mocked(api.saveData).mockResolvedValue({ etag: 'e2', bytes: 10 })
  await loadUserData()
  // 上一轮测试可能留下待写数据，loadUserData 会为它补推一轮。等这轮落地再清零计数，
  // 否则每个测试的第一条断言数的都是上一轮的网络请求。
  await flushUserData()
  await vi.advanceTimersByTimeAsync(0)
  vi.mocked(api.saveData).mockClear()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('playback progress write throttling', () => {
  it('holds progress ticks inside the long window instead of PUTting the whole doc every 4s', async () => {
    tick(4)
    await vi.advanceTimersByTimeAsync(4000)
    tick(8)
    await vi.advanceTimersByTimeAsync(4000)
    tick(12)
    await vi.advanceTimersByTimeAsync(3000)
    // 15 秒窗口还没到：一次都不该发
    expect(api.saveData).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(4000)
    expect(api.saveData).toHaveBeenCalledTimes(1)
  })

  it('keeps flushing on a fixed cadence rather than being postponed forever by the next tick', async () => {
    // 纯去抖会让定时器被每 4 秒一次的 tick 无限重置，整场播放一次都不落盘。
    // 连打 60 秒（15 个 tick）必须至少推过 3 轮。
    for (let time = 4; time <= 240; time += 16) {
      tick(time)
      await vi.advanceTimersByTimeAsync(4000)
    }
    expect(vi.mocked(api.saveData).mock.calls.length).toBeGreaterThanOrEqual(3)
  })

  it('does not hold back a favorite made during playback', async () => {
    tick(4)
    await vi.advanceTimersByTimeAsync(4000)
    expect(api.saveData).not.toHaveBeenCalled()

    favorite(1)
    await vi.advanceTimersByTimeAsync(800)
    expect(api.saveData).toHaveBeenCalledTimes(1)
    // 那一轮把进度也一起带出去了，不能把它落在后面
    const sent = vi.mocked(api.saveData).mock.calls[0]?.[0] as { progress?: Record<string, unknown> }
    expect(Object.keys(sent.progress ?? {})).toHaveLength(1)
  })
})

describe('sync state notifications', () => {
  it('notifies once for repeated writes that leave the state unchanged', async () => {
    const seen: unknown[] = []
    const off = onSyncChange((state) => seen.push(state))
    tick(4)
    tick(8)
    tick(12)
    // 三次写入都是 'pending'，状态没变过：订阅方（Layout 的同步状态点）不该被叫三次，
    // 否则它连着 <Outlet/> 里的当前页面会整棵重渲染
    expect(seen).toHaveLength(1)
    expect(getSyncState().status).toBe('pending')
    off()
  })
})

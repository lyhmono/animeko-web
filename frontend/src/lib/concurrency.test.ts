import { describe, expect, it, vi } from 'vitest'
import { mapLimit } from './concurrency'

describe('mapLimit', () => {
  it('runs every item exactly once', async () => {
    const seen: number[] = []
    await mapLimit([1, 2, 3, 4, 5], 3, () => true, async (n) => {
      seen.push(n)
    })
    expect(seen.slice().sort()).toEqual([1, 2, 3, 4, 5])
  })

  it('never exceeds the concurrency limit', async () => {
    // 这条是它存在的理由：浏览器对同一来源只有 6 条连接，一次全发会把
    // /api/health 这类小请求挤到超时后面，整个应用卡在启动页。
    let live = 0
    let peak = 0
    const items = Array.from({ length: 20 }, (_, i) => i)
    await mapLimit(items, 3, () => true, async () => {
      live++
      peak = Math.max(peak, live)
      await Promise.resolve()
      await Promise.resolve()
      live--
    })
    expect(peak).toBeLessThanOrEqual(3)
    expect(peak).toBeGreaterThan(1)
  })

  it('stops pulling new items once shouldContinue turns false', async () => {
    const seen: number[] = []
    let alive = true
    await mapLimit([1, 2, 3, 4, 5, 6], 1, () => alive, async (n) => {
      seen.push(n)
      if (n === 2) alive = false
    })
    expect(seen).toEqual([1, 2])
  })

  it('does not spawn workers for an empty list', async () => {
    const run = vi.fn(async () => {})
    await mapLimit([], 3, () => true, run)
    expect(run).not.toHaveBeenCalled()
  })

  it('propagates a failure rather than swallowing it', async () => {
    // 错误处理是调用方的事：这里不 try/catch，否则失败会被静默吃掉
    await expect(
      mapLimit([1, 2], 2, () => true, async (n) => {
        if (n === 2) throw new Error('boom')
      }),
    ).rejects.toThrow('boom')
  })
})

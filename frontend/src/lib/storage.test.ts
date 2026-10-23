import { describe, expect, it, vi } from 'vitest'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      loadDataWithEtag: vi.fn(),
      saveData: vi.fn(),
    },
  }
})

import { api } from '../api'
import {
  addRecent,
  addSearchHistory,
  flushUserData,
  listFavorites,
  listRecent,
  listSearchHistory,
  loadUserData,
  toggleFavorite,
} from './storage'

describe('loadUserData after a failed read', () => {
  it('merges local pending writes instead of silently dropping them', async () => {
    const load = vi.mocked(api.loadDataWithEtag)
    const save = vi.mocked(api.saveData)
    load
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce({
        doc: {
          favorites: [{ id: 2, name: 'Remote', status: 'wish', ts: 10 }],
          recent: [{ source: 's', playUrl: 'https://x/remote', title: 'r', anime: 'a', ep: '1', ts: 10 }],
          searchHistory: ['remote'],
        },
        etag: 'e1',
      })
    save.mockResolvedValue({ etag: 'e2', bytes: 100 })

    await loadUserData()
    // 读取失败后界面仍可操作：这些改动只在本机内存里
    toggleFavorite({ id: 1, name: 'Local', name_cn: '', cover: '', date: '', score: null, ts: 20 })
    addRecent({ source: 's', playUrl: 'https://x/local', title: 'l', anime: 'a', ep: '2', ts: 20 })
    addSearchHistory('local')

    // 重新读取成功：本地三条改动要被合并进服务端文档并推一轮，而不是被 clear 掉
    await loadUserData()
    await flushUserData()

    expect(listFavorites().map((f) => f.id).sort()).toEqual([1, 2])
    expect(listRecent().map((r) => r.playUrl)).toEqual(
      expect.arrayContaining(['https://x/local', 'https://x/remote']),
    )
    expect(listSearchHistory()).toEqual(expect.arrayContaining(['local', 'remote']))

    const sent = save.mock.calls[0]?.[0] as {
      favorites?: { id: number }[]
      recent?: { playUrl: string }[]
      searchHistory?: string[]
    }
    expect(sent.favorites?.map((f) => f.id).sort()).toEqual([1, 2])
    expect(sent.searchHistory).toEqual(expect.arrayContaining(['local', 'remote']))
  })
})

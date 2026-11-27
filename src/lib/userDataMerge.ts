/**
 * 个人数据文档的纯函数工具：条目索引、冲突合并、按时间淘汰。
 *
 * 从 storage.ts 抽出来的原因：这些函数不碰任何模块级状态，
 * 可以独立单测，也让「同步状态机」和「文档形状」两件事不再挤在同一个文件里。
 * 约定：传入的文档结构都来自服务端 JSON，类型是 unknown，必须逐层判形。
 */

export function asMap(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {}
}

export function asItemList(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value)
    ? value.filter((v): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v))
    : []
}

export function idOfFavorite(item: unknown): number | null {
  const id = asMap(item).id
  return typeof id === 'number' ? id : null
}

export function favoritesById(list: unknown): Map<number, unknown> {
  const out = new Map<number, unknown>()
  for (const item of asItemList(list)) {
    const id = idOfFavorite(item)
    if (id !== null) out.set(id, item)
  }
  return out
}

export function idOfSourceFavorite(item: unknown): string | null {
  const m = asMap(item)
  return typeof m.source === 'string' && typeof m.url === 'string' ? `${m.source}|${m.url}` : null
}

export function sourceFavoritesById(list: unknown): Map<string, unknown> {
  const out = new Map<string, unknown>()
  for (const item of asItemList(list)) {
    const id = idOfSourceFavorite(item)
    if (id !== null) out.set(id, item)
  }
  return out
}

/**
 * 把「本标签页动过的条目」应用到服务端刚读回来的列表上：
 * 本地有的就覆盖/补进去，本地已经删掉的就从服务端列表里删掉。
 * 没动过的条目一个都不碰 —— 那正是另一个标签页/另一台设备的改动。
 */
export function mergeTouchedItems(
  serverList: unknown,
  mine: Map<string | number, unknown>,
  touched: (string | number)[],
  idOf: (item: unknown) => string | number | null,
): unknown[] {
  const out = Array.isArray(serverList) ? [...serverList] : []
  for (const id of touched) {
    const local = mine.get(id)
    if (local !== undefined) {
      // 本地还有这条：就地覆盖（重复的旧副本一并清掉 —— 手改过的文档里可能有两份同 id）
      let placed = false
      for (let i = out.length - 1; i >= 0; i--) {
        if (idOf(out[i]) !== id) continue
        if (placed) out.splice(i, 1)
        else {
          out[i] = local
          placed = true
        }
      }
      if (!placed) out.unshift(local)
    } else {
      // 本地已经删了：服务端列表里的同 id 条目全部删掉
      for (let i = out.length - 1; i >= 0; i--) {
        if (idOf(out[i]) === id) out.splice(i, 1)
      }
    }
  }
  return out
}

/** 进度条目上限：超过后按 ts 淘汰最久没动的条目，避免撞上服务端 512KB 上限 */
export const MAX_PROGRESS_ENTRIES = 400

/**
 * 按 ts 保留最新的一批条目，并返回被剪掉的键。
 *
 * 被剪掉的键必须一并记进 pending*：否则 409 合并时只把「本次新增的条目」
 * 贴回服务端，被本地剪掉的旧条目会从服务端那份快照里复活，文档永远缩不回去。
 */
export function pruneByTs(map: Record<string, unknown>): { next: Record<string, unknown>; removed: string[] } {
  const entries = Object.entries(map)
  if (entries.length <= MAX_PROGRESS_ENTRIES) return { next: map, removed: [] }
  const tsOf = (v: unknown): number => {
    const ts = asMap(v).ts
    return typeof ts === 'number' && Number.isFinite(ts) ? ts : 0
  }
  // 所有条目一起按 ts 竞争（缺 ts 视为最旧，最先淘汰）：上限才是真上限，
  // 淘汰的也是「最没信息的那几条」，而不是「格式旧的那一类」。
  entries.sort((a, b) => tsOf(b[1]) - tsOf(a[1]))
  const kept: Record<string, unknown> = Object.create(null)
  entries.slice(0, MAX_PROGRESS_ENTRIES).forEach(([k, v]) => {
    kept[k] = v
  })
  const keptKeys = new Set(Object.keys(kept))
  return { next: kept, removed: Object.keys(map).filter((k) => !keptKeys.has(k)) }
}
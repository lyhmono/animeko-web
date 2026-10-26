/**
 * 个人数据层：播放进度 / 追番收藏 / 搜索历史 / 最近观看。
 *
 * 数据存在服务端（{dataDir}/userdata.json），这样换设备、清缓存、开隐私模式都不会丢收藏。
 * 对外仍是一套同步函数：页面代码不该为了「读一次收藏」到处 await。
 * 实现是「启动时整份拉回内存 + 写入去抖回传」：
 *  - 读：命中内存，永远是同步的；
 *  - 写：立刻更新内存并记下改了哪些键，800ms 内多次写入合并成一次 PUT。
 *
 * 三条必须守住的底线（都是真实事故的形状）：
 *  1. 没读到服务端文档就绝不回写。否则一次读取失败 + 一次收藏点击
 *     就会把 `{favorites:[…]}` 整份覆盖上去，收藏与进度当场清空。
 *  2. 回写是条件写（If-Match 带读到的版本）。两个标签页/两台设备各自整份覆盖时，
 *     后写的会把先写的改动吃掉；版本不符就重读、把自己改过的键贴回去再写。
 *  3. 写失败必须保留待写状态并重试，界面也不能说「已保存」。
 */
import { api, ERR_DATA_STALE, errMsg, isApiError } from '../api'
import type { UserData } from '../types'

let doc: UserData = {}
let loaded = false
let baseEtag = ''
/** 同一时刻只允许一个 loadUserData 在飞；重试按钮连点/两处触发时不能并发整档读取 */
let loadInFlight: Promise<void> | null = null
/** 注销/会话失效时递增，用来丢弃换账号后才返回的旧读取结果 */
let loadEpoch = 0

// ---------- 同步状态（给界面一个「到底存上了没有」的说法）----------

export type SyncStatus = 'ready' | 'pending' | 'saving' | 'error'

export interface SyncState {
  status: SyncStatus
  /** 出错的是读取还是写入：读取失败时界面要停下写入，而不是喊「保存失败」 */
  kind: 'load' | 'save' | null
  error: string
  /** 不是故障但该让用户知道的事（例如个人数据快撞到服务端上限） */
  note?: string
}

let sync: SyncState = { status: 'ready', kind: null, error: '', note: '' }

/** 服务端个人数据文档上限 512KB，到八成提醒一次 */
const DOC_SOFT_LIMIT = 0.8 * 512 * 1024
let sizeNote = ''
const syncListeners = new Set<(s: SyncState) => void>()

function setSync(next: SyncState): void {
  // note 为空时带上体积提醒：状态点每变一次都要重算，否则一次成功的保存
  // 就把「快满了」的提醒抹掉了，而那个提醒的意义正是「现在还能写，但快写不下了」
  sync = next.note || !sizeNote ? next : { ...next, note: sizeNote }
  syncListeners.forEach((l) => l(sync))
}

export function getSyncState(): SyncState {
  return sync
}

export function onSyncChange(listener: (s: SyncState) => void): () => void {
  syncListeners.add(listener)
  return () => syncListeners.delete(listener)
}

// ---------- 整份换档通知 ----------

const docListeners = new Set<(doc: UserData) => void>()

/**
 * `doc` 被**整份换掉**时通知：开机读到、冲突合并、注销清档、读取失败清空。
 *
 * 为什么需要：页面是同步 API 的消费者，通常在挂载那一刻把 `listFavorites()` /
 * `listRecent()` 抄进自己的 state。而「启动时读取失败 → 用户点横幅上的重新读取」
 * 这条路径换掉的是整个 doc，页面没有任何理由重抄 —— 症状是收藏明明读回来了，
 * 收藏页还是一片「还没有收藏」，必须离开再回来才对，而用户得到的解释是「数据没了」。
 *
 * 局部写入**不发**这个通知：那些改动是页面自己发起的，内存与界面本来就一致，
 * 多发一次只会在渲染里绕一圈。
 */
export function onDocChange(listener: (doc: UserData) => void): () => void {
  docListeners.add(listener)
  return () => docListeners.delete(listener)
}

function setDoc(next: UserData): void {
  doc = next
  docListeners.forEach((l) => l(next))
}

// ---------- 拉取 ----------

/**
 * 开机拉一次。失败时保持 loaded=false —— 这是「不许覆盖服务器数据」的唯一开关，
 * 所以它同时也是「现在不能写」的状态，界面要显示可重试的读取错误。
 */
export async function loadUserData(): Promise<void> {
  if (loadInFlight) return loadInFlight
  const epoch = loadEpoch
  const task = loadOnce(epoch)
  loadInFlight = task
  try {
    await task
  } finally {
    if (loadInFlight === task) loadInFlight = null
  }
}

async function loadOnce(epoch: number): Promise<void> {
  try {
    const remote = await api.loadDataWithEtag()
    // 注销/换账号后返回的旧读取结果必须丢弃，不能把旧会话的 doc 贴回来
    if (epoch !== loadEpoch) return
    // 读取失败期间用户仍可点收藏/播放：这些改动记在 pending 里，但 doc 只有本地
    // 这一小份。若直接 setDoc(remote) 再清 pending，它们就被静默丢掉了。
    // 这里把 pending 改动合并到服务端文档上，随后立刻推一轮。
    const hadPending = hasPendingWrites()
    const nextDoc = hadPending ? applyPendingTo(remote.doc) : remote.doc
    setDoc(nextDoc)
    baseEtag = remote.etag
    loaded = true
    if (!hadPending) clearPending()
    // 体积提醒属于上一份文档：换账号/重新读取后不能继续挂着旧结论
    sizeNote = ''
    if (hadPending) {
      setSync({ status: 'pending', kind: null, error: '' })
      void flushUserData()
    } else {
      setSync({ status: 'ready', kind: null, error: '' })
    }
  } catch (e) {
    if (epoch !== loadEpoch) return
    loaded = false
    baseEtag = ''
    // 不能 setDoc({})：读取失败期间攒下的本地操作仍要留给下一次成功时合并。
    // 清掉本地 doc 会让 pendingFavIds/XxxKeys 指向一份空文档，下一次合并反而
    // 会删掉服务端同 id 的收藏/进度（失败两次就丢数据）。
    setSync({
      status: 'error',
      kind: 'load',
      error: `个人数据没读到，已暂停写入以免覆盖服务器上的收藏：${errMsg(e)}`,
    })
  }
}

// ---------- 回写 ----------

const DEBOUNCE_MS = 800
const RETRY_MS = 5000
/** 单个剧集一条记录，看久了会无限增长直到撞上限，所以留最新的一部分即可 */
const MAX_PROGRESS_ENTRIES = 400

const pendingKeys = new Set<string>()
/** progress 是一个大 map，整键覆盖会吃掉别的标签页刚写的其它剧集，所以细到条目级 */
const pendingProgressKeys = new Set<string>()
/**
 * 收藏同样细到条目：整键覆盖会让另一个标签页刚删掉的条目「复活」——
 * 实测两个标签页各删一条不同收藏，最终只保住一份（另一条被对方的旧快照写回去）。
 */
const pendingFavIds = new Set<number>()
const pendingSourceFavKeys = new Set<string>()
/** subjectProgress 也是 map：整键覆盖会让另一台设备刚记下的条目消失 */
const pendingSubjectKeys = new Set<string>()

function hasPendingWrites(): boolean {
  return pendingKeys.size > 0 || pendingProgressKeys.size > 0 || pendingFavIds.size > 0 ||
    pendingSourceFavKeys.size > 0 || pendingSubjectKeys.size > 0
}

function clearPending(): void {
  pendingKeys.clear()
  pendingProgressKeys.clear()
  pendingFavIds.clear()
  pendingSourceFavKeys.clear()
  pendingSubjectKeys.clear()
}

let timer: ReturnType<typeof setTimeout> | undefined
let running: Promise<void> | null = null

function scheduleFlush(key?: string, progressEntry?: string): void {
  if (key) pendingKeys.add(key)
  if (progressEntry) pendingProgressKeys.add(progressEntry)
  if (!loaded) return // 见文件头第 1 条：没有服务端文档就没有可覆盖的基准
  setSync({  status: 'pending', kind: null, error: ''  })
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => void flushUserData(), DEBOUNCE_MS)
}

/** 立即把内存里的改动推给服务端（去抖合并、版本比对、失败保留待写状态）。 */
export async function flushUserData(): Promise<void> {
  if (timer) {
    clearTimeout(timer)
    timer = undefined
  }
  if (!loaded) {
    if (sync.status !== 'error') {
      setSync({  status: 'error', kind: 'load', error: '还没读到服务器数据，写入已暂停'  })
    }
    return
  }
  // 正在写的那一轮会看到 pendingKeys，不用并发发第二个 PUT（并发 PUT 的到达顺序没有保证）
  if (running) {
    await running
    if (pendingKeys.size > 0) await flushUserData()
    return
  }
  const round = pushOnce().finally(() => {
    if (running === round) running = null
  })
  running = round
  await round
}

/**
 * 本轮真正发出去的**值**（按引用记）。
 *
 * 为什么需要：api.saveData 在调用那一刻就把 doc 序列化成了请求体，之后的改动
 * 只存在于内存里，不在这份请求体中。成功回来若无条件把这些键从 pendingKeys 删掉，
 * 「await 期间点的那两下」就被当成已同步 —— 界面显示收藏了两部，刷新后少一部。
 * 只有值仍然是发出去那个引用的键，才真的可以清掉。
 * （所有写入路径都是整体替换 `doc[key] = next`，所以引用比较足以判断「变过」。）
 */
interface Round {
  keys: string[]
  progressKeys: string[]
  favIds: number[]
  sourceFavKeys: string[]
  subjectKeys: string[]
  sentValues: Map<string, unknown>
  sentProgress: Map<string, unknown>
  sentFavs: Map<number, unknown>
  sentSourceFavs: Map<string, unknown>
  sentSubjects: Map<string, unknown>
}

function snapshotValues(round: Round): void {
  for (const key of round.keys) round.sentValues.set(key, doc[key])
  const progress = asMap(doc.progress)
  for (const entry of round.progressKeys) round.sentProgress.set(entry, progress[entry])
  const favs = favoritesById(doc.favorites)
  for (const id of round.favIds) round.sentFavs.set(id, favs.get(id))
  const srcFavs = sourceFavoritesById(doc[SOURCE_FAV_KEY])
  for (const k of round.sourceFavKeys) round.sentSourceFavs.set(k, srcFavs.get(k))
  const subjects = asMap(doc.subjectProgress)
  for (const k of round.subjectKeys) round.sentSubjects.set(k, subjects[k])
}

/** 发出去之后又被改过的顶层键 */
function changedKeys(round: Round): string[] {
  return round.keys.filter((key) => {
    if (key === 'progress') {
      const progress = asMap(doc.progress)
      // 本轮没带走、但飞行期间新加进来的条目同样要再发一轮。
      // 只看 round.progressKeys 会漏掉「新 key」，而那时 pendingKeys 会被清掉，
      // 这一条进度就永远等不到下一次 flush（暂停/关页后就丢了）。
      if ([...pendingProgressKeys].some((entry) => !round.progressKeys.includes(entry))) return true
      return round.progressKeys.some((entry) => progress[entry] !== round.sentProgress.get(entry))
    }
    if (key === 'favorites') {
      if ([...pendingFavIds].some((id) => !round.favIds.includes(id))) return true
      const now = favoritesById(doc.favorites)
      return round.favIds.some((id) => now.get(id) !== round.sentFavs.get(id))
    }
    if (key === SOURCE_FAV_KEY) {
      if ([...pendingSourceFavKeys].some((k) => !round.sourceFavKeys.includes(k))) return true
      const now = sourceFavoritesById(doc[SOURCE_FAV_KEY])
      return round.sourceFavKeys.some((k) => now.get(k) !== round.sentSourceFavs.get(k))
    }
    if (key === 'subjectProgress') {
      if ([...pendingSubjectKeys].some((k) => !round.subjectKeys.includes(k))) return true
      const now = asMap(doc.subjectProgress)
      return round.subjectKeys.some((k) => now[k] !== round.sentSubjects.get(k))
    }
    return doc[key] !== round.sentValues.get(key)
  })
}

/** 还欠着服务端一次写入：排一轮延迟推送（有在途请求时由 flushUserData 排队） */
function scheduleFollowUp(delay = DEBOUNCE_MS): void {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => void flushUserData(), delay)
}

async function pushOnce(): Promise<void> {
  let attempt = 0
  while (pendingKeys.size > 0 && attempt < 3) {
    attempt++
    const round: Round = {
      keys: [...pendingKeys],
      progressKeys: [...pendingProgressKeys],
      favIds: [...pendingFavIds],
      sourceFavKeys: [...pendingSourceFavKeys],
      subjectKeys: [...pendingSubjectKeys],
      sentValues: new Map(),
      sentProgress: new Map(),
      sentFavs: new Map(),
      sentSourceFavs: new Map(),
      sentSubjects: new Map(),
    }
    snapshotValues(round)
    setSync({  status: 'saving', kind: null, error: ''  })
    try {
      const saved = await api.saveData(doc, baseEtag)
      baseEtag = saved.etag
      // 服务端上限 512KB，撞到之后每次保存都是 400，而症状只是「收藏点不动」。
      // 提前到八成就说，用户还有机会自己清一清，而不是等写失败去猜原因
      sizeNote = saved.bytes > 0 && saved.bytes >= DOC_SOFT_LIMIT
        ? `个人数据已有 ${(saved.bytes / 1024).toFixed(0)}KB，接近服务端 512KB 上限，建议清理不再看的收藏与历史`
        : ''
      // 只清「发出去之后没再被动过」的键：await 期间新产生的改动必须留给下一轮
      const changed = changedKeys(round)
      round.keys.forEach((k) => { if (!changed.includes(k)) pendingKeys.delete(k) })
      round.progressKeys.forEach((k) => {
        if (asMap(doc.progress)[k] === round.sentProgress.get(k)) pendingProgressKeys.delete(k)
      })
      const favsNow = favoritesById(doc.favorites)
      round.favIds.forEach((id) => {
        if (favsNow.get(id) === round.sentFavs.get(id)) pendingFavIds.delete(id)
      })
      const srcFavsNow = sourceFavoritesById(doc[SOURCE_FAV_KEY])
      round.sourceFavKeys.forEach((k) => {
        if (srcFavsNow.get(k) === round.sentSourceFavs.get(k)) pendingSourceFavKeys.delete(k)
      })
      const subjectsNow = asMap(doc.subjectProgress)
      round.subjectKeys.forEach((k) => {
        if (subjectsNow[k] === round.sentSubjects.get(k)) pendingSubjectKeys.delete(k)
      })
      // 确实有改动没被这份请求体带走 → 自己排下一轮，而不是等用户再点一次。
      // 状态必须跟着排队这件事一起说「待保存」，不能先报 ready。
      // 这条的**实际窗口有多宽**记在这里，免得把它夸大成用户级故障：
      // 本轮实测（挂 12 秒的 PUT + 在飞期间再点一次收藏）——如果这段时间里已经有一次
      // flushUserData 在 `await running` 上排着队，第一轮落地后约 20ms 第二轮就发出去了，
      // 于是「已保存」只谎称了 20 毫秒，界面上看不出来；只有在没有排队轮次、
      // 纯靠这里补定时器的那条路径上，这个窗口才是 800 毫秒。
      // 结论：这是**状态标注的正确性**修复（顺带让文件头第 3 条说得通），
      // 不是一个可以稳定复现给用户看的缺陷 —— 别在注释里把它写成后者。
      if (changed.length > 0) {
        scheduleFollowUp()
        setSync({ status: 'pending', kind: null, error: '' })
      } else {
        setSync({ status: 'ready', kind: null, error: '' })
      }
    } catch (e) {
      if (isApiError(e, 409, ERR_DATA_STALE)) {
        const merged = await mergeWithServer(round)
        if (merged) continue // 换好基准，重试这一次写
        setSync({  status: 'error', kind: 'save', error: '本机改动与服务端冲突且无法合并，请刷新页面重试'  })
        return
      }
      // 超上限、格式错这类不会自己变好，别把浏览器变成打服务端的循环
      const fatal = isApiError(e, 400) || isApiError(e, 413)
      setSync({ status: 'error', kind: 'save', error: `保存失败：${errMsg(e)}` })
      if (!fatal) scheduleFollowUp(RETRY_MS)
      return
    }
  }
  // 上面那个 while 靠 attempt<3 退出时，pendingKeys 可能还是满的：那意味着
  // 「合并完再写又被版本冲突弹回来」连续发生了三次（另一个标签页/设备在高频写）。
  // 原实现到这里就直接 return，于是三份状态一起坏了：待写集合非空、没有定时器、
  // 界面停在最后一轮那句「保存中」—— 状态点从此永远挂着「保存中…」，
  // 再没有任何东西会把这份改动推上去，用户以为存好了，刷新一下就没了。
  // 这里既不骗他（如实报错）也不放弃（定时重试，直到推上去或他刷新为止）。
  if (pendingKeys.size > 0) {
    setSync({
      status: 'error',
      kind: 'save',
      error: '本机改动与服务端版本连续冲突三次，已暂停；将在 5 秒后自动重试',
    })
    scheduleFollowUp(RETRY_MS)
  }
}

/**
 * 版本冲突：重读服务端文档，只把「本标签页改过的东西」贴上去。
 *
 * 粒度分两档：
 *  - progress / subjectProgress / favorites / favoritesSource **按条目**合并 ——
 *    这几个都是「多条目容器」，两个标签页各动一条时，整键覆盖会让另一方的那条复活/消失
 *    （实测：两个标签页各删一条收藏，最终只保住一份；各播一集，另一集进度丢失）。
 *  - 其余键（recent、searchHistory 等）仍按整键：它们只是最近记录，上限小、可自愈，
 *    两个标签页同时各加一条时可能只留下一条，这是已知取舍。
 */
async function mergeWithServer(round: Round): Promise<boolean> {
  let fresh
  try {
    fresh = await api.loadDataWithEtag()
  } catch (e) {
    setSync({ status: 'error', kind: 'load', error: `冲突后重读失败：${errMsg(e)}` })
    return false
  }
  // 合并要按**当前**的待写集合，而不是本轮开始时的快照：
  // PUT 在飞的这段时间里用户还能继续点（收藏、进度），那些改动记在 pending* 里，
  // 但不在 round 里。用快照合并会把它们从内存文档里抹掉（setDoc(merged)），
  // 下一轮再把这个「被抹掉」的文档发上去 —— 改动就这么静默丢了
  // （实测：删 A → PUT 在飞时删 B → 第一轮 409 → 合并只应用了 A → B 复活）。
  setDoc(applyPendingTo(fresh.doc, round))
  baseEtag = fresh.etag
  return true
}

/**
 * 把本机待写改动贴到一份新鲜的服务端文档上。
 *
 * round 为 null 表示「读取失败后重新读取成功」：此时本地 doc 只是读取失败期间
 * 攒下的一小份，recent / searchHistory 必须和服务器并集，不能整键覆盖掉
 * 另一台设备刚写入的记录。正常 409 合并仍按原语义（本地整键为准）。
 */
function applyPendingTo(fresh: UserData, round?: Round): UserData {
  const keys = [...new Set([...(round?.keys ?? []), ...pendingKeys])]
  const progressKeys = [...new Set([...(round?.progressKeys ?? []), ...pendingProgressKeys])]
  const favIds = [...new Set([...(round?.favIds ?? []), ...pendingFavIds])]
  const sourceFavKeys = [...new Set([...(round?.sourceFavKeys ?? []), ...pendingSourceFavKeys])]
  const subjectKeys = [...new Set([...(round?.subjectKeys ?? []), ...pendingSubjectKeys])]
  const merged: UserData = { ...fresh }
  for (const key of keys) {
    if (key === 'progress') {
      const theirs = asMap(merged.progress)
      const mine = asMap(doc.progress)
      const out: Record<string, unknown> = { ...theirs }
      for (const entry of progressKeys) {
        if (entry in mine) out[entry] = mine[entry]
        else delete out[entry]
      }
      merged.progress = out
    } else if (key === 'favorites') {
      // 按条目合并：只应用本标签页动过的收藏，别人的新增/删除原样保留
      merged.favorites = mergeTouchedItems(
        merged.favorites, favoritesById(doc.favorites), favIds, idOfFavorite,
      )
    } else if (key === SOURCE_FAV_KEY) {
      merged[SOURCE_FAV_KEY] = mergeTouchedItems(
        merged[SOURCE_FAV_KEY], sourceFavoritesById(doc[SOURCE_FAV_KEY]), sourceFavKeys, idOfSourceFavorite,
      )
    } else if (key === 'subjectProgress') {
      // 与 progress 同构：只把本标签页动过的条目贴上去
      const theirs = asMap(merged.subjectProgress)
      const mine = asMap(doc.subjectProgress)
      const out: Record<string, unknown> = { ...theirs }
      for (const k of subjectKeys) {
        if (k in mine) out[k] = mine[k]
        else delete out[k]
      }
      merged.subjectProgress = out
    } else if (key === 'recent' && round == null) {
      merged.recent = mergeRecentLists(fresh.recent, doc.recent)
    } else if (key === 'searchHistory' && round == null) {
      merged.searchHistory = mergeSearchHistory(fresh.searchHistory, doc.searchHistory)
    } else if (Object.prototype.hasOwnProperty.call(doc, key)) {
      merged[key] = doc[key]
    } else {
      delete merged[key]
    }
  }
  return merged
}

/** 读取失败后的重新加载：recent 按 (source, playUrl) 并集，取 ts 更新的一份。 */
function mergeRecentLists(server: unknown, local: unknown): unknown[] {
  const byKey = new Map<string, Record<string, unknown>>()
  for (const item of [...asItemList(local), ...asItemList(server)]) {
    const playUrl = asMap(item).playUrl
    if (typeof playUrl !== 'string' || !playUrl.startsWith('http')) continue
    const key = `${String(asMap(item).source ?? '')}|${playUrl}`
    const existing = byKey.get(key)
    if (!existing || numOr(asMap(item).ts, 0) > numOr(existing.ts, 0)) byKey.set(key, item)
  }
  return [...byKey.values()]
    .sort((a, b) => numOr(b.ts, 0) - numOr(a.ts, 0))
    .slice(0, RECENT_LIMIT)
}

/** 读取失败后的重新加载：搜索历史并集，本地新增在前，仍受 10 条上限。 */
function mergeSearchHistory(server: unknown, local: unknown): string[] {
  const out: string[] = []
  const values = [...(Array.isArray(local) ? local : []), ...(Array.isArray(server) ? server : [])]
  for (const value of values) {
    if (typeof value !== 'string') continue
    const keyword = value.trim()
    if (!keyword || out.includes(keyword)) continue
    out.push(keyword)
    if (out.length >= HISTORY_LIMIT) break
  }
  return out
}

// 关掉标签页 / 切后台时尽力把待写内容送出去（fetch 在页面卸载后可能被浏览器掐掉，
// 所以这只是「尽力」，真正的兜底是每次改动都会即时去抖回写）。
if (typeof document !== 'undefined') {
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') void flushUserData()
  })
  window.addEventListener('pagehide', () => void flushUserData())
}

// ---------- 底层读写（键即文档字段）----------

export function readJson<T>(key: string, fallback: T): T {
  const value = doc[key]
  return value === undefined ? fallback : (value as T)
}

export function writeJson(key: string, value: unknown): void {
  doc[key] = value
  scheduleFlush(key)
}

/** 写收藏，并记下这次动过哪几个条目（冲突合并只应用这几条） */
function writeFavorites(next: FavoriteSubject[], touched: readonly number[]): void {
  doc.favorites = next
  touched.forEach((id) => pendingFavIds.add(id))
  scheduleFlush('favorites')
}

function writeSourceFavorites(next: SourceFavorite[], touched: readonly string[]): void {
  doc[SOURCE_FAV_KEY] = next
  touched.forEach((k) => pendingSourceFavKeys.add(k))
  scheduleFlush(SOURCE_FAV_KEY)
}

function writeSubjectProgress(next: Record<string, unknown>, touched: readonly string[]): void {
  doc.subjectProgress = next
  touched.forEach((k) => pendingSubjectKeys.add(k))
  scheduleFlush('subjectProgress')
}

function removeKey(key: string): void {
  if (!(key in doc)) return
  delete doc[key]
  scheduleFlush(key)
}

/** 服务端文档里没有 progress 时给一个空容器（返回的是活引用，调用方只读） */
function progressMap(): Record<string, unknown> {
  const raw = doc.progress
  return raw && typeof raw === 'object' && !Array.isArray(raw) ? (raw as Record<string, unknown>) : {}
}

function asMap(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {}
}

// ---------- 收藏的条目级索引（冲突合并用）----------

function asItemList(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value)
    ? value.filter((v): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v))
    : []
}

function idOfFavorite(item: unknown): number | null {
  const id = asMap(item).id
  return typeof id === 'number' ? id : null
}

function favoritesById(list: unknown): Map<number, unknown> {
  const out = new Map<number, unknown>()
  for (const item of asItemList(list)) {
    const id = idOfFavorite(item)
    if (id !== null) out.set(id, item)
  }
  return out
}

function idOfSourceFavorite(item: unknown): string | null {
  const m = asMap(item)
  return typeof m.source === 'string' && typeof m.url === 'string' ? `${m.source}|${m.url}` : null
}

function sourceFavoritesById(list: unknown): Map<string, unknown> {
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
function mergeTouchedItems(
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

/**
 * 按 ts 保留最新的一批条目，并返回被剪掉的键。
 *
 * 被剪掉的键必须一并记进 pending*：否则 409 合并时只把「本次新增的条目」
 * 贴回服务端，被本地剪掉的旧条目会从服务端那份快照里复活，文档永远缩不回去。
 */
function pruneByTs(map: Record<string, unknown>): { next: Record<string, unknown>; removed: string[] } {
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

function writeProgressMap(map: Record<string, unknown>, entryKey: string): void {
  // 剪掉最久没动的条目：一条都不删的话，看够了集数就会撞上服务端 512KB 上限，
  // 之后每一次收藏/进度写入都会 400，等于整个数据层从此写不进去
  const { next, removed } = pruneByTs(map)
  removed.forEach((key) => pendingProgressKeys.add(key))
  doc.progress = next
  scheduleFlush('progress', entryKey)
}

// ---------- 主题（设备偏好，不进服务端文档）----------

export type Theme = 'dark' | 'light'

const THEME_COLORS: Record<Theme, string> = { dark: '#0b0c0f', light: '#f6f3ec' }

/**
 * 主题只存本机：它是「这台设备看着舒服与否」的偏好，不是收藏。
 * 放在 localStorage 还有一次必须在渲染前读到（否则首屏会闪一下错误配色）。
 */
const THEME_KEY = 'animeko-web:theme'

function localRead(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function localWrite(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    // 隐私模式/超额时静默失败：主题丢了不影响功能
  }
}

/** 同步 address bar / PWA 主题色，避免亮色主题下浏览器 UI 仍是暗色 */
export function syncThemeColor(theme: Theme): void {
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLORS[theme])
}

export function getTheme(): Theme {
  return localRead(THEME_KEY) === 'light' ? 'light' : 'dark'
}

export function setTheme(theme: Theme): void {
  localWrite(THEME_KEY, theme)
}

// ---------- 播放器偏好（设备本地，不同步到服务器）----------

/**
 * 音量 / 静音 / 倍速存在浏览器本地，和主题同一个理由：
 * 它们是**设备属性**而不是账号属性 —— 手机外放要 60%，台式机接音箱要 100%，
 * 同步到服务器只会让两台设备互相打架。所以不走 userdata 那条同步链路。
 */
const PLAYER_PREFS_KEY = 'animeko-web:player'

export interface PlayerPrefs {
  volume: number
  muted: boolean
  rate: number
}

const DEFAULT_PLAYER_PREFS: PlayerPrefs = { volume: 1, muted: false, rate: 1 }

export function getPlayerPrefs(): PlayerPrefs {
  const raw = localRead(PLAYER_PREFS_KEY)
  if (!raw) return { ...DEFAULT_PLAYER_PREFS }
  try {
    const parsed = JSON.parse(raw) as Partial<PlayerPrefs>
    const volume = typeof parsed.volume === 'number' && parsed.volume >= 0 && parsed.volume <= 1
      ? parsed.volume
      : DEFAULT_PLAYER_PREFS.volume
    const rate = typeof parsed.rate === 'number' && parsed.rate >= 0.25 && parsed.rate <= 4
      ? parsed.rate
      : DEFAULT_PLAYER_PREFS.rate
    return { volume, muted: parsed.muted === true, rate }
  } catch {
    // 坏值不该让播放器起不来：退回默认
    return { ...DEFAULT_PLAYER_PREFS }
  }
}

export function savePlayerPrefs(prefs: PlayerPrefs): void {
  localWrite(PLAYER_PREFS_KEY, JSON.stringify(prefs))
}

// ---------- 条目级观看进度（「看到第几话」）----------

/**
 * 按 Bangumi 条目记「看到第几话」。
 *
 * 为什么不直接从 progress 推：progress 的键是 (源, 播放地址)，而条目页的剧集
 * 来自 Bangumi，两边没有可对上的标识 —— 同一个条目在不同源里的播放地址完全不同。
 * 所以由播放页额外记一条「这个条目我看到第 N 话」，条目页才能显示出来。
 * 集号来自播放参数里的 ep（数据源的 sortNum），是提示而非权威值：
 * 不同源的集号可能对不齐，但那也好过完全不显示。
 */
export interface SubjectProgressEntry {
  ep: number
  ts: number
}

export function getSubjectProgress(subjectId: number): SubjectProgressEntry | null {
  const map = asMap(readJson<Record<string, unknown>>('subjectProgress', {}))
  const entry = map[String(subjectId)] as SubjectProgressEntry | undefined
  if (entry == null || typeof entry.ep !== 'number' || !(entry.ep > 0)) return null
  return { ep: entry.ep, ts: typeof entry.ts === 'number' ? entry.ts : 0 }
}

export function setSubjectProgress(subjectId: number, ep: number): void {
  if (!(subjectId > 0) || !(ep > 0)) return
  const map = asMap(readJson<Record<string, unknown>>('subjectProgress', {}))
  const key = String(subjectId)
  const prev = map[key] as SubjectProgressEntry | undefined
  // 只前进不后退：从第 5 话回看第 2 话不该把「看到第 5 话」改小
  if (prev && typeof prev.ep === 'number' && prev.ep >= ep) return
  const next = { ...map, [key]: { ep, ts: Date.now() } }
  // 和 progress 一样要剪枝：这个键按条目增长，不剪的话迟早撞上服务端 512KB 上限，
  // 之后每一次写入都会 400 —— 整个数据层从此写不进去，而不只是进度丢失。
  const { next: pruned, removed } = pruneByTs(next)
  removed.forEach((k) => pendingSubjectKeys.add(k))
  writeSubjectProgress(pruned, [key])
}

// ---------- 播放方式（设备偏好，不进服务端文档）----------

/**
 * 直连播放：浏览器直接连数据源站点取流，不经过这台服务器。
 *
 * 默认开：视频流量是这台机器最大的一笔开销，而且直连没有中转延迟。
 * 代价是源站能看到观看者的 IP；遇到防盗链（要特定 Referer/Cookie）或 CDN 不给
 * CORS 时直连会失败，播放器会自动回退到 /api/proxy 再试一次。
 * 存在本机：同一份部署在不同网络环境下（手机蜂窝 / 家里宽带）可以各选各的。
 */
const DIRECT_PLAY_KEY = 'animeko-web:direct-playback'

export function getDirectPlayback(): boolean {
  // 只有明确写过 off 才关；没设置过按「开」处理
  return localRead(DIRECT_PLAY_KEY) !== 'off'
}

export function setDirectPlayback(on: boolean): void {
  localWrite(DIRECT_PLAY_KEY, on ? 'on' : 'off')
}

// ---------- 播放进度 ----------
export interface ProgressEntry {
  source: string
  playUrl: string
  title: string
  /** 已播放秒数 */
  time: number
  /** 总时长秒数（0 表示未知） */
  duration: number
  ts: number
}

function progressKeyOf(source: string, playUrl: string): string {
  return `${source}:${playUrl}`
}

export function getProgress(source: string, playUrl: string): ProgressEntry | null {
  const entry = asMap(progressMap())[progressKeyOf(source, playUrl)] as ProgressEntry | undefined
  if (entry == null || typeof entry.time !== 'number' || !(entry.time > 5)) return null
  // 返回副本：调用方拿到文档里的活对象一改就绕过 flush，改动永远到不了服务器
  return { ...entry }
}

export function setProgress(entry: ProgressEntry): void {
  const key = progressKeyOf(entry.source, entry.playUrl)
  const map = { ...progressMap(), [key]: entry }
  writeProgressMap(map, key)
}

export function clearProgress(source: string, playUrl: string): void {
  const key = progressKeyOf(source, playUrl)
  const map = progressMap()
  if (!(key in map)) return
  const next = { ...map }
  delete next[key]
  writeProgressMap(next, key)
}

// ---------- 追番收藏 ----------

/**
 * 追番状态。用字符串而不是数字：文档是 JSON、还可能被手改，
 * 数字枚举在换版本时会静默错位（旧版 1=想看、新版 1=在看），字符串不会。
 */
export type FavStatus = 'wish' | 'watching' | 'done' | 'onhold'

export const FAV_STATUS_LABEL: Record<FavStatus, string> = {
  wish: '想看',
  watching: '在看',
  done: '看过',
  onhold: '搁置',
}

export interface FavoriteSubject {
  id: number
  name: string
  name_cn: string
  cover: string
  date: string
  score: number | null
  ts: number
  /** 追番状态。旧数据没有这个字段，归一化时补成「想看」 */
  status: FavStatus
}

/**
 * 单条收藏的归一化。抽成独立函数是因为它有两个调用方：
 * 读文档（listFavorites）与导入备份（importUserData）——
 * 备份文件同样可能来自旧版本或被手工改过，两边必须用同一套规则。
 */
function toFavorite(raw: unknown): FavoriteSubject | null {
  const f = raw as Partial<FavoriteSubject> | null
  if (!f || typeof f.id !== 'number') return null
  return {
    id: f.id,
    name: String(f.name ?? ''),
    name_cn: String(f.name_cn ?? ''),
    cover: String(f.cover ?? ''),
    date: String(f.date ?? ''),
    score: typeof f.score === 'number' ? f.score : null,
    ts: typeof f.ts === 'number' ? f.ts : 0,
    // 未知值一律落到「想看」：宁可归错一档，也不让一条收藏因为脏字段从列表里消失
    status: isFavStatus(f.status) ? f.status : 'wish',
  }
}

export function listFavorites(): FavoriteSubject[] {
  const list = readJson<FavoriteSubject[]>('favorites', [])
  if (!Array.isArray(list)) return []
  // 逐字段归一化：文档可能来自旧版本、别的客户端，或被手工改过。
  // 只校验 id 的话，score 是字符串就会让 FavoritesPage 抛 toFixed is not a function，
  // 而唯一的错误边界在 <Routes> 之上，于是整站变成错误页。
  return list
    .map(toFavorite)
    .filter((f): f is FavoriteSubject => f !== null)
    .sort((a, b) => b.ts - a.ts)
}

function isFavStatus(value: unknown): value is FavStatus {
  return value === 'wish' || value === 'watching' || value === 'done' || value === 'onhold'
}

export function isFavorite(id: number): boolean {
  return listFavorites().some((f) => f.id === id)
}

/** 收藏 ID 集合：列表页批量判断用，避免每张卡片都重新解析一遍文档（O(n²)） */
export function favoriteIdSet(): Set<number> {
  return new Set(listFavorites().map((f) => f.id))
}

/**
 * 切换收藏，返回操作后是否处于「已收藏」。
 *
 * 入参允许不带 status（调用方通常只知道条目信息）：新建收藏时缺省记「想看」，
 * 已收藏则原样保留原来的状态 —— 再点一次是取消，不该顺手把「看过」重置成「想看」。
 */
export function toggleFavorite(
  subject: Omit<FavoriteSubject, 'status'> & { status?: FavStatus },
): boolean {
  const list = listFavorites()
  const exists = list.some((f) => f.id === subject.id)
  const next = exists
    ? list.filter((f) => f.id !== subject.id)
    : [{ ...subject, status: subject.status ?? 'wish' }, ...list]
  writeFavorites(next, [subject.id])
  return !exists
}

/** 改追番状态。条目不在收藏里时什么都不做（返回 false）。 */
export function setFavoriteStatus(id: number, status: FavStatus): boolean {
  const list = listFavorites()
  const target = list.find((f) => f.id === id)
  if (!target) return false
  writeFavorites(
    list.map((f) => (f.id === id ? { ...f, status } : f)),
    [id],
  )
  return true
}

// ---------- 数据源收藏 ----------

/**
 * 从「数据源搜索结果」进来的条目没有 Bangumi id，只能用 (源, 地址) 当键。
 * 单独存一个键而不是混进 favorites：两种形状的归一化规则不同，
 * 混在一起时 listFavorites 的 `typeof id === 'number'` 过滤会静默吃掉整批数据源收藏。
 */
export interface SourceFavorite {
  source: string
  url: string
  name: string
  cover: string
  ts: number
}

const SOURCE_FAV_KEY = 'favoritesSource'

function sourceFavKeyOf(source: string, url: string): string {
  return `${source}|${url}`
}

export function listSourceFavorites(): SourceFavorite[] {
  const list = readJson<SourceFavorite[]>(SOURCE_FAV_KEY, [])
  if (!Array.isArray(list)) return []
  return list
    .filter((f) => f && typeof f.url === 'string' && typeof f.source === 'string')
    .map((f) => ({
      source: f.source,
      url: f.url,
      name: String(f.name ?? ''),
      cover: String(f.cover ?? ''),
      ts: typeof f.ts === 'number' ? f.ts : 0,
    }))
    .sort((a, b) => b.ts - a.ts)
}

export function isSourceFavorite(source: string, url: string): boolean {
  return listSourceFavorites().some((f) => sourceFavKeyOf(f.source, f.url) === sourceFavKeyOf(source, url))
}

/** 切换数据源收藏，返回操作后是否处于「已收藏」 */
export function toggleSourceFavorite(item: SourceFavorite): boolean {
  const key = sourceFavKeyOf(item.source, item.url)
  const list = listSourceFavorites()
  const exists = list.some((f) => sourceFavKeyOf(f.source, f.url) === key)
  const next = exists ? list.filter((f) => sourceFavKeyOf(f.source, f.url) !== key) : [item, ...list]
  writeSourceFavorites(next, [key])
  return !exists
}

// ---------- 搜索历史 ----------

const HISTORY_LIMIT = 10

export function listSearchHistory(limit = HISTORY_LIMIT): string[] {
  const list = readJson<string[]>('searchHistory', [])
  if (!Array.isArray(list)) return []
  return list.filter((q) => typeof q === 'string' && q.trim()).slice(0, limit)
}

/** 写入搜索历史（去重、最新在前、上限 10 条） */
export function addSearchHistory(q: string): string[] {
  const keyword = q.trim()
  if (!keyword) return listSearchHistory()
  const next = [keyword, ...listSearchHistory().filter((h) => h !== keyword)].slice(0, HISTORY_LIMIT)
  writeJson('searchHistory', next)
  return next
}

export function clearSearchHistory(): void {
  removeKey('searchHistory')
}

// ---------- 最近观看 ----------

export interface RecentItem {
  source: string
  playUrl: string
  title: string
  anime: string
  ep: string
  ts: number
}

const RECENT_LIMIT = 8

export function listRecent(limit = RECENT_LIMIT): RecentItem[] {
  const list = readJson<RecentItem[]>('recent', [])
  if (!Array.isArray(list)) return []
  return list
    .filter((r) => r && typeof r.playUrl === 'string' && r.playUrl.startsWith('http'))
    .map((r) => ({
      source: String(r.source ?? ''),
      playUrl: r.playUrl,
      title: String(r.title ?? ''),
      anime: String(r.anime ?? ''),
      ep: String(r.ep ?? ''),
      ts: typeof r.ts === 'number' ? r.ts : 0,
    }))
    .slice(0, limit)
}

export function addRecent(item: RecentItem): RecentItem[] {
  const list = listRecent(RECENT_LIMIT).filter(
    (r) => !(r.source === item.source && r.playUrl === item.playUrl),
  )
  const next = [item, ...list].slice(0, RECENT_LIMIT)
  writeJson('recent', next)
  return next
}

/**
 * 丢掉本机文档副本（注销、或服务端说会话已失效）。
 * 之后任何写入都只会改内存，直到 loadUserData 重新拉到属于新会话的文档为止 ——
 * 否则「用旧会话的残档去覆盖新会话的数据」这种事真的会发生。
 */
export function markUserDataStale(): void {
  // 让在飞的旧 load 结果失效；同时允许下一次 loadUserData 立即重新发起
  loadEpoch++
  loadInFlight = null
  if (timer) {
    clearTimeout(timer)
    timer = undefined
  }
  pendingKeys.clear()
  pendingProgressKeys.clear()
  pendingFavIds.clear()
  pendingSourceFavKeys.clear()
  pendingSubjectKeys.clear()
  loaded = false
  setDoc({})
  baseEtag = ''
  // 体积提醒属于上一份文档：不清掉的话，换账号后页脚还会说「接近上限」
  sizeNote = ''
  setSync({  status: 'ready', kind: null, error: ''  })
}

// ---------- 备份导出 / 导入 ----------

export interface ImportSummary {
  favorites: number
  sourceFavorites: number
  subjectProgress: number
  progress: number
  searchHistory: number
  recent: number
}

/** 个人数据是否已经从服务器读到。导出/导入都要看它，理由见下面两个函数的守卫。 */
export function isUserDataLoaded(): boolean {
  return loaded
}

/** 导出整份个人数据。格式化输出，便于人工查看与 diff。 */
export function exportUserData(): string {
  // 读取失败时应用仍可用（浏览与搜索不需要同步），但 doc 是空的。
  // 此时导出的是一份 {}，用户却会以为备份成功 —— 那比不给备份更糟。
  if (!loaded) throw new Error('个人数据还没从服务器读到，现在导出只会得到一份空备份')
  return JSON.stringify(doc, null, 2)
}

function numOr(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

/**
 * 导入备份。语义是**合并**而不是覆盖，理由：覆盖一旦选错文件就是不可逆的数据丢失，
 * 而合并最坏情况只是「多了几条本来就在的」。
 *
 * 各类数据的合并规则：
 *  - 收藏：按 Bangumi id 去重，同 id 保留**本地**版本（本地可能刚改过状态）
 *  - 数据源收藏：按 (源, 地址) 去重取并集
 *  - 条目进度：取更大的集号（进度只前进不后退）
 *  - 每集进度：按 (源, 播放地址) 取 ts 更新的一条
 *  - 搜索历史 / 最近观看：并集去重，仍受各自上限约束
 *
 * 返回各类实际新增的条数，UI 用来说明「带进来了什么」。
 * 文件不合法时抛错，由调用方展示。
 */
export function importUserData(text: string): ImportSummary {
  // 未加载时写进去的改动既不会被推送（scheduleFlush 在 !loaded 时直接返回），
  // 还会被随后一次成功的 loadUserData 整份覆盖 —— 而界面已经显示「已合并」。
  if (!loaded) throw new Error('个人数据还没从服务器读到，现在导入会被随后的加载覆盖')
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new Error('不是合法的 JSON 文件')
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('备份文件格式不对：顶层应该是一个对象')
  }
  const incoming = parsed as Record<string, unknown>
  const summary: ImportSummary = {
    favorites: 0,
    sourceFavorites: 0,
    subjectProgress: 0,
    progress: 0,
    searchHistory: 0,
    recent: 0,
  }

  if (Array.isArray(incoming.favorites)) {
    const local = listFavorites()
    const ids = new Set(local.map((f) => f.id))
    const added: FavoriteSubject[] = []
    for (const raw of incoming.favorites) {
      const fav = toFavorite(raw)
      if (!fav || ids.has(fav.id)) continue
      ids.add(fav.id)
      added.push(fav)
    }
    if (added.length > 0) {
      writeFavorites([...added, ...local], added.map((f) => f.id))
      summary.favorites = added.length
    }
  }

  // 数据源收藏（favoritesSource）。导出是整份 doc，里面就含这一键，
  // 而导入原先**完全不处理它** —— 于是「备份 → 换设备/换账号恢复」这条路上
  // 数据源收藏整段静默消失，摘要里一个数字都没有，用户根本不知道少了一批。
  // 备份功能存在的意义就是别丢数据，这一格不能留白。
  if (Array.isArray(incoming[SOURCE_FAV_KEY])) {
    const local = listSourceFavorites()
    const seen = new Set(local.map((f) => sourceFavKeyOf(f.source, f.url)))
    const added: SourceFavorite[] = []
    for (const raw of incoming[SOURCE_FAV_KEY] as unknown[]) {
      const item = asMap(raw)
      if (typeof item.url !== 'string' || typeof item.source !== 'string') continue
      const fav: SourceFavorite = {
        source: item.source,
        url: item.url,
        name: String(item.name ?? ''),
        cover: String(item.cover ?? ''),
        ts: numOr(item.ts, 0),
      }
      const key = sourceFavKeyOf(fav.source, fav.url)
      if (seen.has(key)) continue
      seen.add(key)
      added.push(fav)
    }
    if (added.length > 0) {
      writeSourceFavorites([...added, ...local], added.map((f) => sourceFavKeyOf(f.source, f.url)))
      summary.sourceFavorites = added.length
    }
  }

  if (incoming.subjectProgress && typeof incoming.subjectProgress === 'object' && !Array.isArray(incoming.subjectProgress)) {
    const local = asMap(readJson<Record<string, unknown>>('subjectProgress', {}))
    const merged: Record<string, unknown> = { ...local }
    const applied: string[] = []
    for (const [id, raw] of Object.entries(incoming.subjectProgress as Record<string, unknown>)) {
      const ep = numOr(asMap(raw).ep, 0)
      if (!(ep > 0)) continue
      if (ep <= numOr(asMap(merged[id]).ep, 0)) continue
      merged[id] = { ep, ts: numOr(asMap(raw).ts, Date.now()) }
      applied.push(id)
      summary.subjectProgress += 1
    }
    // 与 progress 同理：导入必须把**真正改过的条目**记进待写集合，
    // 否则 409 合并时会按服务端版本整键覆盖，这次导入静默丢失。
    // 同时走一次上限剪枝：备份可能有几千条，直接合并会撞上 512KB 上限。
    if (applied.length > 0) {
      const { next: pruned, removed } = pruneByTs(merged)
      removed.forEach((k) => pendingSubjectKeys.add(k))
      writeSubjectProgress(pruned, applied)
    }
  }

  if (incoming.progress && typeof incoming.progress === 'object' && !Array.isArray(incoming.progress)) {
    const merged: Record<string, unknown> = { ...progressMap() }
    const applied: string[] = []
    for (const [key, raw] of Object.entries(incoming.progress as Record<string, unknown>)) {
      const entry = asMap(raw)
      const time = numOr(entry.time, 0)
      if (!(time > 0)) continue
      // 同一个播放地址上，取进度更新的一条
      if (numOr(entry.ts, 0) <= numOr(asMap(merged[key]).ts, -1)) continue
      merged[key] = entry
      applied.push(key)
      summary.progress += 1
    }
    if (applied.length > 0) {
      // 备份里可能有几千条进度；不剪枝的话一次导入就能超过服务端 512KB，
      // 之后每一次保存都是 400，用户只能手动清理文档。
      const { next: pruned, removed } = pruneByTs(merged)
      removed.forEach((key) => pendingProgressKeys.add(key))
      doc.progress = pruned
      // 只标记这次真正写进去的键。把**全部本地键**都记成「我改的」会在 409 合并时
      // 把另一台设备刚删掉的旧进度又贴回服务端（复活）。
      for (const key of applied) pendingProgressKeys.add(key)
      scheduleFlush('progress')
    }
  }

  if (Array.isArray(incoming.searchHistory)) {
    const local = listSearchHistory()
    // 导入项放前面：本地历史通常已经占满 10 条，若追加到末尾再 slice，
    // 导入项会被全部裁掉，而摘要仍然报告「已合并 N 条」。
    const merged: string[] = []
    for (const raw of incoming.searchHistory) {
      if (typeof raw !== 'string') continue
      const keyword = raw.trim()
      if (!keyword || merged.includes(keyword)) continue
      merged.push(keyword)
    }
    for (const keyword of local) {
      if (!merged.includes(keyword)) merged.push(keyword)
    }
    const next = merged.slice(0, HISTORY_LIMIT)
    const localSet = new Set(local)
    const actuallyAdded = next.filter((keyword) => !localSet.has(keyword)).length
    if (actuallyAdded > 0) {
      summary.searchHistory += actuallyAdded
      writeJson('searchHistory', next)
    }
  }

  if (Array.isArray(incoming.recent)) {
    const local = listRecent(999)
    const keyOf = (r: RecentItem) => `${r.source}:${r.playUrl}`
    const seen = new Set(local.map(keyOf))
    const merged = [...local]
    for (const raw of incoming.recent) {
      const r = raw as Partial<RecentItem> | null
      if (!r || typeof r.playUrl !== 'string' || !r.playUrl.startsWith('http')) continue
      const item: RecentItem = {
        source: String(r.source ?? ''),
        playUrl: r.playUrl,
        title: String(r.title ?? ''),
        anime: String(r.anime ?? ''),
        ep: String(r.ep ?? ''),
        ts: numOr(r.ts, 0),
      }
      if (seen.has(keyOf(item))) continue
      seen.add(keyOf(item))
      merged.push(item)
      summary.recent += 1
    }
    if (summary.recent > 0) {
      merged.sort((a, b) => b.ts - a.ts)
      writeJson('recent', merged.slice(0, RECENT_LIMIT))
    }
  }

  return summary
}

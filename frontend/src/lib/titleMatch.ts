/**
 * 自动匹配的「选哪个条目 / 选哪一集」规则。
 *
 * 抽出来是因为原实现有两处只看顺序、不看内容：
 *  1. 搜索结果直接取 `hits[0]`。数据源按相关度排，「进击的巨人」的第一条常常是
 *     「进击的巨人 最终季」或某个番外合集 —— 用户点第一季第一集，播出来的是别的季。
 *  2. 剧集在**扁平列表**里找第一个 `sortNum === 1`。多线路站点常把「番外」「OVA」
 *     线路排在正片前面，于是「第 1 集」命中的是番外第 1 集。
 *
 * 这里做两件事：给搜索命中的标题按「季号/类型/名字相似度」打分挑最优；
 * 选集时优先挑非番外/OVA 线路，同页挂多季时按条目季号选对那一路，并按
 * Bangumi 的剧集名做一次对齐。
 */
import type { ChannelGroupHit, EpisodeHit, EpisodesResult } from '../types'

export type TitleKind = 'tv' | 'movie' | 'special' | 'final'

export interface TitleInfo {
  /** 去掉季号/类型标记与常见站名装饰后的主标题（小写、仅保留字母数字与汉字） */
  core: string
  /** 识别出的季号；null = 没写季号（通常就是第一季） */
  season: number | null
  kind: TitleKind
}

const CN_NUM: Record<string, number> = {
  一: 1, 二: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9, 十: 10,
  十一: 11, 十二: 12, 十三: 13, 十四: 14, 十五: 15, 十六: 16, 十七: 17, 十八: 18, 十九: 19, 二十: 20,
}

/** 常见站点装饰词：不参与标题相似度比较（否则「XX 在线观看」会把相似度拉低） */
const DECORATIONS = /在线观看|在线播放|高清完整版|高清|完整版|无删减|中文字幕|中字|国语版|国语|日语版|日语|全集|免费观看|更新至第?\d+[集话]/g

const SEASON_CN = /第\s*([一二三四五六七八九十两0-9]{1,3})\s*[季期]/
const SEASON_CN_PART = /第\s*([一二三四五六七八九十两0-9]{1,3})\s*部/
const SEASON_EN = /\bseason\s*(\d{1,2})\b/
const SEASON_ORDINAL = /\b(\d{1,2})(?:st|nd|rd|th)\s*season\b/
const SEASON_SHORT = /\bs(\d{1,2})\b/
const FINAL = /最终季|最终章|完结篇|last\s*season/i
const MOVIE = /剧场版|映画|the\s*movie/i
const SPECIAL = /番外|ova|oad|特别篇|特典|\bsp\b|外传/i

function toNumber(raw: string): number | null {
  if (/^\d+$/.test(raw)) return Number(raw)
  return CN_NUM[raw.replace(/两/, '二')] ?? null
}

/** 归一化：全角转半角、小写、去掉标点空白 */
export function normalizeTitle(raw: string): string {
  return raw
    .normalize('NFKC')
    .toLowerCase()
    .replace(/[\s\u3000]+/g, '')
    .replace(/[!-/:-@[-`{-~。、，<《》「」『』（）()【】\[\]·:：;；!?！？…—－]/g, '')
}

export function parseTitleInfo(raw: string): TitleInfo {
  const text = raw.normalize('NFKC').toLowerCase()
  let season: number | null = null
  let kind: TitleKind = 'tv'

  const cn = SEASON_CN.exec(text) ?? SEASON_CN_PART.exec(text)
  const en = SEASON_EN.exec(text) ?? SEASON_ORDINAL.exec(text) ?? SEASON_SHORT.exec(text)
  if (cn) season = toNumber(cn[1])
  else if (en) season = Number(en[1])

  if (FINAL.test(text)) kind = 'final'
  if (MOVIE.test(text)) kind = 'movie'
  if (SPECIAL.test(text)) kind = 'special'

  // 主标题：先摘掉季号与类型标记，再摘掉站点装饰词
  const core = normalizeTitle(
    text
      .replace(SEASON_CN, ' ')
      .replace(SEASON_CN_PART, ' ')
      .replace(SEASON_EN, ' ')
      .replace(SEASON_ORDINAL, ' ')
      .replace(SEASON_SHORT, ' ')
      .replace(FINAL, ' ')
      .replace(MOVIE, ' ')
      .replace(SPECIAL, ' ')
      .replace(DECORATIONS, ' '),
  )
  return { core, season, kind }
}

/** 两个字符串的二元组 Dice 系数（0..1），给「名字大体相同但写法有出入」一点分 */
function dice(a: string, b: string): number {
  if (a.length < 2 || b.length < 2) return a === b ? 1 : 0
  const grams = new Map<string, number>()
  for (let i = 0; i < a.length - 1; i++) grams.set(a.slice(i, i + 2), (grams.get(a.slice(i, i + 2)) ?? 0) + 1)
  let hits = 0
  for (let i = 0; i < b.length - 1; i++) {
    const g = b.slice(i, i + 2)
    const n = grams.get(g) ?? 0
    if (n > 0) {
      hits++
      grams.set(g, n - 1)
    }
  }
  return (2 * hits) / (a.length + b.length - 2)
}

function seasonScore(subject: TitleInfo, hit: TitleInfo): number {
  if (subject.kind === 'movie') return hit.kind === 'movie' ? 35 : -30
  if (subject.kind === 'special') return hit.kind === 'special' ? 35 : -25
  if (subject.kind === 'final') return hit.kind === 'final' ? 35 : hit.season != null ? -25 : -5
  // 下面是「正片」的判定
  if (hit.kind === 'movie') return -30
  if (hit.kind === 'special') return -25
  if (hit.kind === 'final') return -35 // 第一季 vs 最终季：除非只有它，否则不选
  if (subject.season != null) {
    if (hit.season == null) return 5
    return hit.season === subject.season ? 35 : -60
  }
  // 主体没写季号 = 第一季；命中写「第一季」与不写是同一回事，不能当「别的季」罚分
  if (hit.season == null || hit.season === 1) return 15
  return -45
}

/** 一个搜索命中的标题相对某个条目标题的得分（对多个候选标题取最高） */
export function scoreHit(hitName: string, subjectNames: string[]): number {
  const hit = parseTitleInfo(hitName)
  let best = Number.NEGATIVE_INFINITY
  for (const raw of subjectNames) {
    if (!raw.trim()) continue
    const subject = parseTitleInfo(raw)
    let score = 0
    if (hit.core && subject.core) {
      if (hit.core === subject.core) score += 100
      else if (hit.core.includes(subject.core) || subject.core.includes(hit.core)) score += 45
      else score += Math.round(30 * dice(hit.core, subject.core))
    }
    score += seasonScore(subject, hit)
    best = Math.max(best, score)
  }
  return best === Number.NEGATIVE_INFINITY ? 0 : best
}

/** 从搜索结果里挑最像这个条目的一个；分不出高下时保留数据源原本的顺序 */
export function pickBestHit<T extends { name: string }>(hits: T[], subjectNames: string[]): T | null {
  if (hits.length === 0) return null
  let best = hits[0]
  let bestScore = scoreHit(best.name, subjectNames)
  for (const hit of hits.slice(1)) {
    const score = scoreHit(hit.name, subjectNames)
    if (score > bestScore) {
      best = hit
      bestScore = score
    }
  }
  return best
}

const SPECIAL_EPISODE = /番外|ova|oad|特别篇|特典|预告|花絮|\bpv\d*\b|\bsp\d*\b|外传|剧场版|电影版|映画/i

/** 条目本身是剧场版/番外时，来源里叫「剧场版」「特别篇」的剧集就是正主，不能再当噪音 */
function subjectKindOf(subjectNames: string[]): TitleKind {
  const kinds = subjectNames.filter((n) => n.trim()).map((n) => parseTitleInfo(n).kind)
  return kinds.find((k) => k === 'movie') ?? kinds.find((k) => k === 'special') ?? 'tv'
}

function pickFromPools(
  pools: EpisodeHit[][],
  want: number,
  wantedName: string,
  allow: (e: EpisodeHit) => boolean,
): EpisodeHit | null {
  for (const pool of pools) {
    const exact = pool.filter((e) => e.sortNum === want && allow(e))
    if (exact.length === 0) continue
    if (wantedName) {
      // 条目里这一集的标题（如「第1话 标题」）与源站集名对得上时优先 —— 同季不同版本时更准
      const byName = exact.find((e) => {
        const name = normalizeTitle(e.name)
        return name.length > 0 && (name.includes(wantedName) || wantedName.includes(name))
      })
      if (byName) return byName
    }
    return exact[0]
  }
  return null
}

/**
 * 选一集。优先在「频道名不像番外」的线路里找；同页挂多季时按条目季号挑线路，
 * 再看名字对齐，最后才退回扁平列表。
 *
 * `preferSpecial`：用户点的这一集本身是 SP/OP/ED（Bangumi 的 type != 0）。
 * 此时只认番外线路，且找不到就返回 null 交给手动选集 —— 绝不退回去播正片。
 */
export function pickEpisode(
  result: EpisodesResult,
  want: number,
  subjectEpisodeName = '',
  subjectNames: string[] = [],
  preferSpecial = false,
): EpisodeHit | null {
  /** 条目本身是第几季；没写季号按第一季 */
  const subjectSeason =
    subjectNames.map((n) => parseTitleInfo(n).season).find((s): s is number => s != null) ?? 1
  const subjectKind = subjectKindOf(subjectNames)
  const allowSpecialNames = subjectKind === 'movie' || subjectKind === 'special'
  const wantedName = normalizeTitle(subjectEpisodeName)
  const allow = (e: EpisodeHit) => allowSpecialNames || !SPECIAL_EPISODE.test(e.name)
  const channelIsSpecial = (g: ChannelGroupHit) => SPECIAL_EPISODE.test(g.name)
  const byCount = (a: ChannelGroupHit, b: ChannelGroupHit) => b.episodes.length - a.episodes.length

  if (preferSpecial && (subjectKind === 'tv' || subjectKind === 'final')) {
    const specialPools = result.groups.filter(channelIsSpecial).sort(byCount).map((g) => g.episodes)
    if (specialPools.length === 0) return null
    return pickFromPools(specialPools, want, wantedName, () => true)
  }

  const pools: EpisodeHit[][] = []
  /** 只含「同季/未标季」的池：找不到精确集时的下一集兜底不能跳到别的季 */
  const fallbackPools: EpisodeHit[][] = []
  const candidates = allowSpecialNames ? result.groups : result.groups.filter((g) => !channelIsSpecial(g))
  if (candidates.length > 0) {
    // 频道名写明了季号的按条目季号分组：同季 > 未写季号（通常是正片）> 其它季。
    // 不能只按集数挑：同页挂全季的站点上，集数最多的往往是别的季。
    const marked = candidates.map((g) => ({ g, season: parseTitleInfo(g.name).season }))
    const same = marked.filter((x) => x.season === subjectSeason).sort((a, b) => byCount(a.g, b.g))
    const none = marked.filter((x) => x.season == null).sort((a, b) => byCount(a.g, b.g))
    const other = marked.filter((x) => x.season != null && x.season !== subjectSeason).sort((a, b) => byCount(a.g, b.g))
    for (const x of same) {
      pools.push(x.g.episodes)
      fallbackPools.push(x.g.episodes)
    }
    for (const x of none) {
      pools.push(x.g.episodes)
      fallbackPools.push(x.g.episodes)
    }
    // 其它季只参与精确集数匹配，不参与「往后找一集」的兜底
    for (const x of other) pools.push(x.g.episodes)
  } else if (result.groups.length > 0) {
    // 来源只有番外/OVA 线路：正片点第 N 集时不能拿番外顶替，交给手动选集
    return null
  }
  // 没有线路分组时才用扁平列表；有分组时它是所有季的并集，
  // 把它塞进 pools 会让「本季缺这一集」退化成另一季的同号剧集。
  if (result.groups.length === 0) {
    pools.push(result.episodes)
    fallbackPools.push(result.episodes)
  }

  const hit = pickFromPools(pools, want, wantedName, allow)
  if (hit) return hit
  // 兜底也只在同季/未标季的 pool 内找，绝不回到跨季的扁平并集或「其它季」。
  for (const pool of fallbackPools) {
    const later = pool
      .filter((e) => e.sortNum != null && e.sortNum >= want && allow(e))
      .sort((a, b) => (a.sortNum as number) - (b.sortNum as number))
    if (later.length > 0) return later[0]
  }
  return null
}

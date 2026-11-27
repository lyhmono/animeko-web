/**
 * /play 的 URL 契约。
 *
 * 单独成一份的理由：构造 /play 参数的代码曾在播放页、条目页、数据源详情页各写一份，
 * 而三份会各自漏字段，且漏了都是静默的——没有报错，只是功能不成立：
 * 漏 subject 时，自动切到下一话后 subjectId=0，条目页的「看到第几话」永久停在上一话；
 * 漏 surl 时，从数据源详情页进去的连播只能连一集，与条目页入口行为不一致。
 */

/** 一集的可播放身份。 */
export interface PlayEpisode {
  source: string
  play: string
  title: string
  anime: string
  /** 数据源侧集号：连播时会变 */
  ep: string
  /** 条目侧 Bangumi 集号：条目进度只能写这个，不能写数据源集号 */
  bep?: string
}

export interface PlayLink {
  /** 条目 id：播放页据此回写「看到第几话」。0 或缺省表示不是从条目页来的 */
  subjectId?: number
  /** 数据源详情页地址：连播链条在「URL 里没带下一集」时靠它重新取列表 */
  subjectUrl?: string
  /** 紧邻的下一集：带上则下一跳走零成本的旧路径，不必重新取列表 */
  next?: PlayEpisode | null
}

export function buildPlayParams(ep: PlayEpisode, link: PlayLink = {}): URLSearchParams {
  const params = new URLSearchParams({
    source: ep.source,
    play: ep.play,
    title: ep.title,
    anime: ep.anime,
    ep: ep.ep,
  })
  if (ep.bep) params.set('bep', ep.bep)
  if (link.subjectId && link.subjectId > 0) params.set('subject', String(link.subjectId))
  if (link.subjectUrl) params.set('surl', link.subjectUrl)
  if (link.next) params.set('next', JSON.stringify(link.next))
  return params
}

export function buildPlayUrl(ep: PlayEpisode, link: PlayLink = {}): string {
  return `/play?${buildPlayParams(ep, link).toString()}`
}

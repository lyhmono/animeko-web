import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import Poster from './Poster'
import { api } from '../api'
import { favoriteIdSet } from '../lib/storage'
import { IconHeartFilled } from '../lib/icons'
import type { BgmCalendarDay } from '../types'

/**
 * 首页「每日放送」区块 —— 和 Animeko App 首页同一形态：按星期分组的
 * 番剧放送表。数据来自 Bangumi /v0/calendar（官方端点）。
 *
 * 三条设计约束，都是踩过的：
 *  1) **静默降级**：社区镜像（多数自托管部署的实际通道）不镜像 calendar，
 *     官方域名在境外 VPS 上又不可达 —— 拉不到就整块隐藏。
 *     首页的功能是搜索，放送表是增色，不许它把首页变成一张错误页。
 *  2) 默认选中「今天」，但今天可能一条都没有（周一/周二放送少），
 *     此时落到第一个非空的日子，别给用户看一张空网格。
 *  3) 封面仍走 Poster 两级回退（直连失败 → /api/proxy）。
 */
export default function AiringCalendar() {
  const [days, setDays] = useState<BgmCalendarDay[] | null>(null)
  const [selected, setSelected] = useState<number>(-1)
  /** 只看已收藏：放送表的真正价值是「我追的今天更新了吗」，不是资讯墙 */
  const [onlyFav, setOnlyFav] = useState(false)
  // 只取值：收藏在本页不会被修改（改收藏去条目页），所以不需要 setter。
  // 每次进首页组件都会重新挂载，这里读到的就是最新收藏。
  const [favIds] = useState<Set<number>>(() => favoriteIdSet())

  useEffect(() => {
    const ctrl = new AbortController()
    api
      .bangumiCalendar(ctrl.signal)
      .then((list) => {
        if (ctrl.signal.aborted) return
        // 整周都没有条目时直接按「没有放送表」处理：否则会渲染出一排空 tab
        // 和一句「这一天没有放送安排」，看着像功能坏了。空周通常意味着
        // 上游返回了结构正确但内容为空的数据（镜像的残缺实现）。
        if (list.every((d) => d.items.length === 0)) {
          setDays([])
          return
        }
        setDays(list)
        // Bangumi 的 weekday.id：1=周一 … 7=周日；本地 getDay()：0=周日
        const todayId = ((new Date().getDay() + 6) % 7) + 1
        const todayIndex = list.findIndex((d) => d.weekday.id === todayId && d.items.length > 0)
        // 今天没番就落到第一个有番的日子；前面已保证至少有一天非空，所以
        // 这里一定命中，Math.max 只是兜底不是常态
        const firstNonEmpty = list.findIndex((d) => d.items.length > 0)
        setSelected(todayIndex >= 0 ? todayIndex : firstNonEmpty)
      })
      .catch(() => {
        // 镜像 502 / 官方不可达 / 网络抖动：当作「没有放送表」。
        // 注意这里不能 setDays(null)：初始值就是 null（=加载中），失败置 null
        // 等于什么都没变、骨架屏永远转。空列表是「确认没有」的稳定终态。
        if (!ctrl.signal.aborted) setDays([])
      })
    return () => ctrl.abort()
  }, [])

  const current = useMemo(
    () => (days && selected >= 0 && selected < days.length ? days[selected] : null),
    [days, selected],
  )

  /** 当前这一天的条目（按「只看我追的」过滤后） */
  const items = useMemo(() => {
    const list = current?.items ?? []
    return onlyFav ? list.filter((i) => favIds.has(i.id)) : list
  }, [current, onlyFav, favIds])

  /** 整周里有多少部是已收藏的 —— 为 0 时「只看我追的」没有意义，直接禁用 */
  const favCountInWeek = useMemo(() => {
    if (!days) return 0
    return days.reduce((n, d) => n + d.items.filter((i) => favIds.has(i.id)).length, 0)
  }, [days, favIds])

  // 失败（空数组）整块不渲染 —— 包括标题和骨架。骨架屏从「每日放送」标题下
  // 突然消失比一开始就没有更扎眼，首页会跳一下版。
  if (days !== null && days.length === 0) return null
  if (days === null) {
    // 加载中先不占高度：服务端有 12 秒预算，但多数情况 1~3 秒就回来。
    // 给骨架是为了让首屏不空着，而骨架出现/消失都在同一个位置，不会推动下方内容。
    return (
      <section className="airing">
        <div className="result-grid">
          {Array.from({ length: 6 }).map((_, i) => (
            <div className="skeleton-card" key={i}>
              <div className="skeleton skeleton-cover" />
              <div className="skeleton skeleton-line" />
            </div>
          ))}
        </div>
      </section>
    )
  }

  return (
    <section className="airing">
      <h2 className="section-title">
        每日放送
        <span className="count">Bangumi 放送表</span>
        <button
          className={`airing-filter${onlyFav ? ' active' : ''}`}
          onClick={() => setOnlyFav((v) => !v)}
          // 已开启时永远可点：否则「开着筛选 + 收藏被清空」会让按钮变成
          // disabled 的 active 状态，用户既看不到东西也关不掉筛选
          disabled={favCountInWeek === 0 && !onlyFav}
          aria-pressed={onlyFav}
          title={
            favCountInWeek === 0
              ? '本周放送里没有你收藏的番剧'
              : `本周有 ${favCountInWeek} 部已收藏`
          }
        >
          <IconHeartFilled size={12} />
          只看我追的{favCountInWeek > 0 ? `（${favCountInWeek}）` : ''}
        </button>
      </h2>
      <div className="airing-tabs" role="tablist" aria-label="选择星期">
        {days.map((d, i) => {
          const favOnDay = d.items.filter((it) => favIds.has(it.id)).length
          const dim = onlyFav ? favOnDay === 0 : d.items.length === 0
          return (
            <button
              key={d.weekday.id}
              className={`airing-tab${i === selected ? ' active' : ''}${dim ? ' empty' : ''}`}
              onClick={() => setSelected(i)}
              role="tab"
              aria-selected={i === selected}
              title={
                onlyFav
                  ? favOnDay > 0
                    ? `追的番 ${favOnDay} 部`
                    : '这天没有你追的番'
                  : d.items.length > 0
                    ? `${d.items.length} 部放送`
                    : '无放送'
              }
            >
              {d.weekday.cn || d.weekday.en || `周${i + 1}`}
            </button>
          )
        })}
      </div>
      {items.length === 0 && (
        <div className="empty">
          {onlyFav ? '这一天没有你收藏的番剧在放送。' : '这一天没有放送安排'}
        </div>
      )}
      <div className="result-grid">
        {items.map((item) => {
          const title = item.name_cn || item.name
          const score = item.rating?.score ?? item.score
          return (
            <Link key={item.id} className="subject-card" to={`/subject/${item.id}`}>
              {favIds.has(item.id) && (
                <span className="fav-badge" title="已收藏">
                  <IconHeartFilled size={13} />
                </span>
              )}
              <Poster
                className="cover"
                src={item.images?.large ?? item.images?.common ?? ''}
                srcSet={
                  item.images
                    ? [
                        [item.images.grid, '100w'],
                        [item.images.common, '200w'],
                        [item.images.large, '400w'],
                      ]
                        .filter(([u]) => !!u)
                        .map(([u, w]) => `${u} ${w}`)
                        .join(', ')
                    : undefined
                }
                alt={title}
                sizes="(max-width: 720px) 45vw, 200px"
              />
              <div className="info">
                <div className="title">{title}</div>
                <div className="meta">
                  {score ? <span className="score">{score.toFixed(1)}</span> : null}
                  {item.eps ? <span>{item.eps} 话</span> : null}
                </div>
              </div>
            </Link>
          )
        })}
      </div>
    </section>
  )
}

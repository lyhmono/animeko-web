import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { getProgress, listRecent, onDocChange, type ProgressEntry, type RecentItem } from '../lib/storage'
import { IconPlay } from '../lib/icons'

/**
 * 首页「继续观看」。
 *
 * 为什么单独做一块而不是复用底部的「最近观看」：
 * 最近观看只记「点过哪一集」，不知道看到哪儿了 —— 用户真正的诉求是
 * 「上次那集还剩几分钟，接着看」。进度（progress）与最近（recent）是两份
 * 独立数据，按 (源, 播放地址) 才能对上，所以这里把它们拼起来。
 *
 * 没有进度的条目也保留（可能是刚点开就退出的），但排在后面并且不显示进度条 ——
 * 直接隐藏会让「我刚看过的那集」凭空消失，比显示一个空进度条更困惑。
 */
interface ContinueItem extends RecentItem {
  progress: ProgressEntry | null
}

export default function ContinueWatching() {
  const [items, setItems] = useState<ContinueItem[]>([])

  useEffect(() => {
    const refill = () => {
      const rows = listRecent()
        .map((r) => ({ ...r, progress: getProgress(r.source, r.playUrl) }))
        // 有进度的优先，其次按最近时间；两者都已在 listRecent 里按 ts 排过
        .sort((a, b) => Number(!!b.progress) - Number(!!a.progress))
        .slice(0, 6)
      setItems(rows)
    }
    refill()
    // 启动时读取失败、后来点「重新读取」成功的场景：本组件是 `if (items.length === 0) return null`，
    // 不重抄就一直不出现，用户只当这台机器上根本没有「继续观看」这回事
    return onDocChange(refill)
  }, [])

  if (items.length === 0) return null

  return (
    <section className="continue" data-testid="continue-watching">
      <h2 className="section-title">
        <IconPlay size={17} />
        继续观看
      </h2>
      <div className="continue-list">
        {items.map((it) => {
          const p = it.progress
          const pct = p && p.duration > 0 ? Math.min(100, (p.time / p.duration) * 100) : 0
          const remainMin =
            p && p.duration > 0 ? Math.max(1, Math.round((p.duration - p.time) / 60)) : null
          // duration 未知（部分源不报总时长）时给「已看 N 分钟」：
          // 只说「从头开始」是错的，用户明明看了 20 分钟
          const watchedMin = p && p.duration <= 0 ? Math.max(1, Math.round(p.time / 60)) : null
          return (
            <Link
              key={`${it.source}:${it.playUrl}`}
              className="continue-card"
              to={`/play?${new URLSearchParams({
                source: it.source,
                play: it.playUrl,
                title: it.title,
                anime: it.anime,
                ep: it.ep,
              }).toString()}`}
            >
              <span className="continue-play">
                <IconPlay size={15} />
              </span>
              <span className="continue-body">
                <span className="continue-title">{it.anime || it.title}</span>
                <span className="continue-meta">
                  {it.ep ? `第 ${it.ep} 话` : it.title}
                  {remainMin != null && <span className="continue-remain">还剩约 {remainMin} 分钟</span>}
                  {watchedMin != null && (
                    <span className="continue-remain">已看约 {watchedMin} 分钟</span>
                  )}
                  {!p && <span className="continue-remain dim">从头开始</span>}
                </span>
                {p && p.duration > 0 && (
                  <span className="continue-bar" aria-hidden>
                    <span className="continue-bar-fill" style={{ width: `${pct}%` }} />
                  </span>
                )}
              </span>
            </Link>
          )
        })}
      </div>
    </section>
  )
}

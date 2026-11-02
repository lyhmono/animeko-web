import type { ReactNode } from 'react'

/**
 * Plex 式横向分区容器。
 *
 * 只负责「标题 + 横向滚动」这一层，内容仍由调用方渲染（海报卡、最近观看卡等），
 * 这样它既可以用在发现页的推荐位，也可以用在收藏页/条目页，而不绑定任何数据模型。
 */
export default function RailSection({
  title,
  subtitle,
  action,
  children,
}: {
  title: string
  subtitle?: string
  action?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="rail-section">
      <div className="rail-head">
        <h2 className="section-title">
          {title}
          {subtitle && <span className="count">{subtitle}</span>}
        </h2>
        {action}
      </div>
      <div className="rail-scroller">{children}</div>
    </section>
  )
}
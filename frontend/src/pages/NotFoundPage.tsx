import { Link } from 'react-router-dom'
import { IconSearch } from '../lib/icons'

export default function NotFoundPage() {
  return (
    <div className="fade-in">
      <div className="empty">
        <span className="empty-emoji">🧭</span>
        <h2 style={{ margin: '8px 0 4px' }}>404 · 页面不存在</h2>
        <p style={{ color: 'var(--text-secondary)', fontSize: 13, marginBottom: 18 }}>
          地址可能已失效或输入有误，去首页搜索你想看的番剧吧
        </p>
        <Link className="btn" to="/">
          <IconSearch size={15} />
          返回首页
        </Link>
      </div>
    </div>
  )
}

import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  children: ReactNode
  /**
   * 变了就清掉错误状态重新渲染子树。
   * 没有它的话，报错之后点导航（导航在本边界之外）只会一直停在「页面出错了」：
   * URL 换了、内容区还是上一个页面的错误兜底，用户只能整页刷新。
   */
  resetKey?: unknown
}

interface State {
  error: Error | null
}

/**
 * 渲染错误边界：任何页面抛异常时展示兜底 UI 而不是整页白屏。
 * 「重试」清空错误状态重新挂载子组件；「返回首页」做一次全量刷新。
 */
export default class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('ErrorBoundary caught:', error, info.componentStack)
  }

  componentDidUpdate(prev: Props): void {
    // 换页面就算一次新的开始：错误已经被"离开"了，不该继续挡住新页面
    if (prev.resetKey !== this.props.resetKey && this.state.error) {
      this.setState({ error: null })
    }
  }

  // 只清 state 是不行的：React.lazy 失败后 payload._status 会永久停在 rejected，
  // 重新渲染抛的还是同一个错误。重新部署导致旧 chunk 404 时（本项目最常见的故障），
  // 只有整页重载才能拿到新的 index.html 与新的哈希文件名。
  private retry = () => window.location.reload()

  render() {
    const { error } = this.state
    if (!error) return this.props.children
    return (
      <div className="error-boundary" role="alert">
        <span className="empty-emoji">🛠️</span>
        <h2>页面出错了</h2>
        <p className="error-boundary-msg">{error.message || '未知错误'}</p>
        <div style={{ display: 'flex', gap: 10, marginTop: 16 }}>
          <button className="btn" onClick={this.retry}>
            重试
          </button>
          <button className="btn btn-secondary" onClick={() => window.location.assign('/')}>
            返回首页
          </button>
        </div>
      </div>
    )
  }
}

import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  children: ReactNode
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

  private retry = () => this.setState({ error: null })

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

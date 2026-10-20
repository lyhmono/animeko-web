import { useState } from 'react'
import { signIn } from '../lib/auth'

/**
 * 登录页：单共享账号、个人自用。
 * 口令只发给 /api/session/login，前端不做任何「记住口令」的持久化 ——
 * 会话由服务端下发的 HttpOnly Cookie 维持，本机存的只是一个显示用的账号名。
 */
export default function LoginPage({ onSuccess }: { onSuccess: () => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    if (busy) return
    if (!password) {
      setError('请输入口令')
      return
    }
    setBusy(true)
    setError('')
    const message = await signIn(username.trim(), password)
    setBusy(false)
    if (message) {
      setError(message)
      // 口令错了要清掉，否则用户会对着一个已经填过坏值的表单反复提交
      setPassword('')
      return
    }
    onSuccess()
  }

  return (
    <div className="login-shell">
      <form
        className="login-card"
        onSubmit={(e) => {
          e.preventDefault()
          void submit()
        }}
      >
        <div className="login-brand">
          <span className="dot" />
          Animeko Web
        </div>
        <h1 className="login-title">登录</h1>
        <p className="login-sub">收藏、观看进度与数据源都保存在这台服务器上，登录后继续。</p>

        <label className="field">
          <span>账号</span>
          <input
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoFocus
            placeholder="ANIMEKO_AUTH_USER"
          />
        </label>
        <label className="field">
          <span>口令</span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            aria-invalid={error ? true : undefined}
            aria-describedby={error ? 'login-error' : undefined}
            placeholder="口令，或 ANIMEKO_AUTH_TOKEN"
          />
        </label>

        {/* role=alert：读屏用户否则完全不知道「点了进入但什么也没发生」 */}
        {error && (
          <div className="error-box" id="login-error" role="alert" aria-live="assertive">
            {error}
          </div>
        )}

        <button className="btn btn-block" type="submit" disabled={busy}>
          {busy ? '登录中…' : '进入'}
        </button>

        {error.includes('未配置登录凭据') && (
          // 服务端没开鉴权却又落到登录页（例如停在旧标签页里）时给出出口，
          // 否则这个表单永远提交不成功
          <button className="btn btn-secondary btn-block" type="button" onClick={() => window.location.assign('/')}>
            这台服务没开登录，直接进站点
          </button>
        )}

        <p className="login-hint">
          连续输错会被限速（默认 5 分钟内 8 次）。忘记口令就在服务端环境变量里改一份新的，重启后生效。
        </p>
      </form>
    </div>
  )
}

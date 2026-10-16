/**
 * 登录状态。服务端用 HttpOnly Cookie 承载会话，前端只能靠 /api/health 回读「当前是否已登录」，
 * 所以这里的职责是三件事：探测、登录、注销，外加把「会话失效」这件事广播出去。
 */
import { api, errMsg, UNAUTHORIZED_EVENT } from '../api'

export interface SessionInfo {
  /** 服务端是否启用了鉴权（没配凭据就是 false，此时不需要登录） */
  authEnabled: boolean
  authenticated: boolean
}

const USER_KEY = 'animeko-web:user'

/** 只是显示用的名字，来自上一次登录的响应；不含任何凭据。 */
export function rememberedUser(): string {
  try {
    return sessionStorage.getItem(USER_KEY) ?? ''
  } catch {
    return ''
  }
}

export function rememberUser(name: string): void {
  try {
    if (name) sessionStorage.setItem(USER_KEY, name)
    else sessionStorage.removeItem(USER_KEY)
  } catch {
    /* 隐私模式下忽略 */
  }
}

export async function probeSession(): Promise<SessionInfo> {
  const health = await api.health()
  return { authEnabled: health.authEnabled, authenticated: health.authenticated }
}

/** 登录。返回错误文案（空串表示成功），把服务端的人话错误直接透出，便于用户判断是口令错还是被限速。 */
export async function signIn(username: string, password: string): Promise<string> {
  try {
    const resp = await api.login(username, password)
    rememberUser(resp.user)
    return ''
  } catch (e) {
    return errMsg(e)
  }
}

export async function signOut(): Promise<void> {
  try {
    await api.logout()
  } finally {
    rememberUser('')
  }
}

/** 订阅「会话已失效」事件（任何请求收到 401 时触发）。 */
export function onSessionLost(listener: () => void): () => void {
  window.addEventListener(UNAUTHORIZED_EVENT, listener)
  return () => window.removeEventListener(UNAUTHORIZED_EVENT, listener)
}

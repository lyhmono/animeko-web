/**
 * 登录状态。服务端用 HttpOnly Cookie 承载会话，前端只能靠 /api/health 回读「当前是否已登录」，
 * 所以这里的职责是三件事：探测、登录、注销，外加把「会话失效」这件事广播出去。
 */
import { api, errMsg, invalidateApiSession, UNAUTHORIZED_EVENT } from '../api'

export interface SessionInfo {
  /** 服务端是否启用了鉴权（没配凭据就是 false，此时不需要登录） */
  authEnabled: boolean
  authenticated: boolean
}

const USER_KEY = 'animeko-web:user'
let authEpoch = 0
/** 仅由本次运行里的成功登录响应设置；sessionStorage 中的名字只是显示值。 */
let verifiedUser = ''

export function verifiedSessionUser(): string {
  return verifiedUser
}

/** 会话失效立即撤销本次验证身份，显示名暂留给未同步副本标注原账号。 */
export function invalidateSessionIdentity(): void {
  authEpoch++
  invalidateApiSession()
  verifiedUser = ''
}

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

export async function probeSession(signal?: AbortSignal): Promise<SessionInfo> {
  const epoch = authEpoch
  const health = await api.health(signal)
  signal?.throwIfAborted()
  if (epoch !== authEpoch) throw new DOMException('旧会话探测已失效', 'AbortError')
  return { authEnabled: health.authEnabled, authenticated: health.authenticated }
}

/** 登录。返回错误文案（空串表示成功），把服务端的人话错误直接透出，便于用户判断是口令错还是被限速。 */
export async function signIn(username: string, password: string, signal?: AbortSignal): Promise<string> {
  const epoch = ++authEpoch
  try {
    const resp = await api.login(username, password, signal)
    signal?.throwIfAborted()
    if (epoch !== authEpoch) return '登录请求已失效，请重试'
    invalidateApiSession()
    verifiedUser = resp.user
    rememberUser(resp.user)
    return ''
  } catch (e) {
    return errMsg(e)
  }
}

export async function signOut(signal?: AbortSignal): Promise<void> {
  const epoch = ++authEpoch
  await api.logout(signal)
  signal?.throwIfAborted()
  if (epoch !== authEpoch) throw new DOMException('旧注销请求已失效', 'AbortError')
  invalidateApiSession()
  verifiedUser = ''
  // 网络失败不代表服务器已经注销，用户名与待写副本都要留到确认结果后。
  rememberUser('')
}

/** 订阅「会话已失效」事件（任何请求收到 401 时触发）。 */
export function onSessionLost(listener: () => void): () => void {
  window.addEventListener(UNAUTHORIZED_EVENT, listener)
  return () => window.removeEventListener(UNAUTHORIZED_EVENT, listener)
}

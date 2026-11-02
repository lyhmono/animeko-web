/**
 * 设备本地偏好：主题、播放器音量/倍速、直连播放开关。
 *
 * 这些不是账号数据，不进 userdata.json：手机外放与台式机音箱、不同网络下
 * 直连是否可用，都是「这台设备」的属性。放在 localStorage，不参与同步。
 * 从 storage.ts 抽出来是为了让账号数据层只剩「文档 + 同步」，
 * 偏好读写不会把整份 userdata 同步逻辑拖进任何只需要主题的组件。
 */

export type Theme = 'dark' | 'light'

const THEME_COLORS: Record<Theme, string> = { dark: '#0b0c0f', light: '#f6f3ec' }

/**
 * 主题只存本机：它是「这台设备看着舒服与否」的偏好，不是收藏。
 * 放在 localStorage 还有一次必须在渲染前读到（否则首屏会闪一下错误配色）。
 */
const THEME_KEY = 'animeko-web:theme'

function localRead(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function localWrite(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    // 隐私模式/超额时静默失败：偏好丢了不影响功能
  }
}

/** 同步 address bar / PWA 主题色，避免亮色主题下浏览器 UI 仍是暗色 */
export function syncThemeColor(theme: Theme): void {
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_COLORS[theme])
}

export function getTheme(): Theme {
  return localRead(THEME_KEY) === 'light' ? 'light' : 'dark'
}

export function setTheme(theme: Theme): void {
  localWrite(THEME_KEY, theme)
}

/**
 * 音量 / 静音 / 倍速存在浏览器本地，和主题同一个理由：
 * 它们是**设备属性**而不是账号属性 —— 手机外放要 60%，台式机接音箱要 100%，
 * 同步到服务器只会让两台设备互相打架。所以不走 userdata 那条同步链路。
 */
const PLAYER_PREFS_KEY = 'animeko-web:player'

export interface PlayerPrefs {
  volume: number
  muted: boolean
  rate: number
}

const DEFAULT_PLAYER_PREFS: PlayerPrefs = { volume: 1, muted: false, rate: 1 }

export function getPlayerPrefs(): PlayerPrefs {
  const raw = localRead(PLAYER_PREFS_KEY)
  if (!raw) return { ...DEFAULT_PLAYER_PREFS }
  try {
    const parsed = JSON.parse(raw) as Partial<PlayerPrefs>
    const volume = typeof parsed.volume === 'number' && parsed.volume >= 0 && parsed.volume <= 1
      ? parsed.volume
      : DEFAULT_PLAYER_PREFS.volume
    const rate = typeof parsed.rate === 'number' && parsed.rate >= 0.25 && parsed.rate <= 4
      ? parsed.rate
      : DEFAULT_PLAYER_PREFS.rate
    return { volume, muted: parsed.muted === true, rate }
  } catch {
    // 坏值不该让播放器起不来：退回默认
    return { ...DEFAULT_PLAYER_PREFS }
  }
}

export function savePlayerPrefs(prefs: PlayerPrefs): void {
  localWrite(PLAYER_PREFS_KEY, JSON.stringify(prefs))
}

/**
 * 直连播放：浏览器直接连数据源站点取流，不经过这台服务器。
 *
 * 默认开：视频流量是这台机器最大的一笔开销，而且直连没有中转延迟。
 * 代价是源站能看到观看者的 IP；遇到防盗链（要特定 Referer/Cookie）或 CDN 不给
 * CORS 时直连会失败，播放器会自动回退到 /api/proxy 再试一次。
 * 存在本机：同一份部署在不同网络环境下（手机蜂窝 / 家里宽带）可以各选各的。
 */
const DIRECT_PLAY_KEY = 'animeko-web:direct-playback'

export function getDirectPlayback(): boolean {
  // 只有明确写过 off 才关；没设置过按「开」处理
  return localRead(DIRECT_PLAY_KEY) !== 'off'
}

export function setDirectPlayback(on: boolean): void {
  localWrite(DIRECT_PLAY_KEY, on ? 'on' : 'off')
}
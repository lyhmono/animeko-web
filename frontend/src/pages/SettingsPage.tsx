import { useEffect, useState } from 'react'
import { api } from '../api'
import { getTheme, setTheme, syncThemeColor, type Theme } from '../lib/storage'
import { IconMoon, IconSun } from '../lib/icons'

export default function SettingsPage() {
  const [health, setHealth] = useState<{
    version: string
    danmakuConfigured: boolean
    authEnabled: boolean
  } | null>(null)
  const [theme, setThemeState] = useState<Theme>(getTheme)

  useEffect(() => {
    api.health().then(setHealth).catch(() => setHealth(null))
  }, [])

  const applyTheme = (t: Theme) => {
    setTheme(t)
    document.documentElement.dataset.theme = t
    syncThemeColor(t)
    setThemeState(t)
    // 同步导航栏的切换按钮状态
    window.dispatchEvent(new Event('animeko-theme-change'))
  }

  return (
    <div className="fade-in" style={{ maxWidth: 720 }}>
      <h2 className="section-title">设置</h2>

      <div className="settings-group card">
        <h3>外观</h3>
        <div className="settings-row">
          <span className="settings-label">主题模式（选择会保存在本地浏览器）</span>
          <span style={{ display: 'inline-flex', gap: 8 }}>
            <button
              className={`btn btn-sm ${theme === 'dark' ? '' : 'btn-secondary'}`}
              onClick={() => applyTheme('dark')}
            >
              <IconMoon size={14} />
              暗色
            </button>
            <button
              className={`btn btn-sm ${theme === 'light' ? '' : 'btn-secondary'}`}
              onClick={() => applyTheme('light')}
            >
              <IconSun size={14} />
              亮色
            </button>
          </span>
        </div>
      </div>

      <div className="settings-group card">
        <h3>服务信息</h3>
        <div style={{ fontSize: 13, color: 'var(--text-secondary)', lineHeight: 2 }}>
          <div>版本：{health?.version ?? '—'}</div>
          <div>
            弹幕服务：
            {health?.danmakuConfigured
              ? '已配置'
              : '未配置（在容器环境变量中设置 ANIMEKO_DANMAKU_APP_ID / ANIMEKO_DANMAKU_APP_SECRET）'}
          </div>
          <div>
            访问鉴权：
            {health?.authEnabled
              ? '已开启'
              : '未开启（公网暴露时建议设置 ANIMEKO_AUTH_ENABLED=true 与 ANIMEKO_AUTH_TOKEN）'}
          </div>
        </div>
      </div>

      <div className="settings-group card">
        <h3>环境变量（docker-compose 中配置）</h3>
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', fontSize: 12, borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ color: 'var(--text-dim)', textAlign: 'left' }}>
                <th style={{ padding: '6px 8px' }}>变量</th>
                <th style={{ padding: '6px 8px' }}>默认</th>
                <th style={{ padding: '6px 8px' }}>说明</th>
              </tr>
            </thead>
            <tbody>
              {[
                ['ANIMEKO_PORT', '8080', '服务端口'],
                ['ANIMEKO_DATA_DIR', './data', '数据目录（数据源配置等）'],
                ['ANIMEKO_AUTH_ENABLED', 'false', '是否开启访问鉴权'],
                ['ANIMEKO_AUTH_TOKEN', '—', 'Bearer Token（公网推荐）'],
                ['ANIMEKO_DANMAKU_APP_ID', '—', '弹弹play AppId'],
                ['ANIMEKO_DANMAKU_APP_SECRET', '—', '弹弹play AppSecret'],
                [
                  'ANIMEKO_BANGUMI_BASE_URLS',
                  'api.bgm.tv,bgmapi.anibt.net',
                  'Bangumi API 地址列表（逗号分隔，按顺序故障转移；官方被墙时可用社区镜像）',
                ],
              ].map(([k, v, d]) => (
                <tr key={k} style={{ borderTop: '1px solid var(--border)' }}>
                  <td style={{ padding: '6px 8px', fontFamily: 'monospace', color: 'var(--accent)' }}>
                    {k}
                  </td>
                  <td style={{ padding: '6px 8px', fontFamily: 'monospace' }}>{v}</td>
                  <td style={{ padding: '6px 8px' }}>{d}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="settings-group card">
        <h3>关于</h3>
        <p style={{ fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.8 }}>
          Animeko Web 是{' '}
          <a href="https://github.com/open-ani/animeko" style={{ color: 'var(--accent)' }}>
            animeko
          </a>
          （AGPL-3.0）的服务端化实现：数据源采集、Bangumi 元数据、弹弹play 弹幕与视频代理运行在服务端容器中，浏览器负责播放与弹幕渲染。
          本项目同样以 AGPL-3.0 授权。数据与缓存仅保存在本容器，请遵守所在地法律法规，仅用于个人学习与使用。
        </p>
      </div>
    </div>
  )
}

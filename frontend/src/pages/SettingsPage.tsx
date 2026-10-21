import { useEffect, useState } from 'react'
import { api, errMsg } from '../api'
import BangumiChannelCard from '../components/BangumiChannelCard'
import {
  exportUserData,
  flushUserData,
  isUserDataLoaded,
  getDirectPlayback,
  getSyncState,
  importUserData,
  setDirectPlayback,
  getTheme,
  onSyncChange,
  setTheme,
  syncThemeColor,
  type SyncState,
  type Theme,
} from '../lib/storage'
import { rememberedUser } from '../lib/auth'
import { IconMoon, IconSun } from '../lib/icons'

export default function SettingsPage() {
  const [health, setHealth] = useState<{ version: string; authEnabled: boolean; authenticated: boolean } | null>(null)
  // 健康检查失败 ≠ 功能未开启：混在一起会给出与实际相反的配置建议
  const [healthError, setHealthError] = useState('')
  const [healthLoading, setHealthLoading] = useState(true)
  const [theme, setThemeState] = useState<Theme>(getTheme)
  const [directPlayback, setDirectPlaybackState] = useState<boolean>(getDirectPlayback)
  const [sync, setSync] = useState<SyncState>(getSyncState)
  const [saving, setSaving] = useState(false)
  /** 备份/恢复的结果提示（成功与失败共用一条，避免两套状态） */
  const [backupMsg, setBackupMsg] = useState('')

  useEffect(() => onSyncChange(setSync), [])

  useEffect(() => {
    let cancelled = false
    api
      .health()
      .then((h) => {
        if (cancelled) return
        setHealth(h)
        setHealthError('')
      })
      .catch((e) => {
        if (cancelled) return
        setHealth(null)
        setHealthError(errMsg(e))
      })
      .finally(() => {
        if (!cancelled) setHealthLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  // 导航栏切换主题后同步本页按钮高亮（事件 detail 携带主题名，无 detail 时回退本地存储）
  useEffect(() => {
    const onThemeChange = (e: Event) => {
      const detail = (e as CustomEvent<unknown>).detail
      setThemeState(detail === 'dark' || detail === 'light' ? detail : getTheme())
    }
    window.addEventListener('animeko-theme-change', onThemeChange)
    return () => window.removeEventListener('animeko-theme-change', onThemeChange)
  }, [])

  const doExport = () => {
    let text: string
    try {
      text = exportUserData()
    } catch (e) {
      setBackupMsg(`导出失败：${e instanceof Error ? e.message : '未知错误'}`)
      return
    }
    const blob = new Blob([text], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `animeko-backup-${new Date().toISOString().slice(0, 10)}.json`
    a.click()
    // 立刻 revoke 会让部分浏览器来不及取数据，延后一拍再释放
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    setBackupMsg(`已导出 ${(text.length / 1024).toFixed(1)} KB`)
  }

  const doImport = async (file: File | undefined) => {
    if (!file) return
    // 上限只是防呆：备份是纯文本，几千条收藏也就几百 KB。
    // 没有上限时选错一个大文件会把主线程卡在 JSON.parse 上。
    if (file.size > 10 * 1024 * 1024) {
      setBackupMsg('导入失败：文件超过 10MB，多半不是备份文件')
      return
    }
    setBackupMsg('导入中…')
    try {
      const summary = importUserData(await file.text())
      // 立刻推给服务器：去抖那 400ms 里关掉页面就等于没导入
      await flushUserData()
      const parts = [
        summary.favorites > 0 ? `收藏 ${summary.favorites} 部` : '',
        summary.sourceFavorites > 0 ? `数据源收藏 ${summary.sourceFavorites} 条` : '',
        summary.subjectProgress > 0 ? `条目进度 ${summary.subjectProgress} 条` : '',
        summary.progress > 0 ? `播放进度 ${summary.progress} 条` : '',
        summary.searchHistory > 0 ? `搜索历史 ${summary.searchHistory} 条` : '',
        summary.recent > 0 ? `最近观看 ${summary.recent} 条` : '',
      ].filter(Boolean)
      // flushUserData 不抛错（失败都落在同步状态里），所以这里必须自己看一眼：
      // 否则「已合并」会盖在一次失败的服务器写入上面，用户以为备份已经安全落地，
      // 关掉页面就又回到导入前的样子
      const st = getSyncState()
      const tail = st.status === 'error' ? `（已在本机生效，但保存到服务器失败：${st.error || '未知原因'}）` : ''
      setBackupMsg(parts.length > 0 ? `已合并：${parts.join('、')}${tail}` : `备份里没有比本地更新的数据${tail}`)
    } catch (e) {
      setBackupMsg(`导入失败：${e instanceof Error ? e.message : '未知错误'}`)
    }
  }

  const applyTheme = (t: Theme) => {
    setTheme(t)
    document.documentElement.dataset.theme = t
    syncThemeColor(t)
    setThemeState(t)
    // 同步导航栏的切换按钮状态
    window.dispatchEvent(new CustomEvent('animeko-theme-change', { detail: t }))
  }

  return (
    <div className="fade-in" style={{ maxWidth: 720 }}>
      <h2 className="section-title">设置</h2>

      <div className="settings-group card">
        <h3>播放方式</h3>
        <div className="settings-row">
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--text-secondary)' }}>
            <input
              type="checkbox"
              checked={directPlayback}
              onChange={(e) => {
                const on = e.target.checked
                setDirectPlaybackState(on)
                setDirectPlayback(on)
              }}
            />
            直连数据源播放（不经过这台服务器）
          </label>
        </div>
        <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 6 }}>
          直连时视频流量不占这台服务器的带宽，代价是源站能看到观看者的 IP。遇到防盗链（要特定
          Referer/Cookie）或 CDN 不允许跨域时，播放器会自动回退到服务器中转，不需要手动切换。
        </div>
      </div>

      <div className="settings-group card">
        <h3>数据与登录</h3>
        <div className="settings-row">
          <div>
            <div style={{ fontSize: 13, color: 'var(--text-secondary)' }}>
              收藏、观看进度、搜索历史与最近观看保存在服务器的 <code>data/userdata.json</code>，
              换设备或清浏览器缓存都不会丢。
            </div>
            <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 6 }}>
              当前状态：
              {sync.status === 'error' ? (
                <span style={{ color: 'var(--danger)' }}>{sync.error || '保存失败'}</span>
              ) : sync.status === 'ready' ? (
                '已与服务器同步'
              ) : (
                '有改动待写入服务器'
              )}
              {rememberedUser() && `　·　已登录：${rememberedUser()}`}
            </div>
          </div>
          <button
            className="btn btn-secondary btn-sm"
            disabled={saving}
            onClick={() => {
              setSaving(true)
              void flushUserData().finally(() => setSaving(false))
            }}
          >
            {saving ? '保存中…' : '立即保存'}
          </button>
        </div>
        {!healthLoading && health && !health.authEnabled && (
          <div className="error-box" style={{ marginTop: 10 }}>
            登录未启用：任何能连到这台机器的人都能读取你的数据源与个人数据。
            设置 <code>ANIMEKO_AUTH_USER</code> 与 <code>ANIMEKO_AUTH_PASSWORD</code>（或
            <code>ANIMEKO_AUTH_TOKEN</code>）后重启即可启用登录页。
          </div>
        )}
      </div>

      <div className="settings-group card" data-testid="backup-card">
        <h3>数据备份</h3>
        <div style={{ fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.8 }}>
          收藏、观看进度、搜索历史都只存在这台服务器上。<b>容器一删就没了</b>，
          建议偶尔导出一份放到别处。
          <br />
          导入是<b>合并</b>而不是覆盖：按 id / 播放地址去重，同一条保留本地版本，
          所以选错文件也不会把现有数据冲掉。
        </div>
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginTop: 12 }}>
          <button
            className="btn btn-secondary btn-sm"
            data-testid="backup-export"
            onClick={doExport}
            disabled={!isUserDataLoaded()}
            title={isUserDataLoaded() ? '' : '个人数据还没读到，先点页脚的「重新读取」'}
          >
            导出备份
          </button>
          <label
            className="btn btn-secondary btn-sm"
            style={{ cursor: isUserDataLoaded() ? 'pointer' : 'not-allowed', opacity: isUserDataLoaded() ? 1 : 0.5 }}
          >
            导入备份
            <input
              type="file"
              accept=".json,application/json"
              style={{ display: 'none' }}
              onChange={(e) => {
                // 先取出文件再清空 value：不清的话选同一个文件第二次不触发 change
                const file = e.target.files?.[0]
                e.target.value = ''
                void doImport(file)
              }}
            />
          </label>
        </div>
        {backupMsg && (
          <div
            style={{
              marginTop: 10,
              fontSize: 12,
              color: backupMsg.startsWith('导入失败') ? 'var(--danger)' : 'var(--success)',
            }}
          >
            {backupMsg}
          </div>
        )}
      </div>

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

      {/* 服务端持久化配置：和主题不同，它对所有访问这台服务的设备生效 */}
      <BangumiChannelCard />

      <div className="settings-group card">
        <h3>服务信息</h3>
        <div style={{ fontSize: 13, color: 'var(--text-secondary)', lineHeight: 2 }}>
          <div>版本：{healthLoading ? '检测中…' : (health?.version ?? '—')}</div>
          {healthError ? (
            <div>
              后端不可用：{healthError}（无法确认访问鉴权状态，请检查服务是否在运行）
            </div>
          ) : (
            <>
              <div>
                访问鉴权：
                {healthLoading
                  ? '检测中…'
                  : health?.authEnabled
                    ? '已开启'
                    : '未开启（公网暴露时建议设置 ANIMEKO_AUTH_ENABLED=true 与 ANIMEKO_AUTH_TOKEN）'}
              </div>
            </>
          )}
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
                ['ANIMEKO_DATA_DIR', './data', '数据目录（数据源配置 + userdata.json 个人数据）'],
                ['ANIMEKO_AUTH_USER / PASSWORD', '—', '登录账号与口令；配了即自动开启登录'],
                ['ANIMEKO_AUTH_TOKEN', '—', '也可以只配一个口令，登录页当密码用（公网推荐随机长串）'],
                ['ANIMEKO_AUTH_ENABLED', '自动', '显式写 false 可关掉登录页（前面已有反代鉴权时才需要）'],
                ['ANIMEKO_COOKIE_SECURE', 'false', '反代配好 HTTPS 后设为 true'],
                ['ANIMEKO_ALLOW_PRIVATE_TARGETS', 'false', '允许代理/抓取访问内网地址（仅局域网 NAS 源需要）'],
                ['ANIMEKO_ALLOW_CROSS_ORIGIN', 'false', '放开「写接口只允许同源」检查（反代改写 Host 时才需要）'],
                [
                  'ANIMEKO_BANGUMI_BASE_URLS',
                  'api.bgm.tv,bgmapi.anibt.net',
                  '自动通道的候选地址（逗号分隔）；更省心的切换方式在上方「Bangumi 数据源通道」卡片',
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
          （AGPL-3.0）的服务端化实现：数据源采集、Bangumi 元数据与视频代理运行在服务端容器中，浏览器只负责界面与播放。
          本项目同样以 AGPL-3.0 授权。收藏与进度等个人数据保存在服务器的数据目录里，不上传到任何第三方；
          请遵守所在地法律法规，仅用于个人学习与使用。
        </p>
      </div>
    </div>
  )
}

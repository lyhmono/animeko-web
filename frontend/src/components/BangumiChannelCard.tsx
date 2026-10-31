import { useEffect, useRef, useState } from 'react'
import { api, errMsg } from '../api'
import type { BgmChannelInfo, BgmChannelKind, BgmProbeResult } from '../types'

/**
 * 设置页的「Bangumi 通道」卡片。
 *
 * 存在的理由：官方 api.bgm.tv 与社区镜像能力不对等（每日放送表只有官方有），
 * 部署在官方不可达地区的人想要首页放送表，就得能明确切到官方、或贴自己的中转地址。
 * 这个配置是服务端持久化的（改完立即生效，重启后保留），而不是浏览器本地偏好。
 *
 * 交互流程刻意是「先探测、再保存」：
 *  - 选中某通道后「测试连通性」会探测**该选项**对应的地址（哪怕还没保存），
 *    让站长在切过去之前就知道「官方在你这里通不通」「镜像没有放送表」；
 *  - 探测结果原样列出来，ok 与否都展示 —— 全红也是一种答案，
 *    说明这台 VPS 到 Bangumi 两边都不通，该考虑自建中转而不是继续猜。
 */

const CHANNEL_LABELS: { value: BgmChannelKind; label: string; note: string }[] = [
  { value: 'AUTO', label: '自动（官方优先，故障转移）', note: '使用 ANIMEKO_BANGUMI_BASE_URLS 配置的候选列表，能用的先赢' },
  { value: 'OFFICIAL', label: '仅官方 api.bgm.tv', note: '功能最全（含每日放送），部分地区较慢或不可达' },
  { value: 'MIRROR', label: '仅社区镜像', note: '境外 VPS 上通常最快，但不提供每日放送端点' },
  { value: 'CUSTOM', label: '自定义地址', note: '自建中转或可信第三方，可填多个按顺序尝试' },
]

export default function BangumiChannelCard() {
  const [info, setInfo] = useState<BgmChannelInfo | null>(null)
  const [loadError, setLoadError] = useState('')
  const [selected, setSelected] = useState<BgmChannelKind>('AUTO')
  const [customText, setCustomText] = useState('')
  const [probing, setProbing] = useState(false)
  const [probeResults, setProbeResults] = useState<BgmProbeResult[] | null>(null)
  const [probeError, setProbeError] = useState('')
  const [saving, setSaving] = useState(false)
  const [saveMsg, setSaveMsg] = useState('')
  const probeRef = useRef<AbortController | null>(null)
  const probeSeqRef = useRef(0)
  const saveRef = useRef<AbortController | null>(null)

  useEffect(() => {
    const ctrl = new AbortController()
    api
      .bangumiChannel(ctrl.signal)
      .then((i) => {
        if (ctrl.signal.aborted) return
        setInfo(i)
        setSelected(i.channel)
        setCustomText(i.customUrls.join('\n'))
        setProbeResults(null)
      })
      .catch((e) => { if (!ctrl.signal.aborted) setLoadError(errMsg(e)) })
    return () => {
      ctrl.abort()
      probeSeqRef.current++
      probeRef.current?.abort()
      probeRef.current = null
      saveRef.current?.abort()
      saveRef.current = null
    }
  }, [])

  const invalidateProbe = () => {
    probeSeqRef.current++
    probeRef.current?.abort()
    probeRef.current = null
    setProbing(false)
    setProbeResults(null)
    setProbeError('')
  }

  // 选完通道先探一下：切通道最坏的结果是「切过去才发现不通」，一次探测能避免
  const doProbe = () => {
    if (probeRef.current || saveRef.current) return
    const ctrl = new AbortController()
    probeRef.current = ctrl
    const seq = ++probeSeqRef.current
    const current = () => !ctrl.signal.aborted && seq === probeSeqRef.current && probeRef.current === ctrl
    setProbing(true)
    setProbeError('')
    setProbeResults(null)
    api
      .probeBangumiChannel({ channel: selected, customUrls: splitUrls(customText) }, ctrl.signal)
      .then((results) => { if (current()) setProbeResults(results) })
      .catch((e) => { if (current()) setProbeError(errMsg(e)) })
      .finally(() => {
        if (current()) {
          probeRef.current = null
          setProbing(false)
        }
      })
  }

  const doSave = () => {
    if (saveRef.current) return
    const urls = splitUrls(customText)
    // 本地能判定的事不要往返一趟：CUSTOM 没填地址是**点下去就知道**的错误，
    // 等服务端 400 再显示会让按钮白转一圈
    if (selected === 'CUSTOM' && urls.length === 0) {
      setSaveMsg('保存失败：自定义通道需要至少一个地址')
      return
    }
    invalidateProbe()
    const ctrl = new AbortController()
    saveRef.current = ctrl
    const current = () => !ctrl.signal.aborted && saveRef.current === ctrl
    setSaving(true)
    setSaveMsg('')
    api
      .setBangumiChannel(selected, urls, ctrl.signal)
      .then((i) => {
        if (!current()) return
        setInfo(i)
        setSaveMsg('已保存，立即生效（无需重启）')
        setProbeResults(null)
      })
      .catch((e) => { if (current()) setSaveMsg(`保存失败：${errMsg(e)}`) })
      .finally(() => {
        if (current()) {
          saveRef.current = null
          setSaving(false)
        }
      })
  }

  if (loadError) {
    return (
      <div className="settings-group card">
        <h3>Bangumi 数据源通道</h3>
        <div className="error-box">读取通道配置失败：{loadError}</div>
      </div>
    )
  }
  if (!info) {
    return (
      <div className="settings-group card">
        <h3>Bangumi 数据源通道</h3>
        <div className="skeleton" style={{ height: 96 }} />
      </div>
    )
  }

  return (
    <div className="settings-group card">
      <h3>Bangumi 数据源通道</h3>
      <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 12, lineHeight: 1.7 }}>
        番剧资料来自 Bangumi。官方与社区镜像能力不同：<b>每日放送</b>只有官方提供，
        镜像更快但放送表会缺失。官方在你的网络里不通时，可切镜像或填自建中转。
      </div>

      <div className="channel-options" role="radiogroup" aria-label="通道选择">
        {CHANNEL_LABELS.map((c) => (
          <label key={c.value} className={`channel-option${selected === c.value ? ' active' : ''}`}>
            <input
              type="radio"
              name="bgm-channel"
              value={c.value}
              checked={selected === c.value}
              disabled={saving}
              onChange={() => {
                setSelected(c.value)
                invalidateProbe()
                setSaveMsg('')
              }}
            />
            <span>
              <span className="channel-option-label">
                {c.label}
                {info.channel === c.value && <span className="tag tag-accent">当前</span>}
              </span>
              <span className="channel-option-note">{c.note}</span>
            </span>
          </label>
        ))}
      </div>

      {selected === 'CUSTOM' && (
        <textarea
          className="channel-custom"
          rows={3}
          placeholder={'https://your-relay.example.com\n（每行一个，按顺序尝试）'}
          value={customText}
          disabled={saving}
          onChange={(e) => {
            setCustomText(e.target.value)
            invalidateProbe()
          }}
          aria-label="自定义 Bangumi API 地址"
        />
      )}

      <div style={{ fontSize: 12, color: 'var(--text-dim)', margin: '8px 0 12px' }}>
        当前生效地址：{info.effectiveBases.join('、')}
        {!info.calendarSupported && info.channel === 'MIRROR' && (
          <span style={{ color: 'var(--warn)' }}>　·　此通道不含每日放送</span>
        )}
      </div>

      <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
        <button className="btn btn-secondary btn-sm" onClick={doProbe} disabled={probing || saving}>
          {probing ? '探测中…' : '测试连通性'}
        </button>
        <button className="btn btn-sm" onClick={doSave} disabled={saving}>
          {saving ? '保存中…' : '保存'}
        </button>
      </div>

      {probeError && <div className="error-box" style={{ marginTop: 10 }}>探测失败：{probeError}</div>}
      {probeResults && (
        <div style={{ marginTop: 10, fontSize: 12, color: 'var(--text-secondary)', lineHeight: 1.9 }}>
          {probeResults.length === 0 && <span>该通道没有可探测的地址（自定义通道请先填地址）。</span>}
          {probeResults.map((r) => (
            <div key={r.base} className="probe-row">
              <span style={{ color: r.ok ? 'var(--success)' : 'var(--danger)' }}>
                {r.ok ? '✓' : '✗'} {r.base}
              </span>
              <span className="probe-detail">
                {r.detail}（{r.latencyMs}ms）
              </span>
              {/* 核心可用但没有放送表：单独标出来，别让人误以为这个地址能用全功能 */}
              {r.ok && !r.calendarOk && (
                <span className="tag" title="搜索/条目可用，但每日放送拉不到">
                  无放送表
                </span>
              )}
            </div>
          ))}
          {probeResults.length > 0 && !probeResults.some((r) => r.ok) && (
            <div className="error-box" style={{ marginTop: 6 }}>
              全部候选都连不上。若官方在你的网络里超时，可切镜像或填自建中转；
              镜像只能保证搜索与条目，每日放送仍需官方或完整中转。
            </div>
          )}
        </div>
      )}
      {saveMsg && (
        <div style={{ marginTop: 10, fontSize: 12, color: saveMsg.startsWith('保存失败') ? 'var(--danger)' : 'var(--success)' }}>
          {saveMsg}
        </div>
      )}
    </div>
  )
}

function splitUrls(text: string): string[] {
  return text
    .split('\n')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}

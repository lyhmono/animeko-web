import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, errMsg } from '../api'
import type { StoredSource, SubscriptionCandidate, SubscriptionPreview } from '../types'
import { IconLink } from '../lib/icons'
import { notifySourcesChanged } from '../lib/sourceEvents'
import { useModalFocus } from '../lib/useModalFocus'

/** 订阅来源：填地址（服务端去取）或贴正文（站点挂了/需要登录时自己导出） */
function subSource(url: string, text: string): { url?: string; text?: string } | null {
  if (text.trim()) return { text: text.trim() }
  if (url.trim()) return { url: url.trim() }
  return null
}

export default function SourcesPage() {
  const [sources, setSources] = useState<StoredSource[]>([])
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [importText, setImportText] = useState('')
  const [importing, setImporting] = useState(false)
  // ---- 聚合订阅 ----
  const [subUrl, setSubUrl] = useState('')
  const [subText, setSubText] = useState('')
  const [showSubText, setShowSubText] = useState(false)
  const [preview, setPreview] = useState<SubscriptionPreview | null>(null)
  const [picked, setPicked] = useState<Record<string, boolean>>({})
  const [previewing, setPreviewing] = useState(false)
  const [subImporting, setSubImporting] = useState(false)
  const previewSeqRef = useRef(0)
  // 关键词按数据源名分别保存：共用一个 state 会让所有输入框互相覆盖
  const [testKeyword, setTestKeyword] = useState<Record<string, string>>({})
  /** 批量健康检查进行中（按钮文案与禁用） */
  const [checking, setChecking] = useState(false)
  const [testResult, setTestResult] = useState<Record<string, string>>({})
  const [pendingDelete, setPendingDelete] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)
  const deletingRef = useRef(false)
  const deleteModalRef = useRef<HTMLDivElement>(null)
  const closeDelete = () => {
    if (!deletingRef.current) setPendingDelete(null)
  }
  useModalFocus(deleteModalRef, pendingDelete !== null, closeDelete)
  const [pendingToggles, setPendingToggles] = useState<Record<string, boolean>>({})
  const [loading, setLoading] = useState(true)
  const [loaded, setLoaded] = useState(false)
  const mountedRef = useRef(true)
  const checkRef = useRef<AbortController | null>(null)
  const singleChecksRef = useRef(new Map<string, AbortController>())
  const [pendingTests, setPendingTests] = useState<Record<string, boolean>>({})
  const reloadRef = useRef<AbortController | null>(null)
  const reloadSeqRef = useRef(0)

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      checkRef.current?.abort()
      checkRef.current = null
      for (const ctrl of singleChecksRef.current.values()) ctrl.abort()
      singleChecksRef.current.clear()
      reloadSeqRef.current++
      reloadRef.current?.abort()
    }
  }, [])

  const reload = useCallback(async () => {
    if (!mountedRef.current) return
    reloadRef.current?.abort()
    const ctrl = new AbortController()
    reloadRef.current = ctrl
    const seq = ++reloadSeqRef.current
    const current = () => mountedRef.current && seq === reloadSeqRef.current && !ctrl.signal.aborted
    setLoading(true)
    try {
      const list = await api.sources(ctrl.signal)
      if (!current()) return
      setSources(list)
      setLoaded(true)
    } catch (e) {
      if (!current()) return
      setError(errMsg(e))
      setLoaded(false)
    } finally {
      if (current()) {
        reloadRef.current = null
        setLoading(false)
      }
    }
  }, [])

  useEffect(() => { void reload() }, [reload])


  const doImport = async () => {
    if (!importText.trim()) return
    setImporting(true)
    setError('')
    setNotice('')
    try {
      const args = await api.importSource(importText.trim())
      notifySourcesChanged()
      setNotice(`已导入数据源「${args.name}」`)
      setImportText('')
      await reload()
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setImporting(false)
    }
  }

  const pickedNames = useMemo(
    () => (preview ? preview.candidates.filter((c) => picked[c.name]).map((c) => c.name) : []),
    [preview, picked],
  )

  /**
   * 输入变了就让在飞的预览作废：否则旧请求晚到会把旧清单/旧指纹覆盖到新 URL 上，
   * 用户随后按新 URL 导入，服务端的指纹校验会莫名失败。
   */
  const invalidatePreview = () => {
    previewSeqRef.current++
    setPreviewing(false)
    setPreview(null)
    setPicked({})
  }

  /** 拉订阅：只做「看清楚有哪些源」，写不写进仓库由下一步的勾选决定 */
  const fetchPreview = async () => {
    if (previewing) return
    const source = subSource(subUrl, subText)
    if (!source) {
      setError('填一个聚合订阅地址，或展开「贴订阅正文」直接粘 JSON')
      return
    }
    const seq = ++previewSeqRef.current
    setPreviewing(true)
    setError('')
    setNotice('')
    try {
      const result = await api.previewSubscription(source)
      if (seq !== previewSeqRef.current) return
      setPreview(result)
      // 默认勾选「本机还没有」的条目：已存在的源很可能是自己改过的，
      // 默认把它们勾上等于诱导一次静默覆盖
      setPicked(
        Object.fromEntries(result.candidates.map((c) => [c.name, !c.exists])) as Record<string, boolean>,
      )
      if (result.candidates.length === 0) {
        setError(`这个订阅里没有可用的数据源${result.rejected.length ? `（${result.rejected.length} 条被跳过）` : ''}`)
      }
    } catch (e) {
      if (seq === previewSeqRef.current) {
        setError(errMsg(e))
        setPreview(null)
      }
    } finally {
      if (seq === previewSeqRef.current) setPreviewing(false)
    }
  }

  const importPicked = async () => {
    const source = subSource(subUrl, subText)
    const names = pickedNames
    if (!source || names.length === 0) return
    setSubImporting(true)
    setError('')
    setNotice('')
    try {
      // 带上预览时的内容指纹：订阅若在「拉取预览」和「导入」之间被更新过，
      // 服务端会拒绝并提示重新预览，而不是按一份和用户看到的不一样的清单导入
      const result = await api.importSubscription({ ...source, names, fingerprint: preview?.fingerprint })
      if (result.added.length || result.updated.length) notifySourcesChanged()
      const parts = [
        result.added.length ? `新增 ${result.added.length} 个` : '',
        result.updated.length ? `覆盖 ${result.updated.length} 个` : '',
        result.failed.length ? `失败 ${result.failed.length} 个` : '',
      ].filter(Boolean)
      setNotice(`导入完成：${parts.join('、') || '没有变化'}`)
      if (result.failed.length) {
        setError(result.failed.join('；'))
      }
      setPreview(null)
      setSubText('')
      await reload()
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setSubImporting(false)
    }
  }

  const pickAll = (value: boolean) => {
    if (!preview) return
    setPicked(Object.fromEntries(preview.candidates.map((c) => [c.name, value])))
  }

  const cancelSingle = (name: string) => {
    singleChecksRef.current.get(name)?.abort()
    singleChecksRef.current.delete(name)
    setPendingTests((pending) => ({ ...pending, [name]: false }))
    setTestResult((results) => ({ ...results, [name]: '已取消' }))
  }

  const testSearch = async (name: string) => {
    if (checkRef.current || singleChecksRef.current.has(name)) return
    const keyword = (testKeyword[name] ?? '').trim()
    if (!keyword) return
    const ctrl = new AbortController()
    singleChecksRef.current.set(name, ctrl)
    const current = () => mountedRef.current && singleChecksRef.current.get(name) === ctrl && !ctrl.signal.aborted
    setPendingTests((pending) => ({ ...pending, [name]: true }))
    setTestResult((r) => ({ ...r, [name]: '搜索中…' }))
    try {
      const hits = await api.sourceSearch(name, keyword, ctrl.signal)
      if (current()) setTestResult((r) => ({ ...r, [name]: `找到 ${hits.length} 个结果` }))
    } catch (e) {
      if (current()) setTestResult((r) => ({ ...r, [name]: `失败：${errMsg(e)}` }))
    } finally {
      if (current()) {
        singleChecksRef.current.delete(name)
        setPendingTests((pending) => ({ ...pending, [name]: false }))
      }
    }
  }

  const cancelChecks = () => {
    checkRef.current?.abort()
    checkRef.current = null
    setChecking(false)
    setTestResult((results) => Object.fromEntries(Object.entries(results).map(([name, result]) =>
      [name, result === '检查中…' ? '已取消' : result],
    )))
  }

  /**
   * 一键检查所有启用源。
   *
   * 为什么值得做：十几个源里通常挂着两三个死源，而单个「测试」要逐条点、
   * 每条还得自己敲关键词 —— 实际没人会去点，于是死源一直留在搜索链路里
   * 拖慢每一次搜索（它们各自要等到超时才失败）。
   * 并发压到 3：与搜索页同一条理由，浏览器对同源只有 6 条连接，
   * 全占满会让页面自己的其它请求排队。
   */
  const checkAll = async () => {
    if (checkRef.current || singleChecksRef.current.size > 0) return
    const targets = sources.filter((s) => s.enabled).map((s) => s.arguments.name)
    if (targets.length === 0) return
    const ctrl = new AbortController()
    checkRef.current = ctrl
    const current = () => mountedRef.current && checkRef.current === ctrl && !ctrl.signal.aborted
    setChecking(true)
    setTestResult((r) => {
      const next = { ...r }
      for (const n of targets) next[n] = '检查中…'
      return next
    })
    let cursor = 0
    const worker = async () => {
      while (current() && cursor < targets.length) {
        const name = targets[cursor++]
        try {
          const hits = await api.sourceSearch(name, '动画', ctrl.signal)
          if (current()) setTestResult((r) => ({ ...r, [name]: `✓ 可用（${hits.length} 个结果）` }))
        } catch (e) {
          if (current()) setTestResult((r) => ({ ...r, [name]: `✗ 失败：${errMsg(e)}` }))
        }
      }
    }
    await Promise.all(Array.from({ length: Math.min(3, targets.length) }, worker))
    if (current()) {
      checkRef.current = null
      setChecking(false)
    }
  }

  const toggle = async (name: string, enabled: boolean) => {
    setError('')
    setPendingToggles((p) => ({ ...p, [name]: true }))
    try {
      await api.setSourceEnabled(name, enabled)
      notifySourcesChanged()
      await reload()
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setPendingToggles((p) => ({ ...p, [name]: false }))
    }
  }

  const remove = (name: string) => {
    // 统一弹窗确认（替代原生 window.confirm：阻塞、样式割裂、移动端体验差）
    setPendingDelete(name)
  }

  const confirmDelete = async () => {
    const name = pendingDelete
    if (!name || deletingRef.current) return
    deletingRef.current = true
    setDeleting(true)
    setError('')
    try {
      await api.deleteSource(name)
      notifySourcesChanged()
      await reload()
    } catch (e) {
      if (mountedRef.current) setError(errMsg(e))
    } finally {
      deletingRef.current = false
      if (mountedRef.current) {
        setDeleting(false)
        setPendingDelete(null)
      }
    }
  }

  return (
    <div>
      <h2 className="section-title" tabIndex={-1} data-modal-return-focus>
        数据源管理 <span className="count">{sources.length} 个</span>
        <button
          className="airing-filter"
          onClick={() => void checkAll()}
          disabled={checking || Object.values(pendingTests).some(Boolean) || sources.filter((s) => s.enabled).length === 0}
          title="对每个已启用的源跑一次搜索，标出不能用的"
        >
          {checking ? '检查中…' : '检查可用性'}
        </button>
        {checking && <button className="airing-filter" onClick={cancelChecks}>取消检查</button>}
      </h2>
      <p style={{ color: 'var(--text-secondary)', fontSize: 13, marginBottom: 16 }}>
        直接导入 <b>animeko</b> 的 Selector 数据源配置 JSON（factoryId: web-selector，version: 2）。
        配置格式与 animeko 完全一致，可复用社区配置。
      </p>

      {error && <div className="error-box">{error}</div>}
      {notice && (
        <div
          className="error-box"
          style={{
            background: 'rgba(74,222,128,0.08)',
            borderColor: 'rgba(74,222,128,0.3)',
            color: 'var(--success)',
          }}
        >
          {notice}
        </div>
      )}

      {loading && <div className="loading">数据源加载中…</div>}
      {sources.map((s) => (
        <div className="source-item" key={s.arguments.name}>
          <div className="info">
            <div className="name">
              {s.arguments.name} <span className="tag">{s.arguments.searchConfig.subjectFormatId}</span>
            </div>
            <div className="desc">{s.arguments.description || s.arguments.searchConfig.searchUrl}</div>
            {testResult[s.arguments.name] && (
              <div className="desc" style={{ marginTop: 4, color: 'var(--accent)' }}>
                {testResult[s.arguments.name]}
              </div>
            )}
          </div>
          <div className="actions">
            <input
              placeholder="测试关键词"
              value={testKeyword[s.arguments.name] ?? ''}
              onChange={(e) => {
                const value = e.target.value
                setTestKeyword((k) => ({ ...k, [s.arguments.name]: value }))
              }}
              onKeyDown={(e) => e.key === 'Enter' && void testSearch(s.arguments.name)}
              style={{ width: 130 }}
            />
            <button
              className="btn btn-secondary btn-sm"
              disabled={checking}
              onClick={() => pendingTests[s.arguments.name] ? cancelSingle(s.arguments.name) : void testSearch(s.arguments.name)}
            >
              {pendingTests[s.arguments.name] ? '取消测试' : '测试'}
            </button>
            <button
              className={`switch ${s.enabled ? 'on' : ''}`}
              role="switch"
              aria-checked={s.enabled}
              aria-label={`${s.arguments.name}：${s.enabled ? '已启用，点击停用' : '已停用，点击启用'}`}
              title={s.enabled ? '点击停用' : '点击启用'}
              disabled={!!pendingToggles[s.arguments.name]}
              style={pendingToggles[s.arguments.name] ? { opacity: 0.5 } : undefined}
              onClick={() => void toggle(s.arguments.name, !s.enabled)}
            />
            <button className="btn btn-danger btn-sm" onClick={() => remove(s.arguments.name)}>
              删除
            </button>
          </div>
        </div>
      ))}

      {pendingDelete && (
        <div className="modal-mask" onClick={closeDelete}>
          <div
            ref={deleteModalRef}
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-label="确认删除数据源"
            onClick={(e) => e.stopPropagation()}
            style={{ maxWidth: 400 }}
          >
            <h3>删除数据源</h3>
            <p style={{ fontSize: 13, color: 'var(--text-secondary)', margin: '10px 0 18px' }}>
              确认删除数据源「{pendingDelete}」？删除后不可恢复，需要重新导入。
            </p>
            <div className="modal-actions">
              <button className="btn btn-secondary" data-modal-autofocus disabled={deleting} onClick={closeDelete}>
                取消
              </button>
              <button className="btn btn-danger" disabled={deleting} onClick={() => void confirmDelete()}>
                {deleting ? '删除中…' : '确认删除'}
              </button>
            </div>
          </div>
        </div>
      )}

      {!loading && loaded && sources.length === 0 && (
        <div className="empty">暂无数据源，请在下方导入</div>
      )}
      {!loading && !loaded && sources.length === 0 && (
        <div className="empty">数据源列表加载失败，请检查上方错误提示后重试</div>
      )}

      <h2 className="section-title">
        <IconLink size={15} /> 聚合订阅导入
      </h2>
      <div className="card">
        <p className="card-hint">
          社区订阅是一条 URL 背后打包几十上百个数据源。先拉取预览，再只勾自己要的那几个 ——
          整包导入的坏处是：每次搜索都会把那些已经打不开的站点各试一遍，慢且没有结果。
        </p>
        <div className="sub-url-row">
          <input
            value={subUrl}
            placeholder="订阅地址，例如 https://sub.example.org/v1/css1.json"
            onChange={(e) => {
              setSubUrl(e.target.value)
              invalidatePreview()
            }}
            onKeyDown={(e) => e.key === 'Enter' && void fetchPreview()}
          />
          <button
            className="btn"
            disabled={previewing || (!subUrl.trim() && !subText.trim())}
            onClick={() => void fetchPreview()}
          >
            {previewing ? '拉取中…' : '拉取订阅'}
          </button>
        </div>
        <button className="link-btn" onClick={() => setShowSubText((v) => !v)}>
          {showSubText ? '收起订阅正文输入' : '订阅站打不开？直接贴订阅正文'}
        </button>
        {showSubText && (
          <textarea
            className="sub-text"
            placeholder='粘贴订阅 JSON，例如 {"exportedMediaSourceDataList":{"mediaSources":[…]}}'
            value={subText}
            onChange={(e) => {
              setSubText(e.target.value)
              invalidatePreview()
            }}
          />
        )}

        {preview && (
          <div className="sub-preview">
            <div className="sub-preview-head">
              <span>
                可导入 <b>{preview.candidates.length}</b> 个
                {preview.rejected.length > 0 && <>，跳过 {preview.rejected.length} 个</>}
                {preview.subscription && <span className="sub-from"> · 来自 {preview.subscription}</span>}
              </span>
              <span className="keys">
                <button className="btn btn-secondary btn-sm" onClick={() => pickAll(true)}>
                  全选
                </button>
                <button className="btn btn-secondary btn-sm" onClick={() => pickAll(false)}>
                  全不选
                </button>
              </span>
            </div>
            <ul className="sub-list">
              {preview.candidates.map((c: SubscriptionCandidate) => (
                <li className="sub-item" key={c.name}>
                  <label>
                    <input
                      type="checkbox"
                      checked={!!picked[c.name]}
                      onChange={(e) => {
                        const value = e.target.checked
                        setPicked((p) => ({ ...p, [c.name]: value }))
                      }}
                    />
                    <span className="sub-name">{c.name}</span>
                  </label>
                  <span className="sub-meta">
                    {c.tier && <span className="tag">T{c.tier}</span>}
                    {c.site && <span className="sub-site">{c.site}</span>}
                    {c.exists && (
                      <span className="tag tag-warn" title={c.enabled ? '本机已有同名源，导入会覆盖它的配置' : '本机已有同名源且当前停用'}>
                        已存在
                      </span>
                    )}
                  </span>
                </li>
              ))}
            </ul>
            {preview.rejected.length > 0 && (
              <details className="sub-rejected">
                <summary>跳过的 {preview.rejected.length} 条（订阅里结构不完整，导入也搜不出东西）</summary>
                <ul>
                  {preview.rejected.map((r, i) => (
                    <li key={i}>{r}</li>
                  ))}
                </ul>
              </details>
            )}
            <div className="sub-actions">
              <button
                className="btn"
                disabled={pickedNames.length === 0 || subImporting}
                onClick={() => void importPicked()}
              >
                {subImporting ? '导入中…' : `导入所选（${pickedNames.length} 个）`}
              </button>
              <button
                className="btn btn-secondary"
                onClick={() => {
                  setPreview(null)
                  setPicked({})
                }}
              >
                放弃这次预览
              </button>
            </div>
          </div>
        )}
      </div>

      <h2 className="section-title">导入单个数据源</h2>
      <div className="card import-box">
        <textarea
          placeholder='粘贴 animeko 数据源 JSON，例如：{"factoryId":"web-selector","version":2,"arguments":{"name":"...","searchConfig":{...}}}'
          value={importText}
          onChange={(e) => setImportText(e.target.value)}
        />
        <div style={{ marginTop: 10, display: 'flex', gap: 10 }}>
          <button className="btn" disabled={importing} onClick={() => void doImport()}>
            {importing ? '导入中…' : '导入'}
          </button>
          <button className="btn btn-secondary" onClick={() => setImportText('')}>
            清空
          </button>
        </div>
      </div>
    </div>
  )
}

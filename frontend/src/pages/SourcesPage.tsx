import { useCallback, useEffect, useState } from 'react'
import { api, errMsg } from '../api'
import type { StoredSource } from '../types'

export default function SourcesPage() {
  const [sources, setSources] = useState<StoredSource[]>([])
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [importText, setImportText] = useState('')
  const [importing, setImporting] = useState(false)
  // 关键词按数据源名分别保存：共用一个 state 会让所有输入框互相覆盖
  const [testKeyword, setTestKeyword] = useState<Record<string, string>>({})
  const [testResult, setTestResult] = useState<Record<string, string>>({})
  const [pendingDelete, setPendingDelete] = useState<string | null>(null)
  const [pendingToggles, setPendingToggles] = useState<Record<string, boolean>>({})
  const [loading, setLoading] = useState(true)
  const [loaded, setLoaded] = useState(false)

  const reload = useCallback(() => {
    api
      .sources()
      .then((list) => {
        setSources(list)
        setLoaded(true)
      })
      .catch((e) => {
        setError(errMsg(e))
        setLoaded(false)
      })
      .finally(() => setLoading(false))
  }, [])

  useEffect(reload, [reload])

  const doImport = async () => {
    if (!importText.trim()) return
    setImporting(true)
    setError('')
    setNotice('')
    try {
      const args = await api.importSource(importText.trim())
      setNotice(`已导入数据源「${args.name}」`)
      setImportText('')
      reload()
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setImporting(false)
    }
  }

  const testSearch = async (name: string) => {
    const keyword = (testKeyword[name] ?? '').trim()
    if (!keyword) return
    setTestResult((r) => ({ ...r, [name]: '搜索中…' }))
    try {
      const hits = await api.sourceSearch(name, keyword)
      setTestResult((r) => ({ ...r, [name]: `找到 ${hits.length} 个结果` }))
    } catch (e) {
      setTestResult((r) => ({ ...r, [name]: `失败：${errMsg(e)}` }))
    }
  }

  const toggle = async (name: string, enabled: boolean) => {
    setError('')
    setPendingToggles((p) => ({ ...p, [name]: true }))
    try {
      await api.setSourceEnabled(name, enabled)
      reload()
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
    if (!name) return
    setPendingDelete(null)
    setError('')
    try {
      await api.deleteSource(name)
      reload()
    } catch (e) {
      setError(errMsg(e))
    }
  }

  return (
    <div>
      <h2 className="section-title">
        数据源管理 <span className="count">{sources.length} 个</span>
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
            <button className="btn btn-secondary btn-sm" onClick={() => void testSearch(s.arguments.name)}>
              测试
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
        <div className="modal-mask" onClick={() => setPendingDelete(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: 400 }}>
            <h3>删除数据源</h3>
            <p style={{ fontSize: 13, color: 'var(--text-secondary)', margin: '10px 0 18px' }}>
              确认删除数据源「{pendingDelete}」？删除后不可恢复，需要重新导入。
            </p>
            <div className="modal-actions">
              <button className="btn btn-secondary" onClick={() => setPendingDelete(null)}>
                取消
              </button>
              <button className="btn btn-danger" onClick={() => void confirmDelete()}>
                确认删除
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

      <h2 className="section-title">导入数据源</h2>
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

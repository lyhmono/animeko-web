'use client';

// 数据源管理：启停 / 删除 / 测试 / 健康监控 / 订阅与单源导入。
import { useCallback, useEffect, useState } from 'react';
import { api, errMsg, type SourceHealth } from '@/api';
import type { StoredSource, SubjectHit, SubscriptionPreview } from '@/types';
import { notifySourcesChanged } from '@/lib/sourceEvents';
import { IconCheck, IconPlus, IconRefresh, IconSearch, IconTrash, IconX } from '@/components/Icons';

export default function SourcesPage() {
  const [sources, setSources] = useState<StoredSource[]>([]);
  const [health, setHealth] = useState<Record<string, SourceHealth>>({});
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [testing, setTesting] = useState<string | null>(null);
  const [testKeyword, setTestKeyword] = useState(' ');
  const [testResults, setTestResults] = useState<Record<string, SubjectHit[]>>({});

  // 订阅导入
  const [subUrl, setSubUrl] = useState('');
  const [subText, setSubText] = useState('');
  const [preview, setPreview] = useState<SubscriptionPreview | null>(null);
  const [previewInput, setPreviewInput] = useState<{ url?: string; text?: string }>({});
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [rawJson, setRawJson] = useState('');

  const reload = useCallback(async () => {
    try {
      const [list, h] = await Promise.all([api.sources(), api.selectorHealth().catch(() => ({}))]);
      setSources(list);
      setHealth(h);
    } catch (e) {
      setError(errMsg(e));
    }
  }, []);

  useEffect(() => { void reload(); }, [reload]);

  const run = async (action: () => Promise<unknown>, message?: string) => {
    setError('');
    setNotice('');
    try {
      await action();
      if (message) setNotice(message);
      await reload();
      notifySourcesChanged();
    } catch (e) {
      setError(errMsg(e));
    }
  };

  const doPreview = async () => {
    if (!subUrl.trim() && !subText.trim()) return;
    setBusy(true);
    setError('');
    try {
      const input = subText.trim() ? { text: subText.trim() } : { url: subUrl.trim() };
      const result = await api.previewSubscription(input);
      setPreview(result);
      setPreviewInput(input);
      setSelected(new Set(result.candidates.map((c) => c.name)));
    } catch (e) {
      setError(errMsg(e));
      setPreview(null);
    } finally {
      setBusy(false);
    }
  };

  const doImport = async () => {
    if (!preview || !selected.size) return;
    await run(async () => {
      const result = await api.importSubscription({ ...previewInput, names: [...selected], fingerprint: preview.fingerprint });
      setNotice(`导入完成：新增 ${result.added.length}，更新 ${result.updated.length}，失败 ${result.failed.length}`);
      setPreview(null);
      setSubText('');
    });
  };

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-bold">数据源管理</h1>

      {error && <div className="glass-panel border-red-500/30 p-4 text-sm text-red-400">{error}</div>}
      {notice && <div className="glass-panel border-brand-500/30 p-4 text-sm text-brand-400">{notice}</div>}

      {/* === 已导入源 === */}
      <section className="space-y-3">
        {sources.length === 0 && <p className="text-sm text-gray-500">还没有数据源，通过下方订阅或 JSON 导入。</p>}
        {sources.map((s) => {
          const name = s.arguments.name;
          const h = health[name];
          return (
            <div key={name} className="glass-panel p-4">
              <div className="flex flex-wrap items-center gap-3">
                <div className="min-w-0 flex-1">
                  <p className="font-semibold">{name}
                    <span className={`ml-2 rounded-full px-2 py-0.5 text-xs ${s.enabled ? 'bg-brand-500/15 text-brand-400' : 'bg-gray-500/20 text-gray-400'}`}>
                      {s.enabled ? '已启用' : '已停用'}
                    </span>
                  </p>
                  <p className="mt-0.5 truncate text-xs text-gray-500">{s.arguments.description || s.arguments.searchConfig?.searchUrl}</p>
                  {h && (
                    <p className="mt-1 text-xs text-gray-500">
                      成功 {h.success} / 失败 {h.failure} · 平均 {h.avgMs}ms
                      {h.lastError && <span className="ml-2 text-red-400" title={h.lastError}>最近错误：{h.lastError}</span>}
                    </p>
                  )}
                </div>
                <div className="flex items-center gap-2">
                  <button type="button" className="btn-ghost !px-3 !py-1.5 text-xs"
                    onClick={() => { setTesting(testing === name ? null : name); setTestKeyword(''); }}>
                    <IconSearch /> 测试
                  </button>
                  <button type="button" className="btn-ghost !px-3 !py-1.5 text-xs"
                    onClick={() => void run(() => api.setSourceEnabled(name, !s.enabled), s.enabled ? `已停用 ${name}` : `已启用 ${name}`)}>
                    {s.enabled ? '停用' : '启用'}
                  </button>
                  <button type="button" aria-label={`删除 ${name}`}
                    className="rounded-full p-2 text-gray-500 transition hover:bg-red-500/10 hover:text-red-400"
                    onClick={() => {
                      if (window.confirm(`确定删除数据源「${name}」？`)) {
                        void run(() => api.deleteSource(name), `已删除 ${name}`);
                      }
                    }}>
                    <IconTrash />
                  </button>
                </div>
              </div>
              {testing === name && (
                <div className="mt-3 border-t border-white/10 pt-3">
                  <form className="flex gap-2" onSubmit={(e) => {
                    e.preventDefault();
                    if (!testKeyword.trim()) return;
                    void run(async () => {
                      const hits = await api.sourceSearch(name, testKeyword.trim());
                      setTestResults((prev) => ({ ...prev, [name]: hits }));
                    });
                  }}>
                    <input className="input-field" placeholder="输入关键词测试该源搜索" value={testKeyword}
                      onChange={(e) => setTestKeyword(e.target.value)} />
                    <button type="submit" className="btn-primary shrink-0">搜索</button>
                  </form>
                  {testResults[name] && (
                    <ul className="mt-2 space-y-1 text-xs text-gray-400">
                      {testResults[name].length === 0 && <li>没有结果</li>}
                      {testResults[name].slice(0, 5).map((hit) => (
                        <li key={hit.url} className="truncate">· {hit.name}</li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </section>

      {/* === 订阅导入 === */}
      <section className="glass-panel space-y-4 p-5">
        <h2 className="flex items-center gap-2 font-semibold"><IconRefresh /> 聚合订阅导入</h2>
        <div className="flex flex-col gap-2 sm:flex-row">
          <input className="input-field" placeholder="订阅链接（https://…）" value={subUrl}
            onChange={(e) => setSubUrl(e.target.value)} />
          <button type="button" className="btn-primary shrink-0" disabled={busy || (!subUrl.trim() && !subText.trim())}
            onClick={() => void doPreview()}>
            {busy ? '拉取中…' : '预览'}
          </button>
        </div>
        <details>
          <summary className="cursor-pointer text-xs text-gray-500">或粘贴订阅 JSON 文本</summary>
          <textarea className="input-field mt-2 h-28 font-mono text-xs" value={subText}
            onChange={(e) => setSubText(e.target.value)} placeholder='{"mediaSources":[…]} 或单个源配置' />
        </details>

        {preview && (
          <div className="space-y-3 rounded-xl border border-white/10 bg-black/20 p-4">
            <p className="text-sm text-gray-400">{preview.subscription}：{preview.candidates.length} 个可导入源</p>
            <div className="max-h-64 space-y-1 overflow-y-auto thin-scrollbar">
              {preview.candidates.map((c) => (
                <label key={c.name} className="flex cursor-pointer items-center gap-3 rounded-lg px-2 py-1.5 text-sm hover:bg-white/5">
                  <input type="checkbox" checked={selected.has(c.name)} className="accent-brand-500"
                    onChange={() => setSelected((prev) => {
                      const next = new Set(prev);
                      if (next.has(c.name)) next.delete(c.name);
                      else next.add(c.name);
                      return next;
                    })} />
                  <span className="flex-1">{c.name}</span>
                  {c.exists && <span className="text-xs text-amber-400">将覆盖</span>}
                  <span className="max-w-[40%] truncate text-xs text-gray-500">{c.site}</span>
                </label>
              ))}
            </div>
            {preview.rejected.length > 0 && (
              <details className="text-xs text-amber-300">
                <summary className="cursor-pointer">{preview.rejected.length} 项被拒绝</summary>
                <ul className="mt-1 space-y-0.5">{preview.rejected.map((r, i) => <li key={i}>{r}</li>)}</ul>
              </details>
            )}
            <div className="flex gap-2">
              <button type="button" className="btn-primary" disabled={!selected.size} onClick={() => void doImport()}>
                <IconCheck /> 导入所选（{selected.size}）
              </button>
              <button type="button" className="btn-ghost" onClick={() => setPreview(null)}><IconX /> 取消</button>
            </div>
          </div>
        )}
      </section>

      {/* === 单源 JSON 导入 === */}
      <section className="glass-panel space-y-3 p-5">
        <h2 className="flex items-center gap-2 font-semibold"><IconPlus /> 单个数据源 JSON 导入</h2>
        <textarea className="input-field h-32 font-mono text-xs" value={rawJson} onChange={(e) => setRawJson(e.target.value)}
          placeholder='{"factoryId":"web-selector","version":2,"arguments":{"name":"…","searchConfig":{…}}}' />
        <button type="button" className="btn-primary" disabled={!rawJson.trim()}
          onClick={() => void run(async () => {
            await api.importSource(rawJson.trim());
            setRawJson('');
          }, '数据源已保存')}>
          保存数据源
        </button>
      </section>
    </div>
  );
}

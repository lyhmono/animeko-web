'use client';

// 弹幕面板：弹弹 play 搜索 → 分集 → 加载；本地 XML 导入；显示与过滤设置。
import { useEffect, useState } from 'react';
import { api, errMsg } from '@/api';
import {
  convertComments, getDanmakuSettings, parseLocalDanmakuXml, saveDanmakuSettings,
  type ArtDanmaku, type DanmakuSettings,
} from '@/lib/player/danmakuSettings';
import { IconCheck, IconDanmaku, IconPlus, IconSearch, IconTrash } from '@/components/Icons';

interface DanmakuAnime {
  animeId: number;
  animeTitle: string;
  episodeCount?: number;
  typeDescription?: string;
}

interface DanmakuEpisode {
  episodeId: number;
  episodeTitle: string;
}

interface DanmakuPanelProps {
  animeTitle: string;
  currentEp?: string;
  onLoad: (list: ArtDanmaku[], label: string) => void;
  onSettingsChange: (settings: DanmakuSettings) => void;
}

export function DanmakuPanel({ animeTitle, currentEp, onLoad, onSettingsChange }: DanmakuPanelProps) {
  const [keyword, setKeyword] = useState(animeTitle);
  const [animes, setAnimes] = useState<DanmakuAnime[]>([]);
  const [selectedAnime, setSelectedAnime] = useState<DanmakuAnime | null>(null);
  const [episodes, setEpisodes] = useState<DanmakuEpisode[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [loadedLabel, setLoadedLabel] = useState('');
  const [settings, setSettings] = useState<DanmakuSettings>(getDanmakuSettings());
  const [showSettings, setShowSettings] = useState(false);
  const [newRule, setNewRule] = useState('');

  useEffect(() => setKeyword(animeTitle), [animeTitle]);

  const search = async () => {
    if (!keyword.trim()) return;
    setLoading(true);
    setError('');
    setAnimes([]);
    setSelectedAnime(null);
    setEpisodes([]);
    try {
      const result = await fetchDanmakuSearch(keyword.trim());
      setAnimes(result);
      if (!result.length) setError('弹幕库中没有匹配动画，可尝试上传本地 XML 弹幕');
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setLoading(false);
    }
  };

  const pickAnime = async (anime: DanmakuAnime) => {
    setSelectedAnime(anime);
    setLoading(true);
    setError('');
    try {
      const response = await fetch(`/api/danmaku/episodes?animeId=${anime.animeId}`);
      const data = await response.json();
      if (!response.ok) throw new Error(data?.error ?? '分集加载失败');
      const list: DanmakuEpisode[] = data?.bangumi?.episodes ?? data?.episodes ?? [];
      setEpisodes(list);
      // 有当前集号时自动匹配分集标题
      if (currentEp) {
        const matched = list.find((e) => e.episodeTitle?.includes(`第${currentEp}话`) || e.episodeTitle?.includes(`第 ${currentEp} 话`)
          || new RegExp(`(^|\\D)0*${Number(currentEp)}(\\D|$)`).test(e.episodeTitle ?? ''));
        if (matched) await loadComments(matched, anime);
      }
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setLoading(false);
    }
  };

  const loadComments = async (episode: DanmakuEpisode, anime = selectedAnime) => {
    setLoading(true);
    setError('');
    try {
      const response = await fetch(`/api/danmaku/comment?episodeId=${episode.episodeId}`);
      const data = await response.json();
      if (!response.ok) throw new Error(data?.error ?? '弹幕加载失败');
      const list = convertComments(data.comments ?? []);
      const label = `${anime?.animeTitle ?? ''} · ${episode.episodeTitle}（${list.length} 条）`;
      setLoadedLabel(label);
      onLoad(list, label);
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setLoading(false);
    }
  };

  const uploadXml = async (file: File) => {
    try {
      const list = convertComments(parseLocalDanmakuXml(await file.text()));
      if (!list.length) throw new Error('未解析到弹幕，请确认是 B 站 / 弹弹 play XML 格式');
      const label = `${file.name}（${list.length} 条）`;
      setLoadedLabel(label);
      onLoad(list, label);
    } catch (e) {
      setError(errMsg(e));
    }
  };

  const update = (patch: Partial<DanmakuSettings>) => {
    const next = saveDanmakuSettings(patch);
    setSettings(next);
    onSettingsChange(next);
  };

  return (
    <div className="flex h-full flex-col gap-3 text-sm">
      {/* 搜索 */}
      <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); void search(); }}>
        <input className="input-field !py-1.5 text-xs" value={keyword} aria-label="弹幕搜索关键词"
          onChange={(e) => setKeyword(e.target.value)} placeholder="搜索弹幕库动画" />
        <button type="submit" className="btn-primary shrink-0 !px-3 !py-1.5 text-xs" disabled={loading}>
          <IconSearch />
        </button>
      </form>

      {error && <p className="text-xs text-amber-300">{error}</p>}
      {loadedLabel && <p className="flex items-center gap-1 text-xs text-brand-400"><IconCheck /> {loadedLabel}</p>}
      {loading && <p className="text-xs text-gray-500">加载中…</p>}

      <div className="min-h-0 flex-1 space-y-2 overflow-y-auto thin-scrollbar pr-1">
        {!selectedAnime && animes.map((anime) => (
          <button key={anime.animeId} type="button" onClick={() => void pickAnime(anime)}
            className="w-full rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-left transition hover:border-brand-500/50">
            <p className="truncate text-xs font-semibold">{anime.animeTitle}</p>
            <p className="mt-0.5 text-[11px] text-gray-500">{anime.typeDescription ?? ''}{anime.episodeCount ? ` · ${anime.episodeCount} 集` : ''}</p>
          </button>
        ))}
        {selectedAnime && (
          <>
            <button type="button" className="text-xs text-gray-500 hover:text-brand-400"
              onClick={() => { setSelectedAnime(null); setEpisodes([]); }}>
              ← 重新选择动画
            </button>
            <p className="text-xs font-semibold text-gray-300">{selectedAnime.animeTitle}：选择分集</p>
            <div className="grid grid-cols-2 gap-1.5">
              {episodes.map((episode) => (
                <button key={episode.episodeId} type="button" onClick={() => void loadComments(episode)}
                  className="truncate rounded-lg border border-white/10 bg-white/5 px-2 py-1.5 text-left text-[11px] transition hover:border-brand-500/50"
                  title={episode.episodeTitle}>
                  {episode.episodeTitle}
                </button>
              ))}
            </div>
          </>
        )}
      </div>

      {/* 本地 XML */}
      <label className="btn-ghost shrink-0 cursor-pointer !px-3 !py-1.5 text-xs">
        上传本地 XML 弹幕
        <input type="file" accept=".xml,text/xml" className="hidden" onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) void uploadXml(file);
          e.target.value = '';
        }} />
      </label>

      {/* 设置 */}
      <div className="shrink-0 border-t border-white/10 pt-2">
        <button type="button" className="flex w-full items-center justify-between text-xs text-gray-400"
          onClick={() => setShowSettings((v) => !v)}>
          <span className="flex items-center gap-1"><IconDanmaku /> 弹幕设置</span>
          <span>{showSettings ? '收起' : '展开'}</span>
        </button>
        {showSettings && (
          <div className="mt-2 space-y-2 text-xs">
            <label className="flex items-center justify-between">
              显示弹幕
              <input type="checkbox" checked={settings.enabled} className="accent-brand-500"
                onChange={(e) => update({ enabled: e.target.checked })} />
            </label>
            <label className="flex items-center justify-between gap-2">
              不透明度
              <input type="range" min={0.1} max={1} step={0.05} value={settings.opacity} className="w-28 accent-brand-500"
                onChange={(e) => update({ opacity: Number(e.target.value) })} />
            </label>
            <label className="flex items-center justify-between gap-2">
              字号 {settings.fontSize}px
              <input type="range" min={14} max={40} value={settings.fontSize} className="w-28 accent-brand-500"
                onChange={(e) => update({ fontSize: Number(e.target.value) })} />
            </label>
            <label className="flex items-center justify-between gap-2">
              滚动 {settings.speed}s
              <input type="range" min={2} max={15} value={settings.speed} className="w-28 accent-brand-500"
                onChange={(e) => update({ speed: Number(e.target.value) })} />
            </label>
            <div className="border-t border-white/10 pt-2">
              <p className="mb-1 text-gray-400">屏蔽规则（{settings.filterRules.length}）</p>
              <form className="flex gap-1" onSubmit={(e) => {
                e.preventDefault();
                if (!newRule.trim()) return;
                update({ filterRules: [...settings.filterRules, { id: `${Date.now()}`, keyword: newRule.trim(), type: 'normal', enabled: true }] });
                setNewRule('');
              }}>
                <input className="input-field !py-1 text-xs" value={newRule} onChange={(e) => setNewRule(e.target.value)}
                  placeholder="添加屏蔽关键词" aria-label="屏蔽关键词" />
                <button type="submit" className="btn-ghost shrink-0 !px-2 !py-1"><IconPlus /></button>
              </form>
              <ul className="mt-1 space-y-1">
                {settings.filterRules.map((rule) => (
                  <li key={rule.id} className="flex items-center gap-2">
                    <input type="checkbox" checked={rule.enabled} className="accent-brand-500" aria-label="启用规则"
                      onChange={() => update({ filterRules: settings.filterRules.map((r) => r.id === rule.id ? { ...r, enabled: !r.enabled } : r) })} />
                    <span className="flex-1 truncate">{rule.keyword}</span>
                    <button type="button" aria-label="删除规则" className="text-gray-500 hover:text-red-400"
                      onClick={() => update({ filterRules: settings.filterRules.filter((r) => r.id !== rule.id) })}>
                      <IconTrash />
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

async function fetchDanmakuSearch(keyword: string): Promise<DanmakuAnime[]> {
  const response = await fetch(`/api/danmaku/search?keyword=${encodeURIComponent(keyword)}`);
  const data = await response.json();
  if (!response.ok) throw new Error(data?.error ?? '弹幕搜索失败');
  return data?.animes ?? [];
}

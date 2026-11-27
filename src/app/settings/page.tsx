'use client';

// 设置页：主题 / 播放 / 弹幕 / Bangumi 通道 / 备份恢复 / 运行指标。
import { useCallback, useEffect, useState } from 'react';
import { useTheme } from 'next-themes';
import { api, errMsg } from '@/api';
import type { BgmChannelInfo, BgmChannelKind, BgmProbeResult } from '@/types';
import {
  getDirectPlayback, getPlaybackSettings, savePlaybackSettings, setDirectPlayback,
  type PlaybackSettings,
} from '@/lib/prefs';
import { exportUserData, importUserData } from '@/lib/storage';
import { getDanmakuSettings, saveDanmakuSettings, type DanmakuSettings } from '@/lib/player/danmakuSettings';
import { useBoot } from '@/components/BootGate';
import { IconCheck, IconDownload, IconLogout, IconMoon, IconRefresh, IconSun } from '@/components/Icons';

export default function SettingsPage() {
  const { resolvedTheme, setTheme } = useTheme();
  const { logout } = useBoot();
  const [mounted, setMounted] = useState(false);
  const [playback, setPlayback] = useState<PlaybackSettings | null>(null);
  const [direct, setDirect] = useState(true);
  const [danmaku, setDanmaku] = useState<DanmakuSettings | null>(null);
  const [channel, setChannel] = useState<BgmChannelInfo | null>(null);
  const [customUrls, setCustomUrls] = useState('');
  const [probes, setProbes] = useState<BgmProbeResult[]>([]);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [metrics, setMetrics] = useState<{ requests: number; proxyRequests: number; uptimeSeconds: number } | null>(null);

  useEffect(() => {
    setMounted(true);
    setPlayback(getPlaybackSettings());
    setDirect(getDirectPlayback());
    setDanmaku(getDanmakuSettings());
    api.bangumiChannel().then((c) => {
      setChannel(c);
      setCustomUrls(c.customUrls.join('\n'));
    }).catch((e) => setError(errMsg(e)));
    api.metrics().then(setMetrics).catch(() => undefined);
  }, []);

  const flash = useCallback((message: string) => {
    setNotice(message);
    setTimeout(() => setNotice(''), 3000);
  }, []);

  const updatePlayback = (patch: Partial<PlaybackSettings>) => {
    setPlayback(savePlaybackSettings(patch));
    flash('播放设置已保存');
  };

  const updateDanmaku = (patch: Partial<DanmakuSettings>) => {
    setDanmaku(saveDanmakuSettings(patch));
  };

  const saveChannel = async (kind: BgmChannelKind) => {
    setError('');
    try {
      const urls = customUrls.split('\n').map((s) => s.trim()).filter(Boolean);
      const next = await api.setBangumiChannel(kind, kind === 'CUSTOM' ? urls : []);
      setChannel(next);
      flash('通道已保存');
    } catch (e) {
      setError(errMsg(e));
    }
  };

  const probe = async () => {
    setError('');
    setProbes([]);
    try {
      setProbes(await api.probeBangumiChannel());
    } catch (e) {
      setError(errMsg(e));
    }
  };

  const exportBackup = () => {
    const blob = new Blob([exportUserData()], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `animeko-backup-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 5000);
  };

  const importBackup = async (file: File) => {
    try {
      const summary = importUserData(await file.text());
      flash(`备份已合并：收藏 +${summary.favorites}，进度 +${summary.progress}，最近观看 +${summary.recent}`);
    } catch (e) {
      setError(errMsg(e));
    }
  };

  return (
    <div className="max-w-3xl space-y-6">
      <h1 className="text-xl font-bold">设置</h1>
      {notice && <div className="glass-panel border-brand-500/30 p-3 text-sm text-brand-400"><IconCheck className="mr-1 inline" />{notice}</div>}
      {error && <div className="glass-panel border-red-500/30 p-3 text-sm text-red-400">{error}</div>}

      {/* === 外观 === */}
      <section className="glass-panel space-y-3 p-5">
        <h2 className="font-semibold">外观</h2>
        <div className="flex items-center justify-between">
          <span className="text-sm text-gray-400">明暗主题</span>
          <div className="flex gap-2">
            <button type="button" className={`btn-ghost ${mounted && resolvedTheme === 'dark' ? '!border-brand-500/60 text-brand-400' : ''}`}
              onClick={() => setTheme('dark')}><IconMoon /> 深色</button>
            <button type="button" className={`btn-ghost ${mounted && resolvedTheme === 'light' ? '!border-brand-500/60 text-brand-400' : ''}`}
              onClick={() => setTheme('light')}><IconSun /> 浅色</button>
          </div>
        </div>
      </section>

      {/* === 播放 === */}
      {playback && (
        <section className="glass-panel space-y-4 p-5">
          <h2 className="font-semibold">播放</h2>
          <SettingRow label="自动连播下一集" description="当前集播完后 5 秒倒计时自动播放下一集">
            <Toggle checked={playback.autoNext} onChange={(v) => updatePlayback({ autoNext: v })} />
          </SettingRow>
          <SettingRow label="跳过片头" description="播放页提供一键跳过片头按钮（秒）">
            <NumberInput value={playback.opSkipSeconds} onChange={(v) => updatePlayback({ opSkipSeconds: v })} />
          </SettingRow>
          <SettingRow label="自动跳过片尾" description="接近结尾时自动跳到片尾，0 为关闭">
            <NumberInput value={playback.edSkipSeconds} onChange={(v) => updatePlayback({ edSkipSeconds: v })} />
          </SettingRow>
          <SettingRow label="优先直连播放" description="关闭后所有视频都走服务端代理（带 Referer/Cookie 的源必须代理）">
            <Toggle checked={direct} onChange={(v) => { setDirectPlayback(v); setDirect(v); flash('已保存'); }} />
          </SettingRow>
        </section>
      )}

      {/* === 弹幕 === */}
      {danmaku && (
        <section className="glass-panel space-y-4 p-5">
          <h2 className="font-semibold">弹幕</h2>
          <SettingRow label="默认显示弹幕" description="播放页加载弹幕后默认开启显示">
            <Toggle checked={danmaku.enabled} onChange={(v) => updateDanmaku({ enabled: v })} />
          </SettingRow>
          <SettingRow label={`不透明度 ${Math.round(danmaku.opacity * 100)}%`}>
            <input type="range" min={0.1} max={1} step={0.05} value={danmaku.opacity} aria-label="弹幕不透明度"
              onChange={(e) => updateDanmaku({ opacity: Number(e.target.value) })} className="w-40 accent-brand-500" />
          </SettingRow>
          <SettingRow label={`字号 ${danmaku.fontSize}px`}>
            <input type="range" min={14} max={40} step={1} value={danmaku.fontSize} aria-label="弹幕字号"
              onChange={(e) => updateDanmaku({ fontSize: Number(e.target.value) })} className="w-40 accent-brand-500" />
          </SettingRow>
          <SettingRow label={`滚动时长 ${danmaku.speed}s`} description="越小滚动越快">
            <input type="range" min={2} max={15} step={1} value={danmaku.speed} aria-label="弹幕滚动时长"
              onChange={(e) => updateDanmaku({ speed: Number(e.target.value) })} className="w-40 accent-brand-500" />
          </SettingRow>
          <p className="text-xs text-gray-500">弹幕服务由环境变量 ANIMEKO_DANMAKU_API_BASE 配置（弹弹 play /api/v2 协议），播放页也支持上传本地 XML 弹幕。</p>
        </section>
      )}

      {/* === Bangumi 通道 === */}
      <section className="glass-panel space-y-4 p-5">
        <h2 className="font-semibold">Bangumi 通道</h2>
        {channel && (
          <>
            <div className="flex flex-wrap gap-2">
              {(['AUTO', 'OFFICIAL', 'MIRROR', 'CUSTOM'] as BgmChannelKind[]).map((kind) => (
                <button key={kind} type="button" onClick={() => void saveChannel(kind)}
                  className={`rounded-full px-4 py-1.5 text-sm transition ${channel.channel === kind ? 'bg-brand-500 font-semibold text-white' : 'glass-chip hover:text-brand-400'}`}>
                  {{ AUTO: '自动', OFFICIAL: '官方', MIRROR: '镜像', CUSTOM: '自定义' }[kind]}
                </button>
              ))}
            </div>
            {channel.channel === 'CUSTOM' && (
              <div>
                <label htmlFor="custom-bases" className="mb-1 block text-xs text-gray-500">自定义 API 地址（每行一个，最多 8 个）</label>
                <textarea id="custom-bases" className="input-field h-20 font-mono text-xs" value={customUrls}
                  onChange={(e) => setCustomUrls(e.target.value)} />
                <button type="button" className="btn-primary mt-2" onClick={() => void saveChannel('CUSTOM')}>保存自定义通道</button>
              </div>
            )}
            <p className="text-xs text-gray-500">当前生效：{channel.effectiveBases.join('、') || '无'}；每日放送{channel.calendarSupported ? '可用' : '不可用（镜像不支持）'}。</p>
            <button type="button" className="btn-ghost" onClick={() => void probe()}><IconRefresh /> 探测全部通道</button>
            {probes.length > 0 && (
              <ul className="space-y-1 text-xs">
                {probes.map((p) => (
                  <li key={p.base} className="flex flex-wrap items-center gap-2">
                    <span className={p.ok ? 'text-brand-400' : 'text-red-400'}>{p.ok ? '●' : '○'}</span>
                    <span className="font-mono">{p.base}</span>
                    <span className="text-gray-500">{p.latencyMs}ms · 放送表{p.calendarOk ? '可用' : '不可用'} · {p.detail}</span>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </section>

      {/* === 备份与恢复 === */}
      <section className="glass-panel space-y-3 p-5">
        <h2 className="font-semibold">备份与恢复</h2>
        <p className="text-xs text-gray-500">导出收藏、观看进度、最近观看与搜索历史；导入按条目合并，不会覆盖已有数据。</p>
        <div className="flex flex-wrap gap-2">
          <button type="button" className="btn-primary" onClick={exportBackup}><IconDownload /> 导出备份</button>
          <label className="btn-ghost cursor-pointer">
            导入备份
            <input type="file" accept="application/json,.json" className="hidden" onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) void importBackup(file);
              e.target.value = '';
            }} />
          </label>
        </div>
      </section>

      {/* === 运行状态 === */}
      {metrics && (
        <section className="glass-panel p-5 text-sm text-gray-400">
          <h2 className="mb-2 font-semibold text-gray-200">运行状态</h2>
          请求数 {metrics.requests} · 代理请求 {metrics.proxyRequests} · 运行时长 {Math.floor(metrics.uptimeSeconds / 3600)}h{Math.floor((metrics.uptimeSeconds % 3600) / 60)}m
        </section>
      )}

      <button type="button" className="btn-ghost text-red-400" onClick={() => void logout()}><IconLogout /> 注销登录</button>
    </div>
  );
}

function SettingRow({ label, description, children }: { label: string; description?: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <div>
        <p className="text-sm">{label}</p>
        {description && <p className="mt-0.5 text-xs text-gray-500">{description}</p>}
      </div>
      {children}
    </div>
  );
}

function Toggle({ checked, onChange }: { checked: boolean; onChange: (value: boolean) => void }) {
  return (
    <button type="button" role="switch" aria-checked={checked} onClick={() => onChange(!checked)}
      className={`relative h-6 w-11 shrink-0 rounded-full transition ${checked ? 'bg-brand-500' : 'bg-gray-600'}`}>
      <span className={`absolute top-0.5 h-5 w-5 rounded-full bg-white transition-all ${checked ? 'left-[1.375rem]' : 'left-0.5'}`} />
    </button>
  );
}

function NumberInput({ value, onChange }: { value: number; onChange: (value: number) => void }) {
  return (
    <input type="number" min={0} max={300} value={value} aria-label="秒数"
      onChange={(e) => onChange(Math.min(300, Math.max(0, Math.round(Number(e.target.value) || 0))))}
      className="input-field !w-24 text-center" />
  );
}

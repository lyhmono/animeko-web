'use client';

// 播放页：视频解析、断点续播、自动连播、弹幕、外挂字幕、外部播放器与下载。
import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { api, errMsg } from '@/api';
import type { EpisodesResult, VideoSource } from '@/types';
import {
  addRecent, clearProgress, getProgress, setProgress, setSubjectProgress,
} from '@/lib/storage';
import { getDirectPlayback, getPlaybackSettings } from '@/lib/prefs';
import { buildPlayUrl, type PlayEpisode } from '@/lib/playUrl';
import ArtPlayer, { type ArtPlayerHandle } from '@/components/player/ArtPlayer';
import { DanmakuPanel } from '@/components/player/DanmakuPanel';
import { EXTERNAL_PLAYERS, openExternalPlayer } from '@/lib/player/externalPlayers';
import { downloadVideo, type DownloadProgress } from '@/lib/player/m3u8Downloader';
import { saveDanmakuSettings, type ArtDanmaku, type DanmakuSettings } from '@/lib/player/danmakuSettings';
import {
  IconCheck, IconDanmaku, IconDownload, IconExternal, IconLink, IconPlay, IconRefresh, IconSubtitle, IconX,
} from '@/components/Icons';

function parseNextEpisode(raw: string | null): PlayEpisode | null {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as PlayEpisode;
    if (parsed && parsed.source && parsed.play) return parsed;
  } catch {
    // 非法参数忽略
  }
  return null;
}

function PlayerInner() {
  const router = useRouter();
  const params = useSearchParams();
  const source = params.get('source') ?? '';
  const play = params.get('play') ?? '';
  const title = params.get('title') ?? '视频播放';
  const anime = params.get('anime') ?? '';
  const ep = params.get('ep') ?? '';
  const bep = params.get('bep') ?? '';
  const subjectId = Number(params.get('subject') ?? 0) || 0;
  const surl = params.get('surl') ?? '';
  const nextFromUrl = useMemo(() => parseNextEpisode(params.get('next')), [params]);

  const [video, setVideo] = useState<VideoSource | null>(null);
  const [mode, setMode] = useState<'direct' | 'proxy'>(getDirectPlayback() ? 'direct' : 'proxy');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [startAt, setStartAt] = useState(0);
  const [episodes, setEpisodes] = useState<EpisodesResult | null>(null);
  const [channel, setChannel] = useState(0);
  const [panelTab, setPanelTab] = useState<'episodes' | 'danmaku'>(surl ? 'episodes' : 'danmaku');
  const [danmakuCount, setDanmakuCount] = useState(0);
  const [danmakuOn, setDanmakuOn] = useState(true);
  const [showExternal, setShowExternal] = useState(false);
  const [download, setDownload] = useState<DownloadProgress | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [countdown, setCountdown] = useState<number | null>(null);

  const playerRef = useRef<ArtPlayerHandle>(null);
  const lastWriteRef = useRef(0);
  const currentTimeRef = useRef(0);
  const fallbackRef = useRef(false);
  const edSkippedRef = useRef(false);
  const playbackSettings = useRef(getPlaybackSettings());

  const src = video ? (mode === 'direct' ? video.url : video.proxyPath) : '';

  const resolve = useCallback(async () => {
    if (!source || !play) {
      setError('缺少播放参数（source / play）');
      return;
    }
    setError('');
    try {
      const result = await api.resolveVideo(source, play);
      setVideo(result);
      const resume = getProgress(source, play);
      setStartAt(resume && resume.time > 5 ? resume.time : 0);
    } catch (e) {
      setError(errMsg(e));
    }
  }, [source, play]);

  useEffect(() => {
    fallbackRef.current = false;
    edSkippedRef.current = false;
    lastWriteRef.current = 0;
    setVideo(null);
    setCountdown(null);
    void resolve();
  }, [resolve]);

  // 数据源剧集（选集面板 + 连播链）
  useEffect(() => {
    if (!surl || !source) return;
    api.sourceEpisodes(source, surl).then(setEpisodes).catch(() => setEpisodes(null));
  }, [source, surl]);

  const currentGroup = useMemo(() => {
    if (!episodes) return null;
    if (episodes.groups.length) {
      const found = episodes.groups.find((g) => g.episodes.some((e) => e.playUrl === play));
      return found ?? episodes.groups[channel] ?? episodes.groups[0];
    }
    return { name: '', episodes: episodes.episodes };
  }, [episodes, play, channel]);

  const nextEpisode = useMemo((): PlayEpisode | null => {
    if (nextFromUrl) return nextFromUrl;
    if (!currentGroup) return null;
    const index = currentGroup.episodes.findIndex((e) => e.playUrl === play);
    const next = currentGroup.episodes[index + 1];
    if (index < 0 || !next) return null;
    return {
      source,
      play: next.playUrl,
      title: anime ? `${anime} ${next.name}` : next.name,
      anime,
      ep: next.sort,
      ...(subjectId && next.sortNum != null ? { bep: String(next.sortNum) } : {}),
    };
  }, [nextFromUrl, currentGroup, play, source, anime, subjectId]);

  const goNext = useCallback(() => {
    if (!nextEpisode) return;
    router.push(buildPlayUrl(nextEpisode, { subjectId, subjectUrl: surl }));
  }, [nextEpisode, router, subjectId, surl]);

  // 自动连播倒计时
  useEffect(() => {
    if (countdown == null) return;
    if (countdown <= 0) {
      goNext();
      return;
    }
    const timer = setTimeout(() => setCountdown((c) => (c == null ? null : c - 1)), 1000);
    return () => clearTimeout(timer);
  }, [countdown, goNext]);

  const handleEnded = useCallback(() => {
    clearProgress(source, play);
    if (playbackSettings.current.autoNext && nextEpisode) {
      setCountdown(5);
    }
  }, [source, play, nextEpisode]);

  const handleTimeUpdate = useCallback((time: number, duration: number) => {
    currentTimeRef.current = time;
    if (Math.abs(time - lastWriteRef.current) < 4) return;
    lastWriteRef.current = time;
    setProgress({ source, playUrl: play, title, time, duration, ts: Date.now() });
    if (subjectId && bep) {
      const epNum = Number(bep);
      if (epNum > 0) setSubjectProgress(subjectId, epNum);
    }
    // 片尾自动跳过：接近结尾时 seek 到结尾（每集一次），触发 ended 走连播
    const edSkip = playbackSettings.current.edSkipSeconds;
    if (edSkip > 0 && duration > edSkip && duration - time <= edSkip && !edSkippedRef.current) {
      edSkippedRef.current = true;
      playerRef.current?.seek(duration);
    }
  }, [source, play, title, subjectId, bep]);

  const handleReady = useCallback(() => {
    addRecent({ source, playUrl: play, title, anime, ep, ts: Date.now() });
  }, [source, play, title, anime, ep]);

  const handleFatal = useCallback((message: string) => {
    if (!fallbackRef.current) {
      fallbackRef.current = true;
      if (mode === 'direct') {
        setNotice('直连播放失败，已自动切换服务端代理');
        setMode('proxy');
        return;
      }
      setNotice('代理会话可能已过期，正在重新解析…');
      void resolve();
      return;
    }
    setError(message);
  }, [mode, resolve]);

  const onLoadDanmaku = useCallback((list: ArtDanmaku[]) => {
    setDanmakuCount(list.length);
    playerRef.current?.setDanmaku(list);
  }, []);

  const onDanmakuSettings = useCallback((settings: DanmakuSettings) => {
    setDanmakuOn(settings.enabled);
    playerRef.current?.updateDanmakuSettings(settings);
  }, []);

  const doDownload = async () => {
    if (!video || downloading) return;
    setDownloading(true);
    setDownload({ phase: 'playlist', total: 0, completed: 0, failed: 0 });
    try {
      await downloadVideo({
        url: mode === 'direct' ? video.url : video.proxyPath,
        filename: `${title}.ts`,
        onProgress: setDownload,
      });
      setNotice('下载完成');
    } catch (e) {
      setError(errMsg(e));
      setDownload(null);
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className="space-y-4">
      {/* 标题 */}
      <div>
        <h1 className="text-lg font-bold sm:text-xl">{anime || title}{ep && <span className="ml-2 text-brand-400">第 {ep} 集</span>}</h1>
        <p className="mt-0.5 text-xs text-gray-500">{source}{danmakuCount > 0 ? ` · 弹幕 ${danmakuCount} 条` : ''}{startAt > 0 ? ` · 已从 ${Math.floor(startAt / 60)} 分 ${Math.floor(startAt % 60)} 秒续播` : ''}</p>
      </div>

      <div className="grid gap-4 lg:grid-cols-4">
        {/* 播放器 */}
        <div className="lg:col-span-3">
          <div className="relative aspect-video overflow-hidden rounded-xl bg-black">
            {src ? (
              <ArtPlayer key={`${play}:${mode}`} ref={playerRef} src={src} title={title} startAt={startAt}
                rateKey={anime || title} onTimeUpdate={handleTimeUpdate} onEnded={handleEnded}
                onReady={handleReady} onFatalError={handleFatal} />
            ) : error ? (
              <div className="flex h-full flex-col items-center justify-center gap-3 p-6 text-center">
                <p className="text-sm text-red-400">{error}</p>
                <button type="button" className="btn-primary" onClick={() => void resolve()}><IconRefresh /> 重新解析</button>
              </div>
            ) : (
              <div className="flex h-full flex-col items-center justify-center gap-3">
                <div className="h-10 w-10 animate-spin rounded-full border-2 border-brand-500 border-t-transparent" />
                <p className="text-sm text-gray-400">正在解析播放地址…</p>
              </div>
            )}
            {/* 连播倒计时 */}
            {countdown != null && nextEpisode && (
              <div className="absolute inset-0 z-50 flex flex-col items-center justify-center gap-4 bg-black/80">
                <p className="text-sm text-gray-300">即将自动播放下一集</p>
                <p className="max-w-md truncate px-4 text-lg font-semibold">{nextEpisode.title}</p>
                <div className="text-4xl font-bold text-brand-500">{countdown}</div>
                <div className="flex gap-3">
                  <button type="button" className="btn-primary" onClick={goNext}><IconPlay /> 立即播放</button>
                  <button type="button" className="btn-ghost" onClick={() => setCountdown(null)}><IconX /> 取消连播</button>
                </div>
              </div>
            )}
          </div>

          {/* 工具栏 */}
          <div className="mt-3 flex flex-wrap items-center gap-2">
            {playbackSettings.current.opSkipSeconds > 0 && (
              <button type="button" className="btn-ghost !px-3 !py-1.5 text-xs"
                onClick={() => playerRef.current?.seek(currentTimeRef.current + playbackSettings.current.opSkipSeconds)}>
                跳过片头（{playbackSettings.current.opSkipSeconds}s）
              </button>
            )}
            <button type="button" className={`btn-ghost !px-3 !py-1.5 text-xs ${danmakuOn ? 'text-brand-400' : ''}`}
              onClick={() => {
                const next = !danmakuOn;
                setDanmakuOn(next);
                saveDanmakuSettings({ enabled: next });
                playerRef.current?.setDanmakuVisible(next);
              }}>
              <IconDanmaku /> 弹幕{danmakuOn ? '开' : '关'}
            </button>
            <label className="btn-ghost cursor-pointer !px-3 !py-1.5 text-xs">
              <IconSubtitle /> 外挂字幕
              <input type="file" accept=".srt,.vtt,.ass,.ssa,.sbv,.lrc" className="hidden" onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) {
                  playerRef.current?.loadSubtitle(file)
                    .then((message) => setNotice(message))
                    .catch((err) => setError(errMsg(err)));
                }
                e.target.value = '';
              }} />
            </label>
            <div className="relative">
              <button type="button" className="btn-ghost !px-3 !py-1.5 text-xs" disabled={!video}
                onClick={() => setShowExternal((v) => !v)}>
                <IconExternal /> 外部播放器
              </button>
              {showExternal && video && (
                <div className="absolute bottom-full left-0 z-50 mb-2 w-40 rounded-xl border border-white/10 bg-gray-900/95 p-1 shadow-2xl backdrop-blur-xl">
                  {EXTERNAL_PLAYERS.map((p) => (
                    <button key={p.id} type="button"
                      className="w-full rounded-lg px-3 py-2 text-left text-xs transition hover:bg-brand-500/20 hover:text-brand-400"
                      onClick={() => {
                        openExternalPlayer(p.id, video.url, title);
                        setShowExternal(false);
                      }}>
                      {p.name}
                    </button>
                  ))}
                </div>
              )}
            </div>
            <button type="button" className="btn-ghost !px-3 !py-1.5 text-xs" disabled={!video || downloading} onClick={() => void doDownload()}>
              <IconDownload /> {downloading ? '下载中…' : '下载'}
            </button>
            <button type="button" className="btn-ghost !px-3 !py-1.5 text-xs" disabled={!video}
              onClick={() => setMode((m) => m === 'direct' ? 'proxy' : 'direct')}>
              {mode === 'direct' ? '直连播放' : '代理播放'}
            </button>
            <button type="button" className="btn-ghost !px-3 !py-1.5 text-xs" disabled={!video}
              onClick={() => {
                if (video) {
                  void navigator.clipboard?.writeText(video.url).then(() => setNotice('视频地址已复制'));
                }
              }}>
              <IconLink /> 复制链接
            </button>
          </div>

          {/* 下载进度 */}
          {download && (
            <div className="glass-panel mt-3 p-3 text-xs">
              <div className="mb-1 flex justify-between text-gray-400">
                <span>{download.phase === 'playlist' ? '解析清单' : download.phase === 'segments' ? '下载分片' : download.phase === 'merging' ? '合并文件' : '完成'}</span>
                <span>{download.completed}/{download.total}</span>
              </div>
              <div className="h-1.5 overflow-hidden rounded-full bg-white/10">
                <div className="h-full bg-brand-500 transition-all"
                  style={{ width: download.total ? `${(download.completed / download.total) * 100}%` : '5%' }} />
              </div>
              {download.phase === 'done' && <p className="mt-1 flex items-center gap-1 text-brand-400"><IconCheck /> 已保存为 TS 文件</p>}
            </div>
          )}
          {notice && <p className="mt-2 text-xs text-brand-400">{notice}</p>}
          {error && src && <p className="mt-2 text-xs text-red-400">{error}</p>}
        </div>

        {/* 右侧选集 / 弹幕面板 */}
        <aside className="glass-panel flex max-h-[540px] flex-col p-3 lg:max-h-none">
          <div className="mb-3 flex gap-1 rounded-full border border-white/10 bg-black/20 p-1">
            {(['episodes', 'danmaku'] as const).map((tab) => (
              <button key={tab} type="button" onClick={() => setPanelTab(tab)}
                className={`flex-1 rounded-full px-3 py-1 text-xs transition ${panelTab === tab ? 'bg-brand-500 text-white' : 'text-gray-400'}`}>
                {tab === 'episodes' ? '选集' : '弹幕'}
              </button>
            ))}
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto thin-scrollbar">
            {panelTab === 'episodes' ? (
              episodes ? (
                <div className="space-y-3">
                  {episodes.groups.length > 1 && (
                    <div className="flex flex-wrap gap-1">
                      {episodes.groups.map((g, i) => (
                        <button key={g.name} type="button" onClick={() => setChannel(i)}
                          className={`rounded-full px-2.5 py-1 text-[11px] ${currentGroup?.name === g.name ? 'bg-brand-500 text-white' : 'glass-chip'}`}>
                          {g.name}
                        </button>
                      ))}
                    </div>
                  )}
                  <div className="grid grid-cols-4 gap-1.5">
                    {(currentGroup?.episodes ?? []).map((episode) => {
                      const active = episode.playUrl === play;
                      const target: PlayEpisode = {
                        source,
                        play: episode.playUrl,
                        title: anime ? `${anime} ${episode.name}` : episode.name,
                        anime,
                        ep: episode.sort,
                        ...(subjectId && episode.sortNum != null ? { bep: String(episode.sortNum) } : {}),
                      };
                      return (
                        <button key={episode.playUrl} type="button" title={episode.name}
                          onClick={() => router.push(buildPlayUrl(target, { subjectId, subjectUrl: surl }))}
                          className={`rounded-lg border px-1 py-2 text-xs transition ${active ? 'border-brand-500 bg-brand-500/20 font-semibold text-brand-400' : 'border-white/10 bg-white/5 text-gray-300 hover:border-brand-500/50'}`}>
                          {episode.sort || episode.name}
                        </button>
                      );
                    })}
                  </div>
                  {nextEpisode && (
                    <button type="button" className="btn-primary w-full !py-1.5 text-xs" onClick={goNext}>
                      <IconPlay /> 下一集：{nextEpisode.ep || nextEpisode.title}
                    </button>
                  )}
                </div>
              ) : (
                <p className="py-8 text-center text-xs text-gray-500">
                  {surl ? '剧集加载中或不可用' : '从数据源条目进入可使用选集面板'}
                </p>
              )
            ) : (
              <DanmakuPanel animeTitle={anime || title} currentEp={bep || ep}
                onLoad={onLoadDanmaku} onSettingsChange={onDanmakuSettings} />
            )}
          </div>
        </aside>
      </div>
    </div>
  );
}

export default function PlayerPage() {
  return (
    <Suspense fallback={<div className="flex justify-center py-24"><div className="h-8 w-8 animate-spin rounded-full border-2 border-brand-500 border-t-transparent" /></div>}>
      <PlayerInner />
    </Suspense>
  );
}

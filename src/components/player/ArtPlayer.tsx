'use client';

// ArtPlayer 封装：HLS.js、弹幕插件、外挂字幕（VTT 转换 + JASSUB 渲染 ASS）、偏好持久化。
import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';
import { getPlayerPrefs, savePlayerPrefs } from '@/lib/prefs';
import {
  buildDanmakuFilter, getDanmakuSettings, type ArtDanmaku, type DanmakuSettings,
} from '@/lib/player/danmakuSettings';
import { convertToVtt, detectSubtitleKind } from '@/lib/player/subtitleConverter';

export interface ArtPlayerHandle {
  loadSubtitle: (file: File) => Promise<string>;
  setDanmaku: (list: ArtDanmaku[]) => void;
  setDanmakuVisible: (visible: boolean) => void;
  updateDanmakuSettings: (settings: DanmakuSettings) => void;
  seek: (time: number) => void;
  play: () => void;
}

interface ArtPlayerProps {
  src: string;
  title: string;
  startAt?: number;
  rateKey?: string;
  onTimeUpdate?: (time: number, duration: number) => void;
  onEnded?: () => void;
  onReady?: () => void;
  onFatalError?: (message: string) => void;
}

export function isHlsSource(url: string): boolean {
  let target = url;
  if (url.includes('/api/proxy?')) {
    try {
      const inner = new URLSearchParams(url.slice(url.indexOf('?') + 1)).get('url');
      if (inner) target = inner;
    } catch {
      // 保留原串
    }
  }
  return /\.m3u8($|\?)/i.test(target) || /mpegurl/i.test(url);
}

const RATE_STEPS = [0.5, 0.75, 1, 1.25, 1.5, 2];

const ArtPlayer = forwardRef<ArtPlayerHandle, ArtPlayerProps>(function ArtPlayer(
  { src, title, startAt = 0, rateKey, onTimeUpdate, onEnded, onReady, onFatalError },
  ref,
) {
  const containerRef = useRef<HTMLDivElement>(null);
  const artRef = useRef<any>(null);
  const danmakuSettingsRef = useRef<DanmakuSettings>(getDanmakuSettings());
  const subtitleUrlsRef = useRef<string[]>([]);
  const callbacksRef = useRef({ onTimeUpdate, onEnded, onReady, onFatalError });
  callbacksRef.current = { onTimeUpdate, onEnded, onReady, onFatalError };

  useImperativeHandle(ref, () => ({
    async loadSubtitle(file: File) {
      const art = artRef.current;
      if (!art) throw new Error('播放器尚未就绪');
      const kind = detectSubtitleKind(file.name);
      if (kind === 'unknown') throw new Error('支持 SRT / VTT / SBV / LRC / ASS / SSA 字幕');
      const content = await file.text();

      if (kind === 'ass') {
        const { default: artplayerPluginJassub } = await import('artplayer-plugin-jassub');
        const existing = art.plugins?.artplayerPluginJassub?.instance;
        art.subtitle.show = false;
        if (existing) {
          const blobUrl = URL.createObjectURL(new Blob([content], { type: 'text/plain' }));
          subtitleUrlsRef.current.push(blobUrl);
          existing.setTrackByUrl(blobUrl);
        } else {
          art.plugins.add(artplayerPluginJassub({
            subContent: content,
            workerUrl: '/assets/jassub/jassub-worker.js',
            wasmUrl: '/assets/jassub/jassub-worker.wasm',
            modernWasmUrl: '/assets/jassub/jassub-worker-modern.wasm',
            availableFonts: {
              'noto sans cjk sc': '/assets/jassub/NotoSansCJK-Regular.ttc',
              'liberation sans': '/assets/jassub/default.woff2',
            },
            fallbackFont: 'noto sans cjk sc',
          }));
        }
        return `已加载 ASS 字幕：${file.name}`;
      }

      // 文本字幕统一转 VTT 后交给原生 track
      const vtt = convertToVtt(kind, content);
      if (!vtt) throw new Error('字幕转换失败');
      const existing = art.plugins?.artplayerPluginJassub?.instance;
      existing?.freeTrack?.();
      const blobUrl = URL.createObjectURL(new Blob([vtt], { type: 'text/vtt' }));
      subtitleUrlsRef.current.push(blobUrl);
      art.subtitle.switch(blobUrl, { name: file.name, type: 'vtt' });
      art.subtitle.show = true;
      return `已加载字幕：${file.name}`;
    },
    setDanmaku(list) {
      const plugin = artRef.current?.plugins?.artplayerPluginDanmuku;
      plugin?.load(list);
      if (danmakuSettingsRef.current.enabled) plugin?.show();
      else plugin?.hide();
    },
    setDanmakuVisible(visible) {
      danmakuSettingsRef.current = { ...danmakuSettingsRef.current, enabled: visible };
      const plugin = artRef.current?.plugins?.artplayerPluginDanmuku;
      if (!plugin) return;
      if (visible) plugin.show();
      else plugin.hide();
    },
    updateDanmakuSettings(settings) {
      danmakuSettingsRef.current = settings;
      const plugin = artRef.current?.plugins?.artplayerPluginDanmuku;
      plugin?.config({
        speed: settings.speed,
        opacity: settings.opacity,
        fontSize: settings.fontSize,
        margin: [settings.marginTop, settings.marginBottom],
        maxLength: settings.maxlength,
        synchronousPlayback: settings.synchronousPlayback,
        filter: (danmu: { text: string }) => buildDanmakuFilter(settings.filterRules)(danmu.text),
      });
      if (settings.enabled) plugin?.show();
      else plugin?.hide();
    },
    seek(time) {
      if (artRef.current) artRef.current.currentTime = time;
    },
    play() {
      void artRef.current?.play();
    },
  }), []);

  useEffect(() => {
    let destroyed = false;
    let art: any = null;

    const init = async () => {
      const [{ default: Artplayer }, { default: Hls }, { default: artplayerPluginDanmuku }] = await Promise.all([
        import('artplayer'),
        import('hls.js'),
        import('artplayer-plugin-danmuku'),
      ]);
      if (destroyed || !containerRef.current) return;

      const prefs = getPlayerPrefs(rateKey);
      const settings = danmakuSettingsRef.current;
      const filterFn = buildDanmakuFilter(settings.filterRules);
      const isHls = isHlsSource(src);

      art = new Artplayer({
        container: containerRef.current,
        url: src,
        type: isHls ? 'm3u8' : '',
        volume: prefs.volume,
        muted: prefs.muted,
        autoplay: true,
        moreVideoAttr: { preload: 'metadata' },
        theme: '#22c55e',
        lang: 'zh-cn',
        hotkey: true,
        pip: true,
        setting: true,
        playbackRate: true,
        aspectRatio: true,
        screenshot: true,
        fullscreen: true,
        fullscreenWeb: true,
        airplay: true,
        customType: {
          m3u8: (video: HTMLVideoElement & { hls?: any }, url: string) => {
            if (Hls.isSupported()) {
              if (video.hls) video.hls.destroy();
              const hls = new Hls({ enableWorker: true, fragLoadingTimeOut: 30000 });
              hls.loadSource(url);
              hls.attachMedia(video);
              video.hls = hls;
              hls.on(Hls.Events.ERROR, (_: unknown, data: any) => {
                if (!data.fatal) return;
                if (data.type === Hls.ErrorTypes.NETWORK_ERROR) hls.startLoad();
                else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) hls.recoverMediaError();
                else {
                  hls.destroy();
                  callbacksRef.current.onFatalError?.('视频加载失败，可尝试切换直连/代理或更换数据源');
                }
              });
            } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
              video.src = url;
            } else {
              callbacksRef.current.onFatalError?.('当前浏览器不支持 HLS 播放');
            }
          },
        },
        plugins: [
          artplayerPluginDanmuku({
            danmuku: [],
            speed: settings.speed,
            opacity: settings.opacity,
            fontSize: settings.fontSize,
            color: '#FFFFFF',
            mode: 0,
            margin: [settings.marginTop, settings.marginBottom],
            antiOverlap: true,
            synchronousPlayback: settings.synchronousPlayback,
            emitter: false,
            heatmap: false,
            maxLength: settings.maxlength,
            visible: settings.enabled,
            filter: (danmu: { text: string }) => filterFn(danmu.text),
          }),
        ],
      });
      artRef.current = art;

      art.on('ready', () => {
        if (startAt > 0) art.currentTime = startAt;
        const nearest = RATE_STEPS.reduce((a, b) => (Math.abs(b - prefs.rate) < Math.abs(a - prefs.rate) ? b : a), 1);
        art.playbackRate = nearest;
        callbacksRef.current.onReady?.();
      });
      art.on('video:timeupdate', () => {
        callbacksRef.current.onTimeUpdate?.(art.currentTime, art.duration || 0);
      });
      art.on('video:ended', () => callbacksRef.current.onEnded?.());
      art.on('video:volumechange', () => {
        savePlayerPrefs({ ...getPlayerPrefs(rateKey), volume: art.volume, muted: art.muted }, rateKey);
      });
      art.on('video:ratechange', () => {
        savePlayerPrefs({ ...getPlayerPrefs(rateKey), rate: art.playbackRate }, rateKey);
      });
      art.on('video:error', () => {
        callbacksRef.current.onFatalError?.('视频加载失败，可尝试切换直连/代理或更换数据源');
      });
      art.on('error', () => {
        callbacksRef.current.onFatalError?.('播放出错，可尝试切换直连/代理或更换数据源');
      });
    };

    void init();

    return () => {
      destroyed = true;
      subtitleUrlsRef.current.forEach((url) => URL.revokeObjectURL(url));
      subtitleUrlsRef.current = [];
      artRef.current = null;
      if (art) {
        try {
          art.destroy(false);
        } catch {
          // 忽略销毁异常
        }
      }
    };
    // src / startAt 变化时整体重建（换集由父级 key 控制）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [src]);

  return <div ref={containerRef} className="artplayer-app h-full w-full" />;
});

export default ArtPlayer;

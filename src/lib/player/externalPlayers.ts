// 外部播放器 URL scheme 统一构造。
export interface ExternalPlayer {
  id: string;
  name: string;
  build: (url: string, title: string) => string;
}

export const EXTERNAL_PLAYERS: ExternalPlayer[] = [
  { id: 'potplayer', name: 'PotPlayer', build: (url) => `potplayer://${url}` },
  { id: 'vlc', name: 'VLC', build: (url) => `vlc://${url}` },
  { id: 'mpv', name: 'MPV', build: (url) => `mpv://${url}` },
  {
    id: 'mxplayer',
    name: 'MX Player',
    build: (url, title) => `intent://${url}#Intent;package=com.mxtech.videoplayer.ad;S.title=${encodeURIComponent(title)};end`,
  },
  { id: 'nplayer', name: 'nPlayer', build: (url) => `nplayer-${url}` },
  { id: 'iina', name: 'IINA', build: (url) => `iina://weblink?url=${encodeURIComponent(url)}` },
];

export function openExternalPlayer(id: string, url: string, title: string): void {
  const player = EXTERNAL_PLAYERS.find((p) => p.id === id);
  if (!player || !url) return;
  window.open(player.build(url, title), '_blank', 'noopener');
}

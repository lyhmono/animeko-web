// 本地字幕格式识别与 WebVTT 转换；ASS/SSA 由 JASSUB 渲染，不在此转换。
export type SubtitleKind = 'srt' | 'vtt' | 'ass' | 'sbv' | 'lrc' | 'unknown';

export function detectSubtitleKind(filename: string): SubtitleKind {
  const ext = filename.toLowerCase().split('.').pop() ?? '';
  if (ext === 'srt') return 'srt';
  if (ext === 'vtt') return 'vtt';
  if (ext === 'ass' || ext === 'ssa') return 'ass';
  if (ext === 'sbv') return 'sbv';
  if (ext === 'lrc') return 'lrc';
  return 'unknown';
}

function normalizeTime(time: string): string {
  return time.trim().replace(',', '.');
}

/** SRT → WebVTT。 */
export function srtToVtt(text: string): string {
  const normalized = text.replace(/^﻿/, '').replace(/\r\n?/g, '\n');
  const body = normalized
    .split('\n')
    .map((line) => (line.includes('-->') ? line.replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, '$1.$2') : line))
    .join('\n');
  return `WEBVTT\n\n${body}`;
}

/** YouTube SBV → WebVTT。 */
export function sbvToVtt(text: string): string {
  const blocks = text.replace(/\r\n?/g, '\n').trim().split(/\n\n+/);
  const cues: string[] = [];
  for (const block of blocks) {
    const lines = block.split('\n');
    if (lines.length < 2) continue;
    const [start, end] = lines[0].split(',').map((t) => t.trim());
    if (!start || !end) continue;
    cues.push(`${normalizeClock(start)} --> ${normalizeClock(end)}\n${lines.slice(1).join('\n')}`);
  }
  return `WEBVTT\n\n${cues.join('\n\n')}`;
}

function normalizeClock(time: string): string {
  const t = time.trim().replace(',', '.');
  // SBV 标准格式 H:MM:SS.mmm（毫秒已存在，不得再追加）
  const m = /^(\d+):(\d{1,2}):(\d{1,2}(?:\.\d{1,3})?)$/.exec(t);
  if (m) {
    const sec = (m[3].includes('.') ? m[3] : `${m[3]}.000`).padStart(2, '0');
    return `${m[1].padStart(2, '0')}:${m[2].padStart(2, '0')}:${sec}`;
  }
  // 兼容 H:MM:SS:mmm 四段写法
  const parts = t.split(':');
  if (parts.length === 4) return `${parts[0].padStart(2, '0')}:${parts[1]}:${parts[2]}.${parts[3]}`;
  return t;
}

/** LRC 歌词 → WebVTT（每句 8 秒或到下一句）。 */
export function lrcToVtt(text: string): string {
  const entries: Array<{ start: number; text: string }> = [];
  for (const line of text.replace(/\r\n?/g, '\n').split('\n')) {
    const m = /^\[(\d+):(\d+(?:\.\d+)?)\](.*)$/.exec(line.trim());
    if (!m) continue;
    const start = Number(m[1]) * 60 + Number(m[2]);
    const content = m[3].trim();
    if (content) entries.push({ start, text: content });
  }
  const cues = entries.map((entry, i) => {
    const end = entries[i + 1]?.start ?? entry.start + 8;
    return `${formatVttTime(entry.start)} --> ${formatVttTime(end)}\n${entry.text}`;
  });
  return `WEBVTT\n\n${cues.join('\n\n')}`;
}

function formatVttTime(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${s.toFixed(3).padStart(6, '0')}`;
}

/** 文本字幕统一转 VTT；ASS 返回 null（走 JASSUB）。 */
export function convertToVtt(kind: SubtitleKind, content: string): string | null {
  switch (kind) {
    case 'vtt': return content.replace(/^﻿/, '');
    case 'srt': return srtToVtt(content);
    case 'sbv': return sbvToVtt(content);
    case 'lrc': return lrcToVtt(content);
    default: return null;
  }
}

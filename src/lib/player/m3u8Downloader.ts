// 浏览器内 m3u8 分片下载与合并（TS），支持 AES-128 解密、并发与取消。
export interface DownloadProgress {
  phase: 'playlist' | 'segments' | 'merging' | 'done';
  total: number;
  completed: number;
  failed: number;
}

export interface DownloadOptions {
  url: string;
  filename: string;
  concurrency?: number;
  signal?: AbortSignal;
  onProgress?: (progress: DownloadProgress) => void;
}

interface Segment {
  url: string;
  data?: Uint8Array;
  failed?: boolean;
}

function absolutize(link: string, base: string): string {
  return new URL(link, base).toString();
}

async function fetchText(url: string, signal?: AbortSignal): Promise<{ text: string; finalUrl: string }> {
  const response = await fetch(url, { signal });
  if (!response.ok) throw new Error(`清单请求失败：HTTP ${response.status}`);
  return { text: await response.text(), finalUrl: response.url || url };
}

/** 主清单（Master）选择最高带宽子清单。 */
function pickVariant(playlist: string, base: string): string | null {
  if (!playlist.includes('#EXT-X-STREAM-INF')) return null;
  const lines = playlist.split(/\r?\n/);
  let best: { bandwidth: number; url: string } | null = null;
  for (let i = 0; i < lines.length; i += 1) {
    if (lines[i].startsWith('#EXT-X-STREAM-INF')) {
      const bandwidth = Number(/BANDWIDTH=(\d+)/.exec(lines[i])?.[1] ?? 0);
      const uriLine = lines.slice(i + 1).find((l) => l.trim() && !l.startsWith('#'));
      if (uriLine && (!best || bandwidth > best.bandwidth)) {
        best = { bandwidth, url: absolutize(uriLine.trim(), base) };
      }
    }
  }
  return best?.url ?? null;
}

function parseSegments(playlist: string, base: string): { segments: Segment[]; key?: { url: string; iv?: Uint8Array }; mediaSequence: number } {
  const lines = playlist.split(/\r?\n/);
  const segments: Segment[] = [];
  let key: { url: string; iv?: Uint8Array } | undefined;
  let mediaSequence = 0;
  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed.startsWith('#EXT-X-MEDIA-SEQUENCE')) {
      mediaSequence = Number(trimmed.split(':')[1]) || 0;
    } else if (trimmed.startsWith('#EXT-X-KEY')) {
      const method = /METHOD=([^,]+)/.exec(trimmed)?.[1];
      const uri = /URI="([^"]+)"/.exec(trimmed)?.[1];
      if (method === 'AES-128' && uri) {
        const ivHex = /IV=0x([0-9a-fA-F]+)/.exec(trimmed)?.[1];
        key = {
          url: absolutize(uri, base),
          iv: ivHex ? hexToBytes(ivHex) : undefined,
        };
      } else if (method && method !== 'NONE') {
        throw new Error(`不支持的加密方式：${method}`);
      }
    } else if (trimmed && !trimmed.startsWith('#')) {
      segments.push({ url: absolutize(trimmed, base) });
    }
  }
  return { segments, key, mediaSequence };
}

function hexToBytes(hex: string): Uint8Array {
  const normalized = hex.length % 2 ? `0${hex}` : hex;
  const bytes = new Uint8Array(normalized.length / 2);
  for (let i = 0; i < bytes.length; i += 1) bytes[i] = parseInt(normalized.slice(i * 2, i * 2 + 2), 16);
  return bytes;
}

async function fetchBytes(url: string, signal?: AbortSignal, retries = 3): Promise<ArrayBuffer> {
  let lastError: unknown;
  for (let attempt = 0; attempt < retries; attempt += 1) {
    try {
      const response = await fetch(url, { signal });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return await response.arrayBuffer();
    } catch (err) {
      lastError = err;
      if (signal?.aborted) throw err;
      await new Promise((r) => setTimeout(r, 500 * (attempt + 1)));
    }
  }
  throw lastError instanceof Error ? lastError : new Error('分片下载失败');
}

/** 下载视频：m3u8 合并为 TS 文件，其他格式直接另存。 */
export async function downloadVideo(options: DownloadOptions): Promise<void> {
  const { url, filename, signal, onProgress } = options;
  const isHls = /m3u8|mpegurl/i.test(url) || url.includes('/api/proxy?');

  if (!isHls) {
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.target = '_blank';
    a.rel = 'noopener';
    a.click();
    return;
  }

  onProgress?.({ phase: 'playlist', total: 0, completed: 0, failed: 0 });
  let { text, finalUrl } = await fetchText(url, signal);
  if (!text.includes('#EXTM3U')) {
    // 并非清单（例如代理后的 mp4），退化为直接下载
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.target = '_blank';
    a.click();
    return;
  }
  const variant = pickVariant(text, finalUrl);
  if (variant) {
    const next = await fetchText(variant, signal);
    text = next.text;
    finalUrl = next.finalUrl;
  }
  const { segments, key, mediaSequence } = parseSegments(text, finalUrl);
  if (!segments.length) throw new Error('清单中没有分片');

  let keyBytes: ArrayBuffer | null = null;
  if (key) keyBytes = await fetchBytes(key.url, signal);

  const concurrency = Math.max(1, Math.min(8, options.concurrency ?? 6));
  let cursor = 0;
  let completed = 0;
  let failed = 0;
  onProgress?.({ phase: 'segments', total: segments.length, completed, failed });

  const workers = Array.from({ length: concurrency }, async () => {
    while (cursor < segments.length) {
      if (signal?.aborted) throw new DOMException('下载已取消', 'AbortError');
      const index = cursor;
      cursor += 1;
      const segment = segments[index];
      try {
        let data: Uint8Array = new Uint8Array(await fetchBytes(segment.url, signal));
        if (keyBytes) {
          const iv = key?.iv ?? new Uint8Array(16);
          if (!key?.iv) new DataView(iv.buffer).setUint32(12, mediaSequence + index, false);
          const cryptoKey = await crypto.subtle.importKey('raw', keyBytes, 'AES-CBC', false, ['decrypt']);
          const decrypted = await crypto.subtle.decrypt({ name: 'AES-CBC', iv: iv as BufferSource }, cryptoKey, data as BufferSource);
          data = new Uint8Array(decrypted);
        }
        segment.data = data;
      } catch (err) {
        if (signal?.aborted) throw err;
        segment.failed = true;
        failed += 1;
      }
      completed += 1;
      onProgress?.({ phase: 'segments', total: segments.length, completed, failed });
    }
  });
  await Promise.all(workers);

  if (failed > 0) throw new Error(`${failed} 个分片下载失败，请重试`);
  onProgress?.({ phase: 'merging', total: segments.length, completed, failed });
  const blob = new Blob(segments.map((s) => s.data!.slice().buffer as ArrayBuffer), { type: 'video/mp2t' });
  const objectUrl = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = objectUrl;
  a.download = filename.endsWith('.ts') ? filename : `${filename}.ts`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(objectUrl), 10000);
  onProgress?.({ phase: 'done', total: segments.length, completed, failed });
}

// 带超时 / 重定向逐跳 SSRF 校验 / 大小限制的上游请求工具。
import { requireAllowed } from './urlGuard';
import { config } from './config';

export interface GuardedFetchOptions {
  method?: string;
  headers?: Record<string, string>;
  body?: string | Uint8Array;
  timeoutMs?: number;
  maxRedirects?: number;
}

export interface GuardedResponse {
  response: Response;
  finalUrl: string;
}

export class UpstreamError extends Error {
  constructor(message: string, public readonly status = 502) {
    super(message);
    this.name = 'UpstreamError';
  }
}

/** 手动跟随重定向，每一跳都做 SSRF 校验。 */
export async function guardedFetch(rawUrl: string, options: GuardedFetchOptions = {}): Promise<GuardedResponse> {
  const maxRedirects = options.maxRedirects ?? 5;
  let current = rawUrl;
  let method = options.method ?? 'GET';
  let body = options.body;

  for (let hop = 0; hop <= maxRedirects; hop += 1) {
    const url = await requireAllowed(current);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), options.timeoutMs ?? config.requestTimeoutMs);
    let response: Response;
    try {
      response = await fetch(url, {
        method,
        headers: { 'accept-encoding': 'identity', ...options.headers },
        body: body as BodyInit | undefined,
        redirect: 'manual',
        signal: controller.signal,
        // Next.js 14 的服务端 fetch 默认进 Data Cache：上游抓取必须每次实拉，
        // 否则订阅定时刷新拿到旧内容、Bangumi 与数据源页面也被缓存。
        cache: 'no-store',
      });
    } catch (err) {
      clearTimeout(timer);
      const message = err instanceof Error && err.name === 'AbortError' ? '上游请求超时' : '上游请求失败';
      throw new UpstreamError(message);
    }
    clearTimeout(timer);

    if ([301, 302, 303, 307, 308].includes(response.status)) {
      const location = response.headers.get('location');
      await response.arrayBuffer().catch(() => undefined); // 释放连接
      if (!location) throw new UpstreamError('上游重定向缺少 Location');
      current = new URL(location, url).toString();
      if (response.status === 303) {
        method = 'GET';
        body = undefined;
      }
      continue;
    }
    return { response, finalUrl: url.toString() };
  }
  throw new UpstreamError('上游重定向次数过多');
}

/** 读取响应体并限制大小。 */
export async function readBodyLimited(response: Response, maxBytes: number): Promise<Buffer> {
  const buf = Buffer.from(await response.arrayBuffer());
  if (buf.byteLength > maxBytes) throw new UpstreamError('上游响应过大');
  return buf;
}

/** 按 BOM / Content-Type / HTML meta 识别编码并解码。 */
export function decodeText(buf: Buffer, contentType: string | null): string {
  if (buf.length >= 3 && buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf) {
    return buf.subarray(3).toString('utf8');
  }
  let charset = /charset=([\w-]+)/i.exec(contentType ?? '')?.[1]?.toLowerCase();
  if (!charset) {
    const head = buf.subarray(0, Math.min(buf.length, 4096)).toString('latin1');
    charset = /charset=["']?([\w-]+)/i.exec(head)?.[1]?.toLowerCase();
  }
  try {
    if (charset && charset !== 'utf-8' && charset !== 'utf8') {
      return new TextDecoder(charset).decode(buf);
    }
  } catch {
    // 未知编码回退 UTF-8
  }
  return buf.toString('utf8');
}

/** 抓取文本页面（默认 8 MiB 上限）。 */
export async function fetchText(url: string, options: GuardedFetchOptions & { maxBytes?: number } = {}) {
  const { response, finalUrl } = await guardedFetch(url, options);
  if (!response.ok) {
    await response.arrayBuffer().catch(() => undefined);
    throw new UpstreamError(`上游返回 ${response.status}`);
  }
  const buf = await readBodyLimited(response, options.maxBytes ?? 8 * 1024 * 1024);
  return { text: decodeText(buf, response.headers.get('content-type')), finalUrl, response };
}

import { NextRequest, NextResponse } from 'next/server';
import { errorJson, gate } from '@/lib/server/gate';
import { guardedFetch, readBodyLimited, decodeText, UpstreamError } from '@/lib/server/http';
import { metrics } from '@/lib/server/metrics';
import { config } from '@/lib/server/config';
import {
  authorizeProxyHost,
  buildUpstreamHeaders,
  ProxySessionError,
  resolveProxySession,
} from '@/lib/server/proxySessions';
import { SsrfError } from '@/lib/server/urlGuard';

export const dynamic = 'force-dynamic';
export const maxDuration = 300;

const HLS_URI_TAGS = new Set([
  'EXT-X-KEY', 'EXT-X-SESSION-KEY', 'EXT-X-MAP', 'EXT-X-MEDIA', 'EXT-X-I-FRAME-STREAM-INF',
  'EXT-X-PART', 'EXT-X-PRELOAD-HINT', 'EXT-X-RENDITION-REPORT', 'EXT-X-SESSION-DATA', 'EXT-X-DATERANGE',
]);

function rewriteHls(text: string, manifestUrl: string, sid: string | null): string {
  const wrap = (absolute: string): string => {
    authorizeProxyHost(sid, absolute);
    return `/api/proxy?url=${encodeURIComponent(absolute)}${sid ? `&sid=${sid}` : ''}`;
  };
  return text.replace(/^\uFEFF/, '').split(/\r?\n/).map((line) => {
    const trimmed = line.trim();
    if (!trimmed) return line;
    if (!trimmed.startsWith('#')) {
      try {
        return wrap(new URL(trimmed, manifestUrl).toString());
      } catch {
        return line;
      }
    }
    const tag = trimmed.slice(1).split(':')[0];
    if (!HLS_URI_TAGS.has(tag)) return line;
    return line.replace(/URI="([^"]+)"/g, (whole, uri: string) => {
      try {
        return `URI="${wrap(new URL(uri, manifestUrl).toString())}"`;
      } catch {
        return whole;
      }
    });
  }).join('\n');
}

export async function GET(req: NextRequest) {
  const blocked = gate(req);
  if (blocked) return blocked;
  const url = req.nextUrl.searchParams.get('url');
  const sid = req.nextUrl.searchParams.get('sid');
  if (!url || !/^https?:\/\//.test(url)) return errorJson(400, 'invalid url');
  metrics.proxyRequests += 1;

  try {
    const session = resolveProxySession(sid, url);
    const headers = buildUpstreamHeaders(session, url, req.headers.get('range'));
    const { response, finalUrl } = await guardedFetch(url, { headers, timeoutMs: config.proxyTimeoutMs });

    const contentType = response.headers.get('content-type') ?? '';
    const isHls = /mpegurl/i.test(contentType)
      || ((!contentType || /octet-stream|text\/plain/i.test(contentType)) && new URL(finalUrl).pathname.endsWith('.m3u8'));

    if (isHls && response.status === 200) {
      const buf = await readBodyLimited(response, 4 * 1024 * 1024);
      const text = rewriteHls(decodeText(buf, contentType), finalUrl, sid);
      return new NextResponse(text, {
        status: 200,
        headers: {
          'content-type': 'application/vnd.apple.mpegurl',
          'cache-control': 'no-store',
          'content-security-policy': 'sandbox',
        },
      });
    }

    const passthrough = new Headers();
    for (const key of ['content-type', 'content-length', 'content-range', 'accept-ranges', 'content-encoding']) {
      const value = response.headers.get(key);
      if (value) passthrough.set(key, value);
    }
    passthrough.set('content-security-policy', 'sandbox');
    if (/html|svg|javascript/i.test(contentType)) passthrough.set('x-content-type-options', 'nosniff');
    if (contentType.startsWith('image/') && response.status === 200) {
      passthrough.set('cache-control', 'public, max-age=86400');
    }
    return new NextResponse(response.body, { status: response.status, headers: passthrough });
  } catch (err) {
    if (err instanceof ProxySessionError) {
      return errorJson(err.code === 'PROXY_SESSION_EXPIRED' ? 410 : 403, err.code);
    }
    if (err instanceof SsrfError) return errorJson(403, err.message);
    if (err instanceof UpstreamError) return errorJson(err.status, err.message);
    console.error('[proxy]', err);
    return errorJson(500, '代理失败');
  }
}

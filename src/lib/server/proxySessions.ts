// 媒体代理会话：服务端保存请求头，浏览器只持有 sid。
import crypto from 'node:crypto';

export interface ProxySession {
  url: string;
  referer: string;
  userAgent: string;
  cookie: string;
  cookieHosts: string[];
  allowedHosts: Set<string>;
  expiresAt: number;
  secureOrigin: boolean;
}

const IDLE_TTL = 6 * 3600 * 1000;
const MAX_SESSIONS = 1024;
const MAX_HOSTS = 64;

const sessions = new Map<string, ProxySession>();

function sweep(): void {
  const now = Date.now();
  for (const [sid, s] of sessions) {
    if (s.expiresAt <= now) sessions.delete(sid);
  }
}

export function createProxySession(input: {
  url: string;
  referer?: string;
  userAgent?: string;
  cookie?: string;
  cookieHosts?: string[];
}): string | null {
  const headersSize = (input.referer?.length ?? 0) + (input.userAgent?.length ?? 0) + (input.cookie?.length ?? 0);
  if (headersSize === 0 || headersSize > 16 * 1024) return null;
  sweep();
  if (sessions.size >= MAX_SESSIONS) {
    const sorted = [...sessions.entries()].sort((a, b) => a[1].expiresAt - b[1].expiresAt);
    for (const [sid] of sorted.slice(0, Math.floor(MAX_SESSIONS / 2))) sessions.delete(sid);
  }
  const sid = crypto.randomBytes(16).toString('hex');
  const host = new URL(input.url).hostname.toLowerCase();
  sessions.set(sid, {
    url: input.url,
    referer: input.referer ?? '',
    userAgent: input.userAgent ?? '',
    cookie: input.cookie ?? '',
    cookieHosts: (input.cookieHosts ?? []).map((h) => h.toLowerCase()),
    allowedHosts: new Set([host]),
    expiresAt: Date.now() + IDLE_TTL,
    secureOrigin: input.url.startsWith('https:'),
  });
  return sid;
}

export class ProxySessionError extends Error {
  constructor(public readonly code: 'PROXY_SESSION_EXPIRED' | 'PROXY_SESSION_TARGET_MISMATCH') {
    super(code);
    this.name = 'ProxySessionError';
  }
}

export function resolveProxySession(sid: string | null, targetUrl: string): ProxySession | null {
  if (!sid) return null;
  const s = sessions.get(sid);
  if (!s || s.expiresAt <= Date.now()) {
    sessions.delete(sid);
    throw new ProxySessionError('PROXY_SESSION_EXPIRED');
  }
  const host = new URL(targetUrl).hostname.toLowerCase();
  if (!s.allowedHosts.has(host)) throw new ProxySessionError('PROXY_SESSION_TARGET_MISMATCH');
  s.expiresAt = Date.now() + IDLE_TTL;
  return s;
}

/** HLS 清单改写发现的新主机加入白名单。 */
export function authorizeProxyHost(sid: string | null, targetUrl: string): void {
  if (!sid) return;
  const s = sessions.get(sid);
  if (!s) return;
  try {
    const host = new URL(targetUrl).hostname.toLowerCase();
    if (!s.allowedHosts.has(host) && s.allowedHosts.size < MAX_HOSTS) s.allowedHosts.add(host);
  } catch {
    // 非法地址忽略
  }
}

export function buildUpstreamHeaders(session: ProxySession | null, targetUrl: string, range: string | null): Record<string, string> {
  const headers: Record<string, string> = { 'accept-encoding': 'identity' };
  if (range) headers.range = range;
  if (!session) return headers;
  const host = new URL(targetUrl).hostname.toLowerCase();
  if (session.userAgent) headers['user-agent'] = session.userAgent;
  if (session.referer) headers.referer = session.referer;
  const isMediaHost = host === new URL(session.url).hostname.toLowerCase();
  const cookieAllowed = isMediaHost || session.cookieHosts.includes(host);
  const downgrade = session.secureOrigin && targetUrl.startsWith('http:');
  if (session.cookie && cookieAllowed && !downgrade) headers.cookie = session.cookie;
  return headers;
}

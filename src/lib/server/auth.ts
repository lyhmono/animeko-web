// 会话、凭据校验与限流（进程内存态，单副本部署）。
import crypto from 'node:crypto';
import type { NextRequest } from 'next/server';
import { config } from './config';

interface Session {
  user: string;
  createdAt: number;
  lastSeen: number;
  cookieWrittenAt: number;
}

const SESSION_IDLE_TTL = 30 * 24 * 3600 * 1000;
const SESSION_ABSOLUTE_TTL = 90 * 24 * 3600 * 1000;
const MAX_SESSIONS = 1024;
const COOKIE_MAX_AGE = 30 * 24 * 3600;

const sessions = new Map<string, Session>();

export const sessionCookieName = config.cookieSecure ? '__Host-animeko_session' : 'animeko_session';

function sha256(input: string): Buffer {
  return crypto.createHash('sha256').update(input, 'utf8').digest();
}

function safeEqual(a: string, b: string): boolean {
  return crypto.timingSafeEqual(sha256(a), sha256(b));
}

export function createSession(user: string): string {
  const now = Date.now();
  for (const [token, s] of sessions) {
    if (now - s.lastSeen > SESSION_IDLE_TTL || now - s.createdAt > SESSION_ABSOLUTE_TTL) sessions.delete(token);
  }
  if (sessions.size >= MAX_SESSIONS) {
    const oldest = [...sessions.entries()].sort((a, b) => a[1].lastSeen - b[1].lastSeen)[0];
    if (oldest) sessions.delete(oldest[0]);
  }
  const token = crypto.randomBytes(32).toString('hex');
  sessions.set(token, { user, createdAt: now, lastSeen: now, cookieWrittenAt: now });
  return token;
}

export function touchSession(token: string): Session | null {
  const s = sessions.get(token);
  if (!s) return null;
  const now = Date.now();
  if (now - s.lastSeen > SESSION_IDLE_TTL || now - s.createdAt > SESSION_ABSOLUTE_TTL) {
    sessions.delete(token);
    return null;
  }
  s.lastSeen = now;
  return s;
}

export function destroySession(token: string): void {
  sessions.delete(token);
}

const COOKIE_RENEW_INTERVAL = 24 * 3600 * 1000;

/**
 * 会话有效且距上次浏览器 Cookie 写入超过 24 小时时返回 true 并标记本次重写。
 * 每个视频分片都 Set-Cookie 会撑大响应并破坏缓存，所以按天滑动而不是按请求。
 */
export function renewSessionCookieIfDue(token: string): boolean {
  const s = touchSession(token);
  if (!s) return false;
  const now = Date.now();
  if (now - s.cookieWrittenAt < COOKIE_RENEW_INTERVAL) return false;
  s.cookieWrittenAt = now;
  return true;
}

/** 校验登录页凭据，成功返回用户名。 */
export function verifyLogin(username: string, password: string): string | null {
  if (!config.authEnabled) return null;
  if (config.authUser && config.authPassword) {
    if (safeEqual(username, config.authUser) && safeEqual(password, config.authPassword)) return config.authUser;
    return null;
  }
  if (config.authToken && safeEqual(password, config.authToken)) return 'token';
  return null;
}

/** 从请求解析已认证用户（会话 Cookie / Bearer / Basic）。 */
export function authenticate(req: NextRequest): { user: string; viaPassword: boolean } | null {
  if (!config.authEnabled) return { user: 'anonymous', viaPassword: false };
  const cookieToken = req.cookies.get(sessionCookieName)?.value
    ?? req.cookies.get('animeko_session')?.value
    ?? req.cookies.get('__Host-animeko_session')?.value;
  if (cookieToken) {
    const s = touchSession(cookieToken);
    if (s) return { user: s.user, viaPassword: false };
  }
  const header = req.headers.get('authorization') ?? '';
  if (header.startsWith('Bearer ')) {
    const token = header.slice(7).trim();
    if (config.authToken && safeEqual(token, config.authToken)) return { user: 'token', viaPassword: true };
    const s = touchSession(token);
    if (s) return { user: s.user, viaPassword: false };
  } else if (header.startsWith('Basic ') && config.authUser && config.authPassword) {
    try {
      const decoded = Buffer.from(header.slice(6), 'base64').toString('utf8');
      const idx = decoded.indexOf(':');
      if (idx > 0 && safeEqual(decoded.slice(0, idx), config.authUser) && safeEqual(decoded.slice(idx + 1), config.authPassword)) {
        return { user: config.authUser, viaPassword: true };
      }
    } catch {
      // 忽略非法 Basic
    }
  }
  return null;
}

export function sessionCookieAttributes(): string {
  const parts = [
    'Path=/',
    'HttpOnly',
    'SameSite=Strict',
    `Max-Age=${COOKIE_MAX_AGE}`,
  ];
  if (config.cookieSecure) parts.push('Secure');
  return parts.join('; ');
}

export function getClientIp(req: NextRequest): string {
  const hops = config.trustedProxyHops;
  if (hops >= 1) {
    const xff = req.headers.get('x-forwarded-for');
    if (xff) {
      const chain = xff.split(',').map((s) => s.trim()).filter(Boolean);
      const candidate = chain[chain.length - hops];
      if (candidate && /^[\d.:a-fA-F]+$/.test(candidate)) return candidate;
    }
  }
  return req.headers.get('x-real-ip') ?? 'local';
}

/** 固定窗口限流器。 */
export class RateLimiter {
  private buckets = new Map<string, { count: number; resetAt: number }>();

  constructor(private readonly limit: number, private readonly windowMs: number, private readonly maxBuckets = 4096) {}

  /** 返回 0 表示放行，否则返回剩余秒数。 */
  acquire(key: string): number {
    const now = Date.now();
    for (const [k, v] of this.buckets) {
      if (v.resetAt <= now) this.buckets.delete(k);
    }
    const bucket = this.buckets.get(key);
    if (!bucket) {
      if (this.buckets.size >= this.maxBuckets) return Math.ceil(this.windowMs / 1000);
      this.buckets.set(key, { count: 1, resetAt: now + this.windowMs });
      return 0;
    }
    if (bucket.count >= this.limit) return Math.max(1, Math.ceil((bucket.resetAt - now) / 1000));
    bucket.count += 1;
    return 0;
  }
}

export const loginLimiter = new RateLimiter(8, 5 * 60 * 1000);
export const probeLimiter = new RateLimiter(20, 5 * 60 * 1000);

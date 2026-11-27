// API 统一门禁：鉴权 + CSRF 同源检查 + 通用响应/错误处理。
import { NextRequest, NextResponse } from 'next/server';
import { authenticate, getClientIp, renewSessionCookieIfDue, sessionCookieName } from './auth';
import { config } from './config';
import { metrics } from './metrics';
import { SsrfError } from './urlGuard';
import { UpstreamError } from './http';

export function json(data: unknown, init: ResponseInit = {}): NextResponse {
  return NextResponse.json(data, init);
}

export function errorJson(status: number, message: string, headers?: HeadersInit): NextResponse {
  return NextResponse.json({ error: message }, { status, headers });
}

/** CSRF：写请求要求同源 Origin（可用环境变量关闭）。 */
export function checkSameOrigin(req: NextRequest): NextResponse | null {
  if (config.allowCrossOrigin) return null;
  const method = req.method.toUpperCase();
  if (method === 'GET' || method === 'HEAD' || method === 'OPTIONS') return null;
  const origin = req.headers.get('origin');
  if (origin) {
    try {
      const o = new URL(origin);
      const host = req.headers.get('x-forwarded-host') ?? req.headers.get('host') ?? '';
      const proto = req.headers.get('x-forwarded-proto') ?? req.nextUrl.protocol.replace(':', '');
      const expectedHost = config.trustedProxyHops >= 1 ? host : (req.headers.get('host') ?? '');
      const defaultPort = proto === 'https' ? '443' : '80';
      const originPort = o.port || (o.protocol === 'https:' ? '443' : '80');
      const hostName = expectedHost.split(':')[0];
      const hostPort = expectedHost.includes(':') ? expectedHost.split(':')[1] : defaultPort;
      if (o.hostname !== hostName || originPort !== hostPort) {
        return errorJson(403, '跨站请求被拒绝');
      }
      return null;
    } catch {
      return errorJson(403, '跨站请求被拒绝');
    }
  }
  const fetchSite = req.headers.get('sec-fetch-site');
  if (fetchSite === 'cross-site' || fetchSite === 'same-site') {
    return errorJson(403, '跨站请求被拒绝');
  }
  return null;
}

/** 统一入口：通过返回 null，否则直接返回错误响应。 */
export function gate(req: NextRequest, options: { public?: boolean } = {}): NextResponse | null {
  metrics.requests += 1;
  const originError = checkSameOrigin(req);
  if (originError) return originError;
  if (options.public || !config.authEnabled) return null;
  const auth = authenticate(req);
  if (!auth) return errorJson(401, '未登录或会话已失效');
  return null;
}

export function clientIp(req: NextRequest): string {
  return getClientIp(req);
}

/** 包装 handler：统一异常映射，并在会话有效时按天滑动重写会话 Cookie。 */
export function handle(req: NextRequest, fn: () => Promise<NextResponse> | NextResponse): Promise<NextResponse> {
  return Promise.resolve()
    .then(fn)
    .then((response) => {
      if (config.authEnabled) {
        const token = req.cookies.get(sessionCookieName)?.value
          ?? req.cookies.get('animeko_session')?.value;
        if (token && renewSessionCookieIfDue(token)) {
          response.cookies.set(sessionCookieName, token, {
            path: '/',
            httpOnly: true,
            sameSite: 'strict',
            maxAge: 30 * 24 * 3600,
            secure: config.cookieSecure,
          });
        }
      }
      return response;
    })
    .catch((err: unknown) => {
      if (err instanceof SsrfError) return errorJson(403, err.message);
      if (err instanceof UpstreamError) return errorJson(err.status, err.message);
      if (err instanceof StorageError) return errorJson(503, '本地存储暂时不可用，请稍后重试');
      const status = (err as { status?: unknown }).status;
      if (typeof status === 'number' && status >= 400 && status < 600) {
        return errorJson(status, err instanceof Error ? err.message : '请求失败');
      }
      const message = err instanceof Error ? err.message : '服务器内部错误';
      if (/^400:/.test(message)) return errorJson(400, message.slice(4));
      console.error('[api]', err);
      return errorJson(500, '服务器内部错误');
    });
}

export class StorageError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'StorageError';
  }
}

export function badRequest(message: string): never {
  throw new Error(`400:${message}`);
}

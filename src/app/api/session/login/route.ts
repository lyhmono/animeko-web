import { NextRequest, NextResponse } from 'next/server';
import { authenticate } from '@/lib/server/auth';
import { config } from '@/lib/server/config';
import { createSession, loginLimiter, sessionCookieName, verifyLogin, getClientIp } from '@/lib/server/auth';
import { errorJson, gate, handle, json } from '@/lib/server/gate';

export const dynamic = 'force-dynamic';

export async function POST(req: NextRequest) {
  return handle(req, async () => {
    const blocked = gate(req, { public: true });
    if (blocked) return blocked;
    if (!config.authEnabled) return errorJson(409, '当前部署未启用登录鉴权');

    const existing = authenticate(req);
    if (existing && !existing.viaPassword) {
      return json({ authenticated: true, user: existing.user });
    }

    const retryAfter = loginLimiter.acquire(getClientIp(req));
    if (retryAfter > 0) return errorJson(429, '尝试过于频繁，请稍后再试', { 'Retry-After': String(retryAfter) });

    let body: { username?: string; password?: string };
    try {
      body = await req.json();
    } catch {
      return errorJson(400, '请求体必须为 JSON');
    }
    const username = typeof body.username === 'string' ? body.username : '';
    const password = typeof body.password === 'string' ? body.password : '';
    if (!password) return errorJson(400, '缺少口令');

    const user = verifyLogin(username, password);
    if (!user) return errorJson(401, '用户名或密码错误');

    const token = createSession(user);
    const response = NextResponse.json({ authenticated: true, user });
    response.cookies.set(sessionCookieName, token, {
      path: '/',
      httpOnly: true,
      sameSite: 'strict',
      maxAge: 30 * 24 * 3600,
      secure: config.cookieSecure,
    });
    response.headers.set('Cache-Control', 'private, no-store');
    return response;
  });
}

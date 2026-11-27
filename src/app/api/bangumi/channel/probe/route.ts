import { NextRequest } from 'next/server';
import { getClientIp, probeLimiter } from '@/lib/server/auth';
import { errorJson, gate, handle, json } from '@/lib/server/gate';
import { probeChannels, type Channel } from '@/lib/server/bangumi';

export const dynamic = 'force-dynamic';

export async function POST(req: NextRequest) {
  return handle(req, async () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    const retryAfter = probeLimiter.acquire(getClientIp(req));
    if (retryAfter > 0) return errorJson(429, '探测过于频繁，请稍后再试', { 'Retry-After': String(retryAfter) });
    const body = await req.json().catch(() => ({})) as { channel?: Channel; customUrls?: string[] };
    return json(await probeChannels(body));
  });
}

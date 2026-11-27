import { NextRequest, NextResponse } from 'next/server';
import { destroySession } from '@/lib/server/auth';
import { gate, handle } from '@/lib/server/gate';

export const dynamic = 'force-dynamic';

export async function POST(req: NextRequest) {
  return handle(req, async () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    for (const name of ['animeko_session', '__Host-animeko_session']) {
      const token = req.cookies.get(name)?.value;
      if (token) destroySession(token);
    }
    const bearer = req.headers.get('authorization');
    if (bearer?.startsWith('Bearer ')) destroySession(bearer.slice(7).trim());
    const response = NextResponse.json({ authenticated: false, user: '' });
    for (const name of ['animeko_session', '__Host-animeko_session']) {
      response.cookies.set(name, '', { path: '/', httpOnly: true, sameSite: 'strict', maxAge: 0 });
    }
    response.headers.set('Cache-Control', 'private, no-store');
    return response;
  });
}

import { NextRequest } from 'next/server';
import { gate, handle, json } from '@/lib/server/gate';
import { ranking } from '@/lib/server/bangumi';

export const dynamic = 'force-dynamic';

export async function GET(req: NextRequest) {
  return handle(req, async () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    const limit = Number(req.nextUrl.searchParams.get('limit') ?? 24) || 24;
    const offset = Number(req.nextUrl.searchParams.get('offset') ?? 0) || 0;
    return json(await ranking(limit, offset));
  });
}

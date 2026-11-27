import { NextRequest } from 'next/server';
import { badRequest, gate, handle, json } from '@/lib/server/gate';
import { comments } from '@/lib/server/danmaku';

export const dynamic = 'force-dynamic';

export async function GET(req: NextRequest) {
  return handle(req, async () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    const episodeId = Number(req.nextUrl.searchParams.get('episodeId'));
    if (!Number.isInteger(episodeId) || episodeId <= 0) badRequest('缺少 episodeId');
    return json({ comments: await comments(episodeId) });
  });
}

import { NextRequest } from 'next/server';
import { badRequest, gate, handle, json } from '@/lib/server/gate';
import { bangumiEpisodes } from '@/lib/server/danmaku';

export const dynamic = 'force-dynamic';

export async function GET(req: NextRequest) {
  return handle(req, async () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    const animeId = Number(req.nextUrl.searchParams.get('animeId'));
    if (!Number.isInteger(animeId) || animeId <= 0) badRequest('缺少 animeId');
    return json(await bangumiEpisodes(animeId));
  });
}

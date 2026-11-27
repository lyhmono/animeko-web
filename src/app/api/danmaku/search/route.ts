import { NextRequest } from 'next/server';
import { badRequest, gate, handle, json } from '@/lib/server/gate';
import { searchAnime } from '@/lib/server/danmaku';

export const dynamic = 'force-dynamic';

export async function GET(req: NextRequest) {
  return handle(req, async () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    const keyword = req.nextUrl.searchParams.get('keyword') ?? req.nextUrl.searchParams.get('q');
    if (!keyword?.trim()) badRequest('缺少关键词');
    return json(await searchAnime(keyword.trim()));
  });
}

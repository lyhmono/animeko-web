import { NextRequest } from 'next/server';
import { badRequest, gate, handle, json } from '@/lib/server/gate';
import { searchSubjects } from '@/lib/server/bangumi';

export const dynamic = 'force-dynamic';

export async function GET(req: NextRequest) {
  return handle(req, async () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    const q = req.nextUrl.searchParams.get('q')?.trim();
    if (!q) badRequest('缺少搜索关键词');
    return json(await searchSubjects(q));
  });
}

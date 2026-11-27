import { NextRequest } from 'next/server';
import { badRequest, gate, handle, json } from '@/lib/server/gate';
import { getSource } from '@/lib/server/selector/store';
import { listEpisodes } from '@/lib/server/selector/engine';

export const dynamic = 'force-dynamic';

export async function POST(req: NextRequest, { params }: { params: { name: string } }) {
  return handle(req, async () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    const source = getSource(decodeURIComponent(params.name), true);
    const body = await req.json().catch(() => badRequest('请求体必须为 JSON'));
    if (typeof body?.subjectUrl !== 'string' || !body.subjectUrl) badRequest('缺少 subjectUrl');
    return json(await listEpisodes(source, body.subjectUrl));
  });
}

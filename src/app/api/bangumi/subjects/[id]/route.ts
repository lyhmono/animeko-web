import { NextRequest } from 'next/server';
import { badRequest, gate, handle, json } from '@/lib/server/gate';
import { subjectWithEpisodes } from '@/lib/server/bangumi';

export const dynamic = 'force-dynamic';

export async function GET(req: NextRequest, { params }: { params: { id: string } }) {
  return handle(req, async () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    const id = Number(params.id);
    if (!Number.isInteger(id) || id <= 0) badRequest('条目 id 必须为整数');
    return json(await subjectWithEpisodes(id));
  });
}

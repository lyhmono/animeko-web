import { NextRequest } from 'next/server';
import { badRequest, gate, handle, json } from '@/lib/server/gate';
import { setSourceEnabled } from '@/lib/server/selector/store';

export const dynamic = 'force-dynamic';

export async function POST(req: NextRequest, { params }: { params: { name: string } }) {
  return handle(req, async () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    const name = decodeURIComponent(params.name);
    const body = await req.json().catch(() => badRequest('请求体必须为 JSON'));
    if (typeof body?.enabled !== 'boolean') badRequest('需要显式 enabled 布尔值');
    setSourceEnabled(name, body.enabled);
    return json({ name, enabled: body.enabled });
  });
}

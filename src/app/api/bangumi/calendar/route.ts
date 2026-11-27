import { NextRequest } from 'next/server';
import { gate, handle, json } from '@/lib/server/gate';
import { calendar } from '@/lib/server/bangumi';

export const dynamic = 'force-dynamic';

export async function GET(req: NextRequest) {
  return handle(req, async () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    return json(await calendar());
  });
}

import { NextRequest } from 'next/server';
import { gate, handle, json } from '@/lib/server/gate';
import { healthSnapshot } from '@/lib/server/selector/store';

export const dynamic = 'force-dynamic';

export async function GET(req: NextRequest) {
  return handle(req, () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    return json(healthSnapshot());
  });
}

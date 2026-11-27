import { NextRequest } from 'next/server';
import { gate, handle, json } from '@/lib/server/gate';
import { deleteSource } from '@/lib/server/selector/store';

export const dynamic = 'force-dynamic';

export async function DELETE(req: NextRequest, { params }: { params: { name: string } }) {
  return handle(req, () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    const name = decodeURIComponent(params.name);
    deleteSource(name);
    return json({ deleted: name });
  });
}

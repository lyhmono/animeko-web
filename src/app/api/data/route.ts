import { NextRequest, NextResponse } from 'next/server';
import { badRequest, errorJson, gate, handle } from '@/lib/server/gate';
import { conditionalWrite, patchUserData, readUserData } from '@/lib/server/userData';

export const dynamic = 'force-dynamic';

export async function GET(req: NextRequest) {
  return handle(req, () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    const { doc, etag } = readUserData();
    return NextResponse.json(doc, {
      headers: { ETag: `"${etag}"`, 'Cache-Control': 'no-store' },
    });
  });
}

export async function PUT(req: NextRequest) {
  return handle(req, async () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    const raw = await req.text();
    if (raw.length > 512 * 1024) badRequest('个人数据超过 512 KiB 上限');
    let doc: Record<string, unknown>;
    try {
      doc = JSON.parse(raw);
    } catch {
      badRequest('请求体必须为 JSON 对象');
    }
    if (typeof doc !== 'object' || doc === null || Array.isArray(doc)) badRequest('请求体必须为 JSON 对象');
    const { etag, bytes } = conditionalWrite(doc, req.headers.get('if-match'));
    return NextResponse.json({ bytes }, { headers: { ETag: `"${etag}"` } });
  });
}

export async function PATCH(req: NextRequest) {
  return handle(req, async () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    let body: { section?: string; key?: string; value?: unknown };
    try {
      body = await req.json();
    } catch {
      badRequest('请求体必须为 JSON');
    }
    if (!body.section || typeof body.key !== 'string' || !body.key) badRequest('缺少 section 或 key');
    const { etag } = patchUserData(body.section, body.key, body.value, req.headers.get('if-match'));
    return NextResponse.json({ ok: true }, { headers: { ETag: `"${etag}"` } });
  });
}

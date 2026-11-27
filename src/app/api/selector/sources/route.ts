import { NextRequest } from 'next/server';
import { gate, handle, json } from '@/lib/server/gate';
import { listSources, normalizeSource, saveSource } from '@/lib/server/selector/store';
import { ensureSubscriptionAutomation } from '@/lib/server/selector/automation';

export const dynamic = 'force-dynamic';

export async function GET(req: NextRequest) {
  return handle(req, () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    ensureSubscriptionAutomation();
    return json(listSources());
  });
}

export async function POST(req: NextRequest) {
  return handle(req, async () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    const text = await req.text();
    if (text.length > 200000) {
      return json({ error: '数据源配置超过 200000 字符上限' }, { status: 400 });
    }
    let body: unknown;
    try {
      body = JSON.parse(text);
    } catch {
      return json({ error: '请求体必须为 JSON' }, { status: 400 });
    }
    const source = normalizeSource(body);
    saveSource(source);
    return json(source.arguments, { status: 201 });
  });
}

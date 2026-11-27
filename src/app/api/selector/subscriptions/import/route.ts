import { NextRequest } from 'next/server';
import { badRequest, gate, handle, json } from '@/lib/server/gate';
import { importSubscription, loadSubscriptionText } from '@/lib/server/selector/subscriptions';

export const dynamic = 'force-dynamic';

export async function POST(req: NextRequest) {
  return handle(req, async () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    const body = await req.json().catch(() => badRequest('请求体必须为 JSON')) as {
      url?: string;
      text?: string;
      names?: string[];
      fingerprint?: string;
    };
    if (!Array.isArray(body.names)) badRequest('缺少导入名单');
    const { text, fromUrl } = await loadSubscriptionText(body);
    return json(importSubscription(text, body.names, body.fingerprint, fromUrl));
  });
}

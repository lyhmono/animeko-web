import { NextRequest } from 'next/server';
import { gate, handle, json } from '@/lib/server/gate';
import { loadSubscriptionText, previewSubscription } from '@/lib/server/selector/subscriptions';

export const dynamic = 'force-dynamic';

export async function POST(req: NextRequest) {
  return handle(req, async () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    const body = await req.json().catch(() => ({})) as { url?: string; text?: string; label?: string };
    const { text } = await loadSubscriptionText(body);
    return json(previewSubscription(text, body.label ?? body.url ?? '订阅'));
  });
}

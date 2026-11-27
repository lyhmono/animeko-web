import { NextRequest } from 'next/server';
import { gate, json } from '@/lib/server/gate';
import { metrics, uptimeSeconds } from '@/lib/server/metrics';

export const dynamic = 'force-dynamic';

export async function GET(req: NextRequest) {
  const blocked = gate(req);
  if (blocked) return blocked;
  return json({ requests: metrics.requests, proxyRequests: metrics.proxyRequests, uptimeSeconds: uptimeSeconds() });
}

import { NextRequest } from 'next/server';
import { config } from '@/lib/server/config';
import { authenticate } from '@/lib/server/auth';
import { gate, json } from '@/lib/server/gate';
import { ensureSubscriptionAutomation } from '@/lib/server/selector/automation';

export const dynamic = 'force-dynamic';

export async function GET(req: NextRequest) {
  const blocked = gate(req, { public: true });
  if (blocked) return blocked;
  ensureSubscriptionAutomation();
  const auth = config.authEnabled ? authenticate(req) : null;
  return json({
    status: 'ok',
    version: config.version,
    authEnabled: config.authEnabled,
    authenticated: config.authEnabled ? Boolean(auth) : true,
  });
}

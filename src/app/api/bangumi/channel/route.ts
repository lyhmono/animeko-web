import { NextRequest } from 'next/server';
import { badRequest, gate, handle, json } from '@/lib/server/gate';
import { channelResponse, saveChannel, type Channel } from '@/lib/server/bangumi';

export const dynamic = 'force-dynamic';

export async function GET(req: NextRequest) {
  return handle(req, () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    return json(channelResponse());
  });
}

export async function PUT(req: NextRequest) {
  return handle(req, async () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    const body = await req.json().catch(() => badRequest('请求体必须为 JSON'));
    const channel = body?.channel as Channel;
    if (!['AUTO', 'OFFICIAL', 'MIRROR', 'CUSTOM'].includes(channel)) badRequest('非法通道');
    const customUrls = Array.isArray(body?.customUrls)
      ? body.customUrls.filter((u: unknown): u is string => typeof u === 'string' && /^https?:\/\//.test(u))
      : [];
    return json(channelResponseWithSave(channel, customUrls));
  });
}

function channelResponseWithSave(channel: Channel, customUrls: string[]) {
  saveChannel({ channel, customUrls });
  return channelResponse();
}

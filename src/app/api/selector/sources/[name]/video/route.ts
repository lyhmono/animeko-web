import { NextRequest } from 'next/server';
import { badRequest, gate, handle, json } from '@/lib/server/gate';
import { getSource } from '@/lib/server/selector/store';
import { resolveVideo } from '@/lib/server/selector/engine';
import { createProxySession } from '@/lib/server/proxySessions';

export const dynamic = 'force-dynamic';

export async function POST(req: NextRequest, { params }: { params: { name: string } }) {
  return handle(req, async () => {
    const blocked = gate(req);
    if (blocked) return blocked;
    const source = getSource(decodeURIComponent(params.name), true);
    const body = await req.json().catch(() => badRequest('请求体必须为 JSON'));
    if (typeof body?.playUrl !== 'string' || !body.playUrl) badRequest('缺少 playUrl');
    const video = await resolveVideo(source, body.playUrl);
    const sid = createProxySession({
      url: video.url,
      referer: video.referer,
      userAgent: video.userAgent,
      cookie: video.cookie,
      cookieHosts: video.cookieHosts,
    });
    const proxyPath = `/api/proxy?url=${encodeURIComponent(video.url)}${sid ? `&sid=${sid}` : ''}`;
    return json({ url: video.url, proxyPath });
  });
}

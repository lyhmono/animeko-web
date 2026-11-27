// 弹幕服务客户端：弹弹 play /api/v2 协议（支持自建兼容服务与官方签名）。
import crypto from 'node:crypto';
import { config } from './config';
import { guardedFetch, readBodyLimited } from './http';
import { badRequest } from './gate';

function signedHeaders(pathAndQuery: string): Record<string, string> {
  const headers: Record<string, string> = { accept: '*/*', 'user-agent': 'animeko-web/1.0' };
  if (config.danmakuAppId && config.danmakuAppSecret) {
    const timestamp = Math.floor(Date.now() / 1000).toString();
    const signature = crypto
      .createHash('sha256')
      .update(`${config.danmakuAppId}${timestamp}${pathAndQuery}${config.danmakuAppSecret}`)
      .digest('base64');
    headers['x-appid'] = config.danmakuAppId;
    headers['x-timestamp'] = timestamp;
    headers['x-signature'] = signature;
  }
  return headers;
}

async function danmakuRequest(pathAndQuery: string): Promise<string> {
  const { response } = await guardedFetch(`${config.danmakuApiBase}${pathAndQuery}`, {
    headers: signedHeaders(pathAndQuery),
    timeoutMs: config.requestTimeoutMs,
  });
  if (!response.ok) {
    await response.arrayBuffer().catch(() => undefined);
    badRequest(`弹幕服务返回 ${response.status}`);
  }
  const buf = await readBodyLimited(response, 16 * 1024 * 1024);
  return buf.toString('utf8');
}

export async function searchAnime(keyword: string): Promise<unknown> {
  const path = `/api/v2/search/anime?keyword=${encodeURIComponent(keyword)}`;
  return JSON.parse(await danmakuRequest(path));
}

export async function bangumiEpisodes(animeId: number): Promise<unknown> {
  return JSON.parse(await danmakuRequest(`/api/v2/bangumi/${animeId}`));
}

/** 拉取弹幕 XML 并解析为统一结构。 */
export async function comments(episodeId: number): Promise<Array<{ p: string; m: string; cid: number }>> {
  const xml = await danmakuRequest(`/api/v2/comment/${episodeId}?format=xml`);
  return parseDanmakuXml(xml);
}

/** 解析 B 站 / 弹弹 play XML 弹幕。 */
export function parseDanmakuXml(xml: string): Array<{ p: string; m: string; cid: number }> {
  const result: Array<{ p: string; m: string; cid: number }> = [];
  const re = /<d\s+p="([^"]*)"[^>]*>([\s\S]*?)<\/d>/g;
  let match: RegExpExecArray | null;
  let cid = 0;
  while ((match = re.exec(xml)) !== null && result.length < 20000) {
    const text = match[2]
      .replace(/&lt;/g, '<').replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, '&');
    result.push({ p: match[1], m: text, cid: cid += 1 });
  }
  return result;
}

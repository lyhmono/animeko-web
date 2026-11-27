// Bangumi 通道管理与 API 客户端（顺序故障转移 + 可选竞速简化）。
import fs from 'node:fs';
import path from 'node:path';
import { bangumiChannelFile, config } from './config';
import { badRequest } from './gate';
import { fetchText, guardedFetch, readBodyLimited, UpstreamError } from './http';
import { requireAllowed } from './urlGuard';

export type Channel = 'AUTO' | 'OFFICIAL' | 'MIRROR' | 'CUSTOM';

export interface ChannelState {
  channel: Channel;
  customUrls: string[];
}

const OFFICIAL = 'https://api.bgm.tv';
const MAX_METADATA_BYTES = 8 * 1024 * 1024;

export function loadChannel(): ChannelState {
  try {
    const raw = JSON.parse(fs.readFileSync(bangumiChannelFile(), 'utf8')) as ChannelState;
    if (['AUTO', 'OFFICIAL', 'MIRROR', 'CUSTOM'].includes(raw.channel)) {
      return { channel: raw.channel, customUrls: Array.isArray(raw.customUrls) ? raw.customUrls : [] };
    }
  } catch {
    // 缺省配置
  }
  return { channel: 'AUTO', customUrls: [] };
}

export function saveChannel(state: ChannelState): ChannelState {
  if (state.channel === 'CUSTOM') {
    if (!state.customUrls.length || state.customUrls.length > 8) badRequest('自定义通道需要 1~8 个地址');
  }
  const normalized: ChannelState = {
    channel: state.channel,
    customUrls: state.channel === 'CUSTOM' ? state.customUrls.map((u) => u.replace(/\/+$/, '')) : [],
  };
  fs.mkdirSync(path.dirname(bangumiChannelFile()), { recursive: true });
  fs.writeFileSync(bangumiChannelFile(), JSON.stringify(normalized, null, 2));
  return normalized;
}

export function effectiveBases(state = loadChannel()): string[] {
  switch (state.channel) {
    case 'OFFICIAL': return [OFFICIAL];
    case 'MIRROR': return config.bangumiBases.filter((b) => b !== OFFICIAL);
    case 'CUSTOM': return state.customUrls;
    default: return config.bangumiBases;
  }
}

export function channelResponse() {
  const state = loadChannel();
  return {
    ...state,
    effectiveBases: effectiveBases(state),
    defaultBases: config.bangumiBases,
    calendarSupported: state.channel !== 'MIRROR',
  };
}

let preferredBase: string | null = null;

async function requestFromBase(base: string, pathAndQuery: string, init?: { method?: string; body?: string }): Promise<unknown> {
  const { response } = await guardedFetch(`${base}${pathAndQuery}`, {
    method: init?.method ?? 'GET',
    body: init?.body,
    headers: {
      accept: 'application/json',
      'user-agent': 'animeko-web/1.0 (https://github.com/lyhmono/animeko-web)',
      ...(init?.body ? { 'content-type': 'application/json' } : {}),
    },
    timeoutMs: config.bangumiTimeoutMs,
  });
  if (response.status === 404) throw new UpstreamError('条目不存在', 404);
  if (!response.ok) {
    await response.arrayBuffer().catch(() => undefined);
    throw new UpstreamError(`Bangumi 返回 ${response.status}`);
  }
  const buf = await readBodyLimited(response, MAX_METADATA_BYTES);
  return JSON.parse(buf.toString('utf8'));
}

/**
 * 多通道竞速：首选（最近成功地址）先跑，2.5 秒对冲窗口后其余候选并发加入；
 * 首选提前失败则立即故障转移，不等窗口。任一成功即采用并记住为新的首选；
 * 404 是确定性业务结果，直接抛出，不再惊动其他通道。
 */
async function request(pathAndQuery: string, init?: { method?: string; body?: string }): Promise<unknown> {
  const bases = effectiveBases();
  if (!bases.length) throw new UpstreamError('没有可用的 Bangumi 通道');
  const ordered = preferredBase && bases.includes(preferredBase)
    ? [preferredBase, ...bases.filter((b) => b !== preferredBase)]
    : bases;
  if (ordered.length === 1) return requestFromBase(ordered[0], pathAndQuery, init);

  const [first, ...rest] = ordered;
  const HEDGE_MS = 2500;
  // 条目详情的 404 是「条目不存在」，直通抛出；
  // 但每日放送的 404 是「该通道不支持此端点」（镜像普遍如此），必须当通道失败继续转移。
  const notFoundFinal = !pathAndQuery.startsWith('/v0/calendar');
  return new Promise((resolve, reject) => {
    let settled = false;
    let failed = 0;
    let restStarted = false;
    const errors: unknown[] = [];
    let hedgeTimer: NodeJS.Timeout | null = null;

    const stopHedge = () => {
      if (hedgeTimer) {
        clearTimeout(hedgeTimer);
        hedgeTimer = null;
      }
    };
    const startRest = () => {
      if (restStarted || settled) return;
      restStarted = true;
      stopHedge();
      rest.forEach((base) => { void attempt(base); });
    };
    const attempt = async (base: string) => {
      try {
        const result = await requestFromBase(base, pathAndQuery, init);
        if (!settled) {
          settled = true;
          preferredBase = base;
          stopHedge();
          resolve(result);
        }
      } catch (err) {
        if (settled) return;
        if (err instanceof UpstreamError && err.status === 404 && notFoundFinal) {
          settled = true;
          stopHedge();
          reject(err);
          return;
        }
        errors.push(err);
        failed += 1;
        if (base === first && !restStarted) startRest();
        if (failed === ordered.length) {
          settled = true;
          stopHedge();
          reject(errors[0] instanceof Error ? errors[0] : new UpstreamError('Bangumi 请求失败'));
        }
      }
    };

    hedgeTimer = setTimeout(startRest, HEDGE_MS);
    void attempt(first);
  });
}

export async function searchSubjects(keyword: string) {
  const body = JSON.stringify({ keyword, filter: { type: [2] }, limit: 24 });
  const result = await request('/v0/search/subjects', { method: 'POST', body }) as { data?: unknown[] };
  return result.data ?? [];
}

export async function ranking(limit: number, offset: number) {
  const lim = Math.min(50, Math.max(1, limit));
  const off = Math.min(5000, Math.max(0, offset));
  return request(`/v0/subjects?type=2&sort=rank&limit=${lim}&offset=${off}`);
}

export async function calendar() {
  return request('/v0/calendar');
}

export async function subjectWithEpisodes(id: number) {
  const subject = await request(`/v0/subjects/${id}?responseGroup=large`) as Record<string, unknown>;
  const episodes: unknown[] = [];
  for (let offset = 0; offset < 2000; offset += 200) {
    const page = await request(`/v0/episodes?subject_id=${id}&limit=200&offset=${offset}`) as { data?: unknown[] };
    const data = page.data ?? [];
    episodes.push(...data);
    if (data.length < 200) break;
  }
  return { ...subject, eps: episodes };
}

export async function probeBase(base: string): Promise<{ base: string; ok: boolean; latencyMs: number; detail: string; calendarOk: boolean }> {
  const started = Date.now();
  let ok = false;
  let calendarOk = false;
  let detail = '';
  try {
    await requireAllowed(`${base}/v0/subjects/290980`);
    const { response } = await guardedFetch(`${base}/v0/subjects/290980`, {
      headers: { accept: 'application/json', 'user-agent': 'animeko-web/1.0' },
      timeoutMs: Math.min(config.bangumiTimeoutMs, 12000),
    });
    await response.arrayBuffer().catch(() => undefined);
    ok = response.ok;
    detail = `HTTP ${response.status}`;
  } catch (err) {
    detail = err instanceof Error ? err.message.slice(0, 120) : '探测失败';
  }
  try {
    const { response } = await guardedFetch(`${base}/v0/calendar`, {
      headers: { accept: 'application/json', 'user-agent': 'animeko-web/1.0' },
      timeoutMs: 12000,
    });
    await response.arrayBuffer().catch(() => undefined);
    calendarOk = response.ok;
  } catch {
    calendarOk = false;
  }
  return { base, ok, latencyMs: Date.now() - started, detail, calendarOk };
}

export async function probeChannels(input?: { channel?: Channel; customUrls?: string[] }) {
  const state = input?.channel ? { channel: input.channel, customUrls: input.customUrls ?? [] } : loadChannel();
  const bases = effectiveBases(state);
  return Promise.all(bases.map((b) => probeBase(b)));
}

export { fetchText };

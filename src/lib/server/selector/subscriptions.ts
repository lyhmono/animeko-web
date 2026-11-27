// 聚合订阅：预览（fingerprint）与导入。
import { createHash } from 'node:crypto';
import { badRequest } from '../gate';
import { fetchText } from '../http';
import { getSource, listSources, normalizeSource, registerSubscription, saveSource } from './store';
import type { StoredSource } from './types';

export interface SubscriptionCandidate {
  name: string;
  description: string;
  tier: string;
  site: string;
  exists: boolean;
  enabled: boolean;
}

export interface SubscriptionPreview {
  subscription: string;
  candidates: SubscriptionCandidate[];
  rejected: string[];
  fingerprint: string;
}

export interface ParsedSubscription {
  sources: StoredSource[];
  rejected: string[];
}

const MAX_ITEMS = 500;

export function parseSubscription(text: string): ParsedSubscription {
  if (text.length > 4 * 1024 * 1024) badRequest('订阅内容过大');
  let root: unknown;
  try {
    root = JSON.parse(text);
  } catch {
    badRequest('订阅不是合法 JSON');
  }
  let items: unknown[] = [];
  if (Array.isArray(root)) items = root;
  else if (root && typeof root === 'object') {
    const obj = root as Record<string, unknown>;
    const exported = obj.exportedMediaSourceDataList as Record<string, unknown> | undefined;
    if (exported && Array.isArray(exported.mediaSources)) items = exported.mediaSources;
    else if (Array.isArray(obj.mediaSources)) items = obj.mediaSources;
    else if (Array.isArray(obj.list)) items = obj.list;
    else items = [root];
  }
  const sources: StoredSource[] = [];
  const rejected: string[] = [];
  const seen = new Set<string>();
  items.slice(0, MAX_ITEMS).forEach((item, index) => {
    try {
      const source = normalizeSource(item);
      const name = source.arguments.name;
      if (seen.has(name)) {
        rejected.push(`#${index + 1} ${name}：订阅内重名`);
        return;
      }
      seen.add(name);
      sources.push(source);
    } catch (err) {
      rejected.push(`#${index + 1}：${err instanceof Error ? err.message.replace(/^400:/, '') : '配置非法'}`);
    }
  });
  if (items.length > MAX_ITEMS) rejected.push(`超出 ${MAX_ITEMS} 条的 ${items.length - MAX_ITEMS} 项被忽略`);
  return { sources, rejected };
}

export async function loadSubscriptionText(input: { url?: string; text?: string }): Promise<{ text: string; fromUrl: string | null }> {
  if (input.text && input.text.trim()) {
    if (input.text.length > 200000 * 10) badRequest('订阅文本过大');
    return { text: input.text, fromUrl: null };
  }
  if (input.url && input.url.trim()) {
    const { text } = await fetchText(input.url.trim(), { maxBytes: 4 * 1024 * 1024 });
    return { text, fromUrl: input.url.trim() };
  }
  badRequest('需要提供订阅链接或文本');
}

export function previewSubscription(text: string, label?: string): SubscriptionPreview {
  const parsed = parseSubscription(text);
  const existing = new Map(listSources().map((s) => [s.arguments.name, s]));
  return {
    subscription: label ?? '订阅',
    candidates: parsed.sources.map((s) => ({
      name: s.arguments.name,
      description: s.arguments.description ?? '',
      tier: s.arguments.tier == null ? '' : String(s.arguments.tier),
      site: s.arguments.searchConfig.searchUrl,
      exists: existing.has(s.arguments.name),
      enabled: existing.get(s.arguments.name)?.enabled ?? true,
    })),
    rejected: parsed.rejected,
    fingerprint: createHash('sha256').update(text, 'utf8').digest('hex').slice(0, 32),
  };
}

export function importSubscription(text: string, names: string[], fingerprint: string | undefined, fromUrl: string | null) {
  if (fingerprint) {
    const actual = createHash('sha256').update(text, 'utf8').digest('hex').slice(0, 32);
    if (actual !== fingerprint) badRequest('订阅内容已变化，请重新预览');
  }
  const parsed = parseSubscription(text);
  const wanted = new Set(names);
  const added: string[] = [];
  const updated: string[] = [];
  const failed: string[] = [];
  for (const source of parsed.sources) {
    const name = source.arguments.name;
    if (!wanted.has(name)) continue;
    try {
      const existing = (() => {
        try {
          return getSource(name);
        } catch {
          return null;
        }
      })();
      saveSource({ ...source, enabled: existing ? existing.enabled : source.enabled });
      if (existing) updated.push(name);
      else added.push(name);
    } catch {
      failed.push(name);
    }
  }
  if (fromUrl && (added.length || updated.length)) registerSubscription(fromUrl);
  return { added, updated, failed, rejected: parsed.rejected };
}

/**
 * 拉取订阅并 upsert 其中全部可解析源（保留已有源的 enabled 状态）。
 * 供开机自动导入与定时自动刷新使用；tier 过滤仅在显式给出时生效。
 */
export async function refreshSubscription(url: string, opts: { tiers?: string[] } = {}) {
  const { text } = await loadSubscriptionText({ url });
  const parsed = parseSubscription(text);
  const added: string[] = [];
  const updated: string[] = [];
  const failed: string[] = [];
  for (const source of parsed.sources) {
    if (opts.tiers?.length) {
      const tier = source.arguments.tier == null ? '' : String(source.arguments.tier);
      if (!opts.tiers.includes(tier)) continue;
    }
    const name = source.arguments.name;
    try {
      let existing: StoredSource | null = null;
      try {
        existing = getSource(name);
      } catch {
        existing = null;
      }
      saveSource({ ...source, enabled: existing ? existing.enabled : source.enabled });
      if (existing) updated.push(name);
      else added.push(name);
    } catch {
      failed.push(name);
    }
  }
  return { added, updated, failed, rejected: parsed.rejected };
}

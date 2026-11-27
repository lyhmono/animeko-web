// 数据源仓库：JSON 文件持久化 + 健康监控 + 请求间隔控制。
import fs from 'node:fs';
import path from 'node:path';
import { selectorsDir, subscriptionsFile } from '../config';
import { badRequest, StorageError } from '../gate';
import type { SelectorSourceArguments, StoredSource } from './types';

export interface SourceHealth {
  success: number;
  failure: number;
  totalMs: number;
  lastError: string | null;
  lastAt: number;
  avgMs: number;
}

const health = new Map<string, Omit<SourceHealth, 'avgMs'>>();
const lastRequestAt = new Map<string, number>();
const inflight = new Map<string, Promise<unknown>>();

export function recordHealth(name: string, ok: boolean, elapsedMs: number, error?: string): void {
  const h = health.get(name) ?? { success: 0, failure: 0, totalMs: 0, lastError: null, lastAt: 0 };
  if (ok) h.success += 1;
  else {
    h.failure += 1;
    h.lastError = (error ?? '未知错误').slice(0, 120);
  }
  h.totalMs += elapsedMs;
  h.lastAt = Date.now();
  health.set(name, h);
}

export function healthSnapshot(): Record<string, SourceHealth> {
  const result: Record<string, SourceHealth> = {};
  for (const [name, h] of health) {
    result[name] = { ...h, avgMs: h.success + h.failure > 0 ? Math.round(h.totalMs / (h.success + h.failure)) : 0 };
  }
  return result;
}

function sanitizeName(name: string): string {
  return name.replace(/[\\/:*?"<>|]/g, '_').slice(0, 100) || 'unnamed';
}

function sourceFile(name: string): string {
  return path.join(selectorsDir(), `${sanitizeName(name)}.json`);
}

export function normalizeSource(input: unknown): StoredSource {
  if (!input || typeof input !== 'object') badRequest('数据源配置必须为对象');
  const wrapper = input as Record<string, unknown>;
  const factoryId = typeof wrapper.factoryId === 'string' && wrapper.factoryId ? wrapper.factoryId : 'web-selector';
  if (factoryId !== 'web-selector') badRequest('仅支持 web-selector 数据源');
  const args = (wrapper.arguments && typeof wrapper.arguments === 'object' ? wrapper.arguments : wrapper) as SelectorSourceArguments;
  if (!args.name || typeof args.name !== 'string') badRequest('数据源缺少名称');
  if (args.name.length > 100) badRequest('数据源名称过长');
  if (!args.searchConfig || typeof args.searchConfig.searchUrl !== 'string' || !args.searchConfig.searchUrl) {
    badRequest('数据源缺少 searchUrl');
  }
  return {
    factoryId: 'web-selector',
    version: typeof wrapper.version === 'number' ? wrapper.version : 2,
    enabled: wrapper.enabled !== false,
    arguments: args,
  };
}

export function listSources(): StoredSource[] {
  try {
    fs.mkdirSync(selectorsDir(), { recursive: true });
    const files = fs.readdirSync(selectorsDir()).filter((f) => f.endsWith('.json'));
    const sources: StoredSource[] = [];
    for (const file of files) {
      try {
        const raw = JSON.parse(fs.readFileSync(path.join(selectorsDir(), file), 'utf8'));
        sources.push(normalizeSource(raw));
      } catch {
        // 跳过损坏文件
      }
    }
    return sources.sort((a, b) => a.arguments.name.localeCompare(b.arguments.name));
  } catch {
    throw new StorageError('list sources failed');
  }
}

export function getSource(name: string, requireEnabled = false): StoredSource {
  const source = listSources().find((s) => s.arguments.name === name);
  if (!source || (requireEnabled && !source.enabled)) {
    const err = new Error('数据源不存在或已停用') as Error & { status?: number };
    err.status = 404;
    throw err;
  }
  return source;
}

export function saveSource(source: StoredSource): void {
  try {
    fs.mkdirSync(selectorsDir(), { recursive: true });
    fs.writeFileSync(sourceFile(source.arguments.name), JSON.stringify(source, null, 2));
  } catch {
    throw new StorageError('save source failed');
  }
}

export function deleteSource(name: string): void {
  getSource(name);
  try {
    fs.unlinkSync(sourceFile(name));
  } catch {
    throw new StorageError('delete source failed');
  }
}

export function setSourceEnabled(name: string, enabled: boolean): void {
  const source = getSource(name);
  saveSource({ ...source, enabled });
}

/** 同源请求间隔控制（串行准入）。 */
const rateChains = new Map<string, Promise<void>>();

export async function acquireSourceSlot(name: string, intervalMs: number): Promise<void> {
  const interval = Math.min(15000, Math.max(0, intervalMs));
  const previous = rateChains.get(name) ?? Promise.resolve();
  const next = previous.then(async () => {
    const last = lastRequestAt.get(name) ?? 0;
    const wait = last + interval - Date.now();
    if (wait > 0) await new Promise((r) => setTimeout(r, wait));
    lastRequestAt.set(name, Date.now());
  });
  rateChains.set(name, next.catch(() => undefined));
  return next;
}

/** 合并相同 key 的在飞请求。 */
export function singleFlight<T>(key: string, fn: () => Promise<T>): Promise<T> {
  const existing = inflight.get(key);
  if (existing) return existing as Promise<T>;
  const promise = fn().finally(() => inflight.delete(key));
  inflight.set(key, promise);
  return promise;
}

export interface SubscriptionRecord {
  url: string;
  addedAt: number;
  lastRefreshAt: number;
  lastError: string | null;
}

export function registerSubscription(url: string): void {
  try {
    fs.mkdirSync(path.dirname(subscriptionsFile()), { recursive: true });
    let list: SubscriptionRecord[] = [];
    try {
      list = JSON.parse(fs.readFileSync(subscriptionsFile(), 'utf8'));
    } catch {
      list = [];
    }
    const existing = list.find((s) => s.url === url);
    if (existing) {
      existing.lastRefreshAt = Date.now();
      existing.lastError = null;
    } else {
      list.push({ url, addedAt: Date.now(), lastRefreshAt: Date.now(), lastError: null });
    }
    fs.writeFileSync(subscriptionsFile(), JSON.stringify(list, null, 2));
  } catch {
    // 登记失败不影响导入
  }
}

export function listSubscriptions(): SubscriptionRecord[] {
  try {
    const list = JSON.parse(fs.readFileSync(subscriptionsFile(), 'utf8'));
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

/** 定时刷新后回写刷新时间与错误信息。 */
export function updateSubscriptionRecord(url: string, error: string | null): void {
  try {
    const list = listSubscriptions();
    const item = list.find((s) => s.url === url);
    if (!item) return;
    item.lastRefreshAt = Date.now();
    item.lastError = error;
    fs.mkdirSync(path.dirname(subscriptionsFile()), { recursive: true });
    fs.writeFileSync(subscriptionsFile(), JSON.stringify(list, null, 2));
  } catch {
    // 记录失败不影响刷新
  }
}

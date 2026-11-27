// 订阅自动化：开机自动导入 + 定时自动刷新（首个 API 请求触发，单进程幂等）。
import { config } from '../config';
import { listSubscriptions, registerSubscription, updateSubscriptionRecord } from './store';
import { refreshSubscription } from './subscriptions';

let started = false;
let refreshing = false;

async function refreshAll(): Promise<void> {
  if (refreshing) return; // 上一轮未结束则跳过，避免重叠
  refreshing = true;
  try {
    for (const sub of listSubscriptions()) {
      try {
        await refreshSubscription(sub.url);
        updateSubscriptionRecord(sub.url, null);
      } catch (err) {
        updateSubscriptionRecord(sub.url, err instanceof Error ? err.message.slice(0, 200) : '刷新失败');
      }
    }
  } finally {
    refreshing = false;
  }
}

async function autoImport(): Promise<void> {
  for (const url of config.subscriptionAutoImport) {
    try {
      const result = await refreshSubscription(url, { tiers: config.subscriptionAutoTiers });
      registerSubscription(url);
      console.log(`[subscription] 自动导入 ${url}：新增 ${result.added.length}，更新 ${result.updated.length}，失败 ${result.failed.length}`);
    } catch (err) {
      console.warn('[subscription] 自动导入失败', url, err instanceof Error ? err.message : err);
    }
  }
}

/** 幂等启动：自动导入执行一次；已登记订阅按 ANIMEKO_SUBSCRIPTION_REFRESH_MS 周期刷新。 */
export function ensureSubscriptionAutomation(): void {
  if (started) return;
  started = true;
  if (config.subscriptionAutoImport.length) void autoImport();
  const timer = setInterval(() => { void refreshAll(); }, config.subscriptionRefreshMs);
  timer.unref();
}

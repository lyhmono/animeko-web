// 服务端全局配置：环境变量解析（对齐原 Kotlin AppConfig）。
import path from 'node:path';

function boolEnv(name: string, fallback: boolean): boolean {
  const raw = process.env[name];
  if (raw == null || raw.trim() === '') return fallback;
  const v = raw.trim().toLowerCase();
  if (['true', '1', 'yes', 'on', 'y', 't'].includes(v)) return true;
  if (['false', '0', 'no', 'off', 'n', 'f'].includes(v)) return false;
  return fallback;
}

function longEnv(name: string, fallback: number, min: number, max: number): number {
  const raw = process.env[name];
  if (!raw) return fallback;
  const n = Number(raw);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(max, Math.max(min, Math.round(n)));
}

function strEnv(name: string): string | null {
  const raw = process.env[name];
  if (raw == null || raw.trim() === '') return null;
  return raw.trim();
}

const authUser = strEnv('ANIMEKO_AUTH_USER');
const authPassword = strEnv('ANIMEKO_AUTH_PASSWORD');
const authToken = strEnv('ANIMEKO_AUTH_TOKEN');

const defaultBases = ['https://api.bgm.tv', 'https://bgmapi.anibt.net'];
const baseUrlsRaw = strEnv('ANIMEKO_BANGUMI_BASE_URLS') ?? strEnv('ANIMEKO_BANGUMI_BASE_URL');
const bangumiBases = (baseUrlsRaw ? baseUrlsRaw.split(',') : defaultBases)
  .map((s) => s.trim().replace(/\/+$/, ''))
  .filter((s) => /^https?:\/\//.test(s))
  .slice(0, 8);

export const config = {
  dataDir: strEnv('ANIMEKO_DATA_DIR') ?? path.join(process.cwd(), 'data'),
  authUser,
  authPassword,
  authToken,
  authEnabled: boolEnv('ANIMEKO_AUTH_ENABLED', Boolean(authToken || (authUser && authPassword))),
  cookieSecure: boolEnv('ANIMEKO_COOKIE_SECURE', false),
  allowNoAuth: boolEnv('ANIMEKO_ALLOW_NO_AUTH', false),
  allowPrivateTargets: boolEnv('ANIMEKO_ALLOW_PRIVATE_TARGETS', false),
  allowCrossOrigin: boolEnv('ANIMEKO_ALLOW_CROSS_ORIGIN', false),
  trustedProxyHops: longEnv('ANIMEKO_TRUSTED_PROXY_HOPS', 0, 0, 8),
  bangumiBases: bangumiBases.length ? bangumiBases : defaultBases,
  requestTimeoutMs: longEnv('ANIMEKO_REQUEST_TIMEOUT_MS', 20000, 500, 600000),
  bangumiTimeoutMs: longEnv('ANIMEKO_BANGUMI_TIMEOUT_MS', 45000, 500, 600000),
  proxyTimeoutMs: longEnv('ANIMEKO_PROXY_TIMEOUT_MS', 120000, 500, 3600000),
  subscriptionRefreshMs: longEnv('ANIMEKO_SUBSCRIPTION_REFRESH_MS', 21600000, 500, 604800000),
  subscriptionAutoImport: [...new Set([
    ...(strEnv('ANIMEKO_SUBSCRIPTION_AUTO_IMPORT') ?? '').split(','),
    ...(strEnv('ANIMEKO_SEED_SUBSCRIPTION_URL') ? [strEnv('ANIMEKO_SEED_SUBSCRIPTION_URL')!] : []),
  ].map((s) => s.trim()).filter(Boolean))],
  subscriptionAutoTiers: (strEnv('ANIMEKO_SUBSCRIPTION_AUTO_TIERS') ?? '')
    .split(',').map((s) => s.trim()).filter(Boolean),
  // 弹幕服务（弹弹 play /api/v2 协议兼容服务）
  danmakuApiBase: (strEnv('ANIMEKO_DANMAKU_API_BASE') ?? 'https://api.dandanplay.net').replace(/\/+$/, ''),
  danmakuAppId: strEnv('ANIMEKO_DANMAKU_APP_ID'),
  danmakuAppSecret: strEnv('ANIMEKO_DANMAKU_APP_SECRET'),
  version: '1.0.0',
} as const;

export const selectorsDir = () => path.join(config.dataDir, 'selectors');
export const userDataFile = () => path.join(config.dataDir, 'userdata.json');
export const bangumiChannelFile = () => path.join(config.dataDir, 'bangumi-channel.json');
export const subscriptionsFile = () => path.join(config.dataDir, 'subscriptions.json');

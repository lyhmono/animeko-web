// 进程内运行指标。
const startedAt = Date.now();

export const metrics = {
  requests: 0,
  proxyRequests: 0,
};

export function uptimeSeconds(): number {
  return Math.floor((Date.now() - startedAt) / 1000);
}

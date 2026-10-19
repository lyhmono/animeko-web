/**
 * Service Worker：只为两件事存在 —— 让 PWA 可安装、让二次打开更快。
 *
 * 缓存策略，每一条都对应一个具体的坑：
 *
 * 1. 只接管同源 GET。跨域（封面图床、源站）一律不碰：那些地址常带防盗链或
 *    签名，缓存下来的结果在签名过期后就是一张坏图，而且我们也没法判断。
 *
 * 2. /api/ 完全不拦截。里面有流式视频、Range 请求、带会话凭据的代理 ——
 *    过一遍缓存层既没有命中率（每个请求都带用户身份），又容易把 206 变成 200
 *    从而毁掉拖动进度条。
 *
 * 3. 导航请求 network-first：index.html 永远先问服务器。
 *    反过来（cache-first）会拿到旧 index.html，而它引用的 chunk 在重新部署后
 *    已经不存在 —— 本项目踩过这个白屏坑（见 ErrorBoundary 里那段注释）。
 *
 * 4. /assets/* 用 cache-first：文件名带内容哈希，内容变了名字就变，
 *    可以放心长期缓存（后端也对这些路径发了 immutable）。
 *
 * 5. **不调 skipWaiting**：新版本等所有旧页面关掉再接管。
 *    立即接管会让「还开着的老页面」去请求已经被删掉的旧 chunk，
 *    正好制造第 3 条要避免的那种白屏。
 */
const VERSION = 'v1'
const SHELL_CACHE = `animeko-shell-${VERSION}`
const ASSET_CACHE = `animeko-assets-${VERSION}`

self.addEventListener('install', (event) => {
  // 预缓存一份首页当离线兜底。cache: 'reload' 绕开 HTTP 缓存，
  // 保证装进 SW 缓存的是当下这份而不是浏览器里可能更旧的副本。
  event.waitUntil(
    caches
      .open(SHELL_CACHE)
      .then((cache) => cache.add(new Request('/', { cache: 'reload' })))
      // 离线/服务未起时安装失败不该让整个 SW 装不上：没有壳缓存也能正常工作
      .catch(() => {}),
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const keys = await caches.keys()
      await Promise.all(
        keys
          .filter((key) => key.startsWith('animeko-') && !key.endsWith(VERSION))
          .map((key) => caches.delete(key)),
      )
      // 首次安装后立刻接管当前页：缓存是全新的，没有「旧页面配旧资源」的问题
      await self.clients.claim()
    })(),
  )
})

self.addEventListener('fetch', (event) => {
  const req = event.request
  if (req.method !== 'GET') return

  const url = new URL(req.url)
  if (url.origin !== self.location.origin) return
  if (url.pathname.startsWith('/api/')) return

  if (req.mode === 'navigate') {
    event.respondWith(networkFirstNavigation(req))
    return
  }
  if (url.pathname.startsWith('/assets/')) {
    event.respondWith(cacheFirst(req, ASSET_CACHE))
    return
  }
  // 其余静态资源（图标、manifest、favicon）：先给缓存，后台顺手更新
  event.respondWith(staleWhileRevalidate(req, ASSET_CACHE))
})

async function networkFirstNavigation(req) {
  const cache = await caches.open(SHELL_CACHE)
  try {
    const fresh = await fetch(req)
    // 只存成功的 HTML：把 5xx 或登录页存成壳，下次离线打开就是错的
    if (fresh && fresh.ok) await cache.put('/', fresh.clone())
    return fresh
  } catch {
    const cached = await cache.match('/')
    if (cached) return cached
    return new Response('离线，且没有可用的页面缓存。连上网络后刷新即可。', {
      status: 503,
      headers: { 'Content-Type': 'text/plain; charset=utf-8' },
    })
  }
}

async function cacheFirst(req, cacheName) {
  const cache = await caches.open(cacheName)
  const hit = await cache.match(req)
  if (hit) return hit
  const fresh = await fetch(req)
  if (fresh && fresh.ok) await cache.put(req, fresh.clone())
  return fresh
}

async function staleWhileRevalidate(req, cacheName) {
  const cache = await caches.open(cacheName)
  const hit = await cache.match(req)
  const network = fetch(req)
    .then(async (fresh) => {
      if (fresh && fresh.ok) await cache.put(req, fresh.clone())
      return fresh
    })
    .catch(() => null)
  return hit || (await network) || new Response('', { status: 504 })
}

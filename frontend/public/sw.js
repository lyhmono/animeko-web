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
 *
 * 6. `/api/` 的判定必须**按段解码**，不能比字符串前缀。后端的路由与鉴权门禁都是
 *    按段解码匹配的（这正是之前修过的洞）：实测 `//api/data` 与 `/%61pi/data` 都会
 *    打到真接口并返回该用户的个人数据。而 `url.pathname.startsWith('/api/')` 对这两种
 *    写法都是 false → 会被下面的缓存逻辑接管 → `caches.put` 把带凭据的响应写进持久缓存
 *    （Cache API 不理会 `Cache-Control: no-store`），注销、换账号之后仍可能被缓存命中回放。
 */
/**
 * 缓存版本。改动缓存策略时**手动**改这里，activate 会清掉所有旧版本缓存。
 * v1→v2：修「`/api/` 用前缀判断」那个洞时**必须**升版本——只改代码不升版本，
 * 之前被误存进缓存的凭据响应会一直留在设备上（新的清理逻辑只删非当前版本）。
 * 注意：assets 缓存会随部署累积（新部署产生新哈希文件名，旧文件不再被引用
 * 但仍在缓存里，一次部署约 300KB）。这是刻意接受的取舍 —— 换来的是弱网下
 * 二次打开直接命中本地，而清理需要解析 index.html 才知道哪些还在用，
 * 复杂度和出错面都不划算。真需要腾空间时把这里 +1 即可全清。
 */
const VERSION = 'v2'
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
  if (isApiPath(url.pathname)) return
  // sw.js 自身不进缓存：浏览器的更新检查会绕过 SW，但页面里若有 fetch('/sw.js')
  // 就会命中缓存拿到旧副本，一点好处都没有
  if (url.pathname === '/sw.js') return

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

/**
 * 第一段解码后是不是 `api` —— 与后端门禁用同一条判据。
 *
 * 只比 `pathname.startsWith('/api/')` 会漏掉 `//api/data`（空段 + `//`）与
 * `/%61pi/data`（`a` 的百分号写法）：这两种都能命中真接口、返回带凭据的个人数据
 * （实测都 200）。漏判的后果不是多一次网络请求，而是**把凭据响应写进持久缓存**：
 * Cache API 不看 `Cache-Control: no-store`，注销换账号后旧缓存还可能被命中。
 * 认不出来的段（畸形百分号转义）按 api 处理：宁可少缓存，不可误存。
 */
function isApiPath(pathname) {
  for (const seg of pathname.split('/')) {
    if (seg === '') continue
    let decoded = seg
    try {
      decoded = decodeURIComponent(seg)
    } catch {
      return true
    }
    return decoded.toLowerCase() === 'api'
  }
  return false
}

/**
 * 只存明确允许存储的响应。
 *
 * 这里刻意**不**把 no-cache 当成不可缓存：它的含义是「可以存，但每次必须回源验证」，
 * 而导航请求本来就是 network-first。index.html 正是后端发 `no-cache`（入口文档不能
 * 被缓存住，否则重新部署后指向已删除的 chunk → 白屏），若在这里连它一起拒掉，
 * 离线壳就没得兜底了。真正不能落盘的是 no-store / private。
 */
function cacheable(resp) {
  if (!resp || !resp.ok) return false
  const cc = (resp.headers.get('cache-control') || '').toLowerCase()
  if (cc.includes('no-store') || cc.includes('private')) return false
  return true
}

async function networkFirstNavigation(req) {
  const cache = await caches.open(SHELL_CACHE)
  try {
    const fresh = await fetch(req)
    // 只存成功的 HTML：把 5xx 或登录页存成壳，下次离线打开就是错的
    if (cacheable(fresh)) await cache.put('/', fresh.clone())
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
  if (cacheable(fresh)) await cache.put(req, fresh.clone())
  return fresh
}

async function staleWhileRevalidate(req, cacheName) {
  const cache = await caches.open(cacheName)
  const hit = await cache.match(req)
  const network = fetch(req)
    .then(async (fresh) => {
      if (cacheable(fresh)) await cache.put(req, fresh.clone())
      return fresh
    })
    .catch(() => null)
  return hit || (await network) || new Response('', { status: 504 })
}

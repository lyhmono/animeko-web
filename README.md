# Animeko Web

[animeko](https://github.com/open-ani/animeko)（open-ani，100% Kotlin/Compose Multiplatform 的弹幕追番平台）的**服务端化实现**：把 animeko 的数据源采集、Bangumi 元数据、视频代理搬进服务端容器，浏览器负责界面与播放。**面向单人自托管**：打开网站先登录，收藏/进度/数据源都保存在服务器上。

**单容器部署，ARM64 原生支持**（播放在浏览器完成，服务端无原生依赖，x86_64 / arm64 同一份代码）。

> ⚠️ 本项目与 animeko 同样采用 **AGPL-3.0** 许可证；数据源抓取的视频内容版权归源站所有。请遵守所在地法律法规，仅用于个人学习使用，不要公网传播。

## 功能

| 模块 | 说明 |
|---|---|
| Bangumi 元数据 | 搜索 / 条目详情 / 剧集列表（开放 API，无需密钥） |
| Selector 数据源 | 可直接导入 animeko 社区配置（`factoryId: web-selector`，version 2，含 tier 为数字的社区格式）；支持 CSS / XPath / JsonPath 条目格式、线路分组、剧集集号正则。`factoryId` 只接受 `web-selector`：社区订阅里混装的 BT / PikPak 源会被明确拒绝，而不是被当成网页源存下来、搜索永远 0 结果。**注意**：与采集相关的字段全部生效，而 `tier`、`channelTiers`、`defaultResolution`、`defaultSubtitleLanguage`、`onlySupportsPlayers`、`selectMedia`、`searchCacheTtl` 这些属于 App 侧选择策略的字段会被接受但**忽略**（服务端不做画质/字幕/源优先级仲裁） |
| 登录门控 | 打开站点先进登录页，会话是服务端签发的 HttpOnly + SameSite=Strict Cookie（`<video>`、封面、hls.js 分片都能自动带上）；口令错误按 IP 限速 |
| 服务端个人数据 | 收藏 / 观看进度 / 最近观看 / 搜索历史存在 `ANIMEKO_DATA_DIR/userdata.json`，换设备或清浏览器缓存都不丢 |
| 聚合订阅导入 | 填一条订阅 URL（`{exportedMediaSourceDataList:{mediaSources:[…]}}`）先预览，再勾选要导入的几个源；也可粘贴订阅正文 |
| 视频代理 | Range 透传、服务端会话化的 UA/Referer/Cookie 注入、HLS(m3u8) 清单与分片重写（分片同样带防盗链凭据）、逐跳校验的重定向跟随、内网地址拦截 |
| 播放器 | hls.js（m3u8）+ 原生 mp4/webm；倍速、进度、音量、全屏；**手动地址播放**兜底 |
| 播放体验 | 键盘快捷键（空格/`K` 播放暂停、`←`/`→` ±5 秒、`J`/`L` ±10 秒、`↑`/`↓` 音量、`M` 静音、`F` 全屏）、**断点续播**、**自动连播**（下一集倒计时） |
| 个人数据 | 追番收藏、最近观看、搜索历史、每集观看进度 —— 全部存服务器的 `userdata.json`，**不写浏览器 localStorage**（只有主题这一个设备偏好留在本地，因为它必须在首屏渲染前读到） |
| 界面 | 登录页 + 暗色 / 亮色双主题、响应式布局，桌面与手机浏览器均可；页脚常驻「已保存 / 待保存 / 未同步」状态点 |

与 animeko App 的差异：App 用 WebView 拦截网络请求提取视频地址，服务端版改为抓播放页 HTML 后用 `matchVideo` 正则匹配（覆盖绝大多数源）；强依赖 JS 执行的源可用播放页「手动地址播放」兜底。

## 快速开始（Docker）

```bash
# 克隆后直接构建启动（x86_64 与 ARM64 均可）
docker compose up -d --build

# 打开浏览器
open http://localhost:8080
```

数据（数据源配置、收藏与观看进度）持久化在 `./data`，删除容器不丢失。

> 容器进程以非 root 用户 `app`（uid 10001）运行：入口脚本负责把挂载的 `./data` 属主改给它。
> 如果你在 compose/k8s 里显式指定了 `user:`/`runAsUser`，那段会跳过，卷权限由编排层负责。

### 别把 8080 直接裸露在公网

服务默认监听 `0.0.0.0`，而「能不能读到你的收藏和内网拓扑」取决于网络边界，不取决于代码。
三种安全姿势，按推荐顺序：

```bash
# A. 只绑本机，用 SSH 隧道看（最省心，口令都不用设）
#    compose 里改成 ports: - "127.0.0.1:8080:8080"，然后本地：
ssh -N -L 8080:127.0.0.1:8080 user@your-vps      # 浏览器开 http://localhost:8080

# B. 反向代理 + HTTPS：记得开 ANIMEKO_COOKIE_SECURE=true，
#    并且 ANIMEKO_AUTH_* 必须配上（否则登录页就是唯一的门）

# C. 只想临时试一下：配好 ANIMEKO_AUTH_USER/PASSWORD 再对外，
#    并预期会有扫描器来撞口令 —— 限速是 5 分钟 8 次，不是万能药
```

反代注意：`Origin` 与 `Host` 的同源判定依赖 `Host` 透传。如果你的反代改写了 `Host`
（例如固定成 `localhost:8080`），写接口会被误判成跨站并返回 `403 CROSS_SITE_REQUEST`；
此时正确做法是让反代透传原始 Host（nginx 的 `proxy_set_header Host $host;`），
`ANIMEKO_ALLOW_CROSS_ORIGIN=true` 是最后手段而不是常规配置。

> **想让它带登录页**（公网机器上应该这样）：编辑 `docker-compose.yml`，取消
> `ANIMEKO_AUTH_USER` / `ANIMEKO_AUTH_PASSWORD` 的注释并改成自己的口令，再
> `docker compose up -d`。配了凭据登录就自动开启，打开网站先进登录页；
> 不配则站点打开即用（服务默认监听 0.0.0.0，请自己确认它不被不可信网络访问）。

### ARM64 构建

镜像本身无架构相关代码，直接构建即可：

```bash
# ARM64 宿主（如 Apple Silicon 服务器 / 树莓派 / ARM 云主机）
docker compose up -d --build

# 或在 x86 机器上交叉构建（buildx）
docker buildx build --platform linux/arm64 -t animeko-web:arm64 --load .
docker run -d -p 8080:8080 -v ./data:/data animeko-web:arm64
```

### 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `ANIMEKO_HOST` | `0.0.0.0` | 监听地址 |
| `ANIMEKO_PORT` | `8080` | 服务端口 |
| `ANIMEKO_DATA_DIR` | `/data`（镜像内） | 数据目录：`selectors/` 数据源配置、`userdata.json` 个人数据 |
| `ANIMEKO_FRONTEND_DIR` | `/app/frontend/dist` | 前端构建产物目录 |
| `ANIMEKO_AUTH_USER` / `ANIMEKO_AUTH_PASSWORD` | — | 登录页的账号与口令；**配了就自动开启登录** |
| `ANIMEKO_AUTH_TOKEN` | — | 只配一个口令也行：登录页把它当密码（用户名任意），API 调用可用 `Authorization: Bearer` |
| `ANIMEKO_AUTH_ENABLED` | 自动 | 默认跟随凭据。显式写 `false` 可关掉登录页（前面已有反代鉴权时才需要）；**公网暴露时务必开启** |
| `ANIMEKO_COOKIE_SECURE` | `false` | 会话 Cookie 是否加 `Secure`。反代配好 HTTPS 后设为 `true`；在纯 HTTP 下设 `true` 会让浏览器丢弃 Cookie、登录页反复回弹 |
| `ANIMEKO_ALLOW_PRIVATE_TARGETS` | `false` | 是否允许代理/抓取内网地址（见下方安全说明） |
| `ANIMEKO_ALLOW_CROSS_ORIGIN` | `false` | 是否放开「写接口仅限同源」的 CSRF 检查（见下方安全说明） |
| `ANIMEKO_TRUSTED_PROXY_HOPS` | `0` | 服务前面有几层**你控制**的反代。0=直连（X-Forwarded-* 一律不信）；填 N 后限速按 XFF 从右数第 N 个真实客户端记账、同源判定改看 `X-Forwarded-Host`。层数要如实填：少了把代理 IP 当客户端（限速全员共享），多了把客户端伪造的 XFF 当真值 |
| `ANIMEKO_SEED_SUBSCRIPTION_URL` | 空 | **首次启动（仓库为空）时自动拉取导入的聚合订阅地址**，开箱即用不用手动导入。compose 默认已配社区订阅；拉取失败不阻塞启动，仓库仍为空则回退内置示例源 |
| `ANIMEKO_BANGUMI_BASE_URLS` | `https://api.bgm.tv,https://bgmapi.anibt.net` | Bangumi API 地址列表（逗号分隔，按顺序故障转移；官方域名在部分地区被墙，可改用社区镜像） |
| `ANIMEKO_REQUEST_TIMEOUT_MS` | `20000` | 元数据/抓取类出站请求超时 |
| `ANIMEKO_PROXY_TIMEOUT_MS` | `120000` | 视频代理的连接与**响应头**超时（body 阶段刻意不设时限，见下方安全说明） |

### 接口一览

「凭据」指会话 Cookie、`Authorization: Basic`、或 `Authorization: Bearer <token>` 三者之一
（浏览器子资源只能靠 Cookie，命令行用 `-u 账号:口令`）。未启用鉴权时所有接口都免凭据。

| 方法 | 路径 | 凭据 | 说明 |
|---|---|---|---|
| GET | `/api/health` | 免 | `{status, version, authEnabled, authenticated}`；`authenticated` **只反映会话 Cookie**（见下）。容器 `HEALTHCHECK` 依赖它，所以永远免鉴权 |
| POST | `/api/session/login` | 免 | `{username, password}` → `Set-Cookie: animeko_session=…`。按 IP 限速，超限 `429` + `Retry-After` |
| POST | `/api/session/logout` | 需要 | 服务端立即作废该会话号（只清浏览器 Cookie 是不够的） |
| GET | `/api/data` | 需要 | 个人数据整份文档 + `ETag`；无文档时 `{}` |
| PUT | `/api/data` | 需要 | 整份覆盖。带 `If-Match` 时版本不符回 `409 DATA_STALE`；请求体 ≤1MB，文档 ≤512KB |
| GET | `/api/bangumi/search?q=` | 需要 | Bangumi 搜索 |
| GET | `/api/bangumi/subjects/{id}` | 需要 | 条目详情；已删除的条目回 `404`（不是 502） |
| GET | `/api/selector/sources` | 需要 | 已导入的数据源列表 |
| POST | `/api/selector/sources` | 需要 | 导入单个源：`{factoryId,version,arguments}` 包装或裸 `arguments` |
| POST | `/api/selector/sources/{name}/enable` | 需要 | `{"enabled":true/false}` —— 必须是显式布尔值，缺字段或类型不对回 `400` |
| DELETE | `/api/selector/sources/{name}` | 需要 | 删除；名字消毒后撞车的源不会被误删 |
| POST | `/api/selector/subscriptions/preview` | 需要 | `{url}` 或 `{text}` → 候选源列表 + 被跳过的条目及原因（订阅 ≤4MB，逐跳校验重定向） |
| POST | `/api/selector/subscriptions/import` | 需要 | `{url 或 text, names:[…]}` → `{added, updated, failed, rejected}`；只导入勾选的 |
| POST | `/api/selector/sources/{name}/search` | 需要 | `{keyword}` → 该源的搜索结果 |
| POST | `/api/selector/sources/{name}/episodes` | 需要 | `{subjectUrl}` → 线路与剧集 |
| POST | `/api/selector/sources/{name}/video` | 需要 | `{playUrl}` → `{url, proxyPath}`；`proxyPath` 只含随机 `sid`，源站凭据不出服务端 |
| GET | `/api/proxy?url=&sid=` | 需要 | 视频/HLS 代理：Range 透传、清单与分片重写、逐跳校验。会话过期 `410 PROXY_SESSION_EXPIRED`，主机不匹配 `403 PROXY_SESSION_TARGET_MISMATCH` |
| GET | `/*` | 免 | 前端静态资源与 SPA 回退（登录页本身要能打开） |

其余状态码：`403 CROSS_SITE_REQUEST`（写操作被同源检查拦下）、`403`（目标被 SSRF 规则拒绝）、
`400`（请求体超上限 / JSON 不合法 / 数据源配置无效）、`429`（凭据校验失败次数超限）、
`502`（上游失败）、`500 INTERNAL_ERROR`（兜底不回显细节，完整堆栈只在服务端日志）。

### 鉴权与代理的安全说明

- **默认就是登录状态**：只要配了 `ANIMEKO_AUTH_USER`+`ANIMEKO_AUTH_PASSWORD`（或 `ANIMEKO_AUTH_TOKEN`），
  登录鉴权自动开启，打开网站先进登录页。想彻底关掉（例如前面已有反代鉴权）显式写 `ANIMEKO_AUTH_ENABLED=false`。
- 登录成功后服务端签发会话 Cookie（`HttpOnly`、`SameSite=Strict`、`Path=/`、30 天空闲即失效、使用中自动续期）。
  用 Cookie 而不是让前端带 `Authorization` 头，是因为 `<video>`、`<img>`、hls.js 的分片请求
  都加不了自定义头 —— 只有 Cookie 会被浏览器自动带到这些子资源上。
  未认证请求同时下发 **Basic 质询**，命令行与不带 Cookie 的场景仍可用：
  `curl -u 账号:口令`，或只配了 token 时**用户名任意、密码填 token**；
  API 客户端也可以用 `Authorization: Bearer <ANIMEKO_AUTH_TOKEN>`。
- 凭据校验失败按客户端 IP 限速：**5 分钟内 8 次**，超过后连输对口令也返回 `429`（带 `Retry-After`）。
  **直连部署（默认）按 `remoteHost` 记账**：X-Forwarded-For 完全由客户端提供，信它就能靠
  轮换假 IP 无限重置计数器（有回归用例）。**反代部署必须设 `ANIMEKO_TRUSTED_PROXY_HOPS=<层数>`**：
  否则所有请求的 `remoteHost` 都是代理自己，一个客户端输错到限额，其他所有人跟着被锁 5 分钟。
  填了层数后取 XFF 从右数第 N 个地址（每跳可信代理在右端追加一个「这一跳看到的地址」）；
  链长短于层数、或取到的值不是 IP 形态时退回 `remoteHost`，不让伪造值进键空间。
  限速覆盖**所有**能验证凭据的路径，不只是登录接口 —— 只要还剩一个免限流的接口能回答
  「这组 Basic/Bearer 对不对」，它就是一台在线口令字典的 yes/no 预言机（实测确实被这样绕过过）。
  会话 Cookie 不参与计数：那是 64 位十六进制随机数，猜不中没有意义，而人是会输错口令的。
- 口令比较走 `MessageDigest.isEqual`，不做短路比较。
- `/api/health` 的 `authenticated` **只反映会话 Cookie**，不承认 `Authorization` 头：
  这个接口免鉴权，一旦承认头凭据就变成了上面那台预言机。用纯 Basic/Bearer 的脚本调用者
  会看到 `authenticated: false`，这不影响它们调用真正的接口。
- 未认证时是否下发 Basic 质询，取决于 `Sec-Fetch-Mode`：`navigate`（地址栏直接访问）与
  不带该头的客户端（curl）会收到质询；XHR 与媒体子请求**不会** —— 否则 hls.js 的分片一 401，
  浏览器就弹出原生口令框盖住前端的登录页。前端登录页由 `fetch` 收到 401 时广播事件切回去，
  播放器的分片 401 也会走同一条路径。
- 纯 HTTP 部署别把 `ANIMEKO_COOKIE_SECURE` 打开：浏览器会直接丢弃 `Secure` Cookie，
  表现为「登录页反复回弹」。反代配好 HTTPS 之后再开。
- `/api/health` 不参与鉴权：容器 `HEALTHCHECK` 和前端登录页都需要免凭据读到它，
  否则开启鉴权会让容器永久显示 unhealthy。该接口只返回状态、版本与 `authEnabled`/`authenticated` 两个布尔开关。
- 个人数据（`/api/data`）同样在鉴权之后。收藏、进度、历史存在服务器的 `userdata.json`，
  **不再写浏览器 localStorage**（只有主题例外，它必须在首屏渲染前读到且不涉及任何个人内容）。
  前端是「写内存 + 800ms 去抖回传」，所以底部有「已保存/保存中/保存失败」状态点。
- 解析出的源站 `UA/Referer/Cookie` **只留在服务端**：`/api/.../video` 返回一个
  `/api/proxy?url=…&sid=…` 地址，`sid` 是 128 位随机十六进制、6 小时空闲即失效、
  使用中自动续期。HLS 清单改写出的每个分片/密钥地址同样只带 `sid`。
  旧做法是把凭据拼进查询参数，而一张清单会把它复制到成百上千个分片 URL 上，
  这些地址会进浏览器历史与访问日志。凭据因此不再下发给浏览器。
  会话**绑定目标主机**：sid 只适用于解析出来的那台主机，以及清单内容里出现过的
  分片/密钥主机（由服务端改写清单时登记，客户端无法自行扩大）。缺了这层绑定，
  一个泄漏的 sid 就等于「用受害者的 Cookie 去请求攻击者指定的任意地址」。
  会话过期返回 `410 PROXY_SESSION_EXPIRED`、主机不匹配返回
  `403 PROXY_SESSION_TARGET_MISMATCH`（都与「源站拒绝」可区分）；播放器遇到
  这两种情况会**自动重新解析一次**，失败才报错。
  `/api/proxy` 不再接受 `ua`/`referer`/`cookie` 参数，顺带关掉了
  「任意 URL + 任意请求头」这个组合面。
- 两张会话表（播放代理的 `sid`、登录会话）都是**进程内内存**，因此只支持**单副本**部署：
  多副本各持一份、请求被负载均衡轮转时，分片会随机 403/410、登录会随机掉线。
  要横向扩展需把它们换成共享存储（如 Redis）。容器重启后需要重新登录。
- `/api/data` 与数据源写入有 **1MB 请求体上限**，个人数据文档本身有 512KB 上限；
  超限返回 `400`，且请求体是边读边计数的，不会先把整个请求读进堆里。
- `/api/data` 是**条件写**：`GET` 返回 `ETag`（内容指纹），`PUT` 带上同一个 `If-Match` 时，
  版本已经变了会返回 `409 DATA_STALE` 而不是静默覆盖。前端据此重读服务器文档、
  只把自己改过的键贴回去再写一次（`progress` 再细一层到剧集），所以两个标签页/两台设备
  不会互相吃掉对方的收藏。不带 `If-Match`（curl、旧客户端）则按明确意图直接覆盖。
- 前端数据层在**没读到服务器文档时禁止回写**（读取失败保持「未同步」状态并可重试）。
  这不是保守，是必须的：否则「读失败 → 文档视作空 → 一次收藏点击」就会把
  `{favorites:[…]}` 整份覆盖上去，收藏、进度、历史当场清零。
- 所有出站目标（`/api/proxy`、数据源页面、订阅地址）统一过 `UrlGuard`：拒绝
  127.0.0.0/8、10/192.168/172.16 私有段、169.254.169.254 元数据地址、`*.localhost`/`*.internal`
  等，并且**重定向的每一跳都重新校验**（只校验入口地址时，一条 302 就能把服务变成内网跳板）。
  判定会真的解析 DNS（「公网域名 → 内网 IP」拦得住；解析不出来则放行，交给真实请求自然失败）。
  确实要代理局域网内 NAS 时，把 `ANIMEKO_ALLOW_PRIVATE_TARGETS=true` 打开。
- 被拒绝时对外只回 403 与一句通用文案，「主机名 → 内网 IP」这类拒绝原因只进服务端日志：
  那对任何能连到这台机器的人来说都是网络拓扑泄露。
- `/api/*` 的**写操作只允许同源**（对比 `Origin` 与 `Host`）：这一层不能因为「有鉴权」就省掉——
  浏览器对 Basic 凭据是按域缓存、会自动附带的，而 `SameSite` 只约束 Cookie。
  用户在质询弹窗里输过一次口令之后，恶意页面的跨站 POST 就会带着那份凭据打进来，
  此时唯一还能识别「这是跨站」的信号是 `Origin`（`text/plain` 属简单请求，不会触发预检）。
  被拦时返回 `403 CROSS_SITE_REQUEST`。反向代理若改写了 `Host` 造成误判：
  设 `ANIMEKO_TRUSTED_PROXY_HOPS=<层数>` 后判定改看 `X-Forwarded-Host`（浏览器看到的原始主机），
  或者确认反代保持 `proxy_set_header Host $host`；`ANIMEKO_ALLOW_CROSS_ORIGIN=true` 是最后手段
  （此时请自行保证服务不被不可信网络访问）。
  命令行/API 客户端不带 `Origin`，不受影响。
- 鉴权豁免判定走**归一化后的路径段精确匹配**（丢空段、逐段 URL 解码），不是 URI 字符串前缀。
  原因：Ktor 的路由按段匹配，`//api/data`、`/%61pi/data` 都会打到真接口，
  而 `request.local.uri` 与 `request.path()` 拿到的原串都不以 `/api/` 开头 ——
  用前缀判断时这两个写法能无凭据读走数据源列表、覆写个人数据（已修，并有回归用例）。
- 视频代理的 **body 阶段不设超时**，这是权衡后的结论而不是遗漏。两种直观做法都验证过：
  按总时长切断会砍掉健康的慢速长传输（实测一部 700KB 的慢流被 4 秒期限切断后，
  声明了 `Content-Length` 的响应既不补字节也不关闭，客户端等满 90 秒仍在等 ——
  播放器表现成卡死，比不切断更糟）；按空闲间隔切断同样不成立，因为这条链路是同步
  `copyTo`，两次 read 之间耗的主要是**客户端背压**的等待时间，误伤的是边看边缓冲的正常观众，
  而真正危险的「read 自己永久卡住」在阻塞读返回之前根本没有检查点。
  要正确解决需换成非阻塞写出（或带超时的异步 read 包裹），那是另一套机制。
  现在兜住它的是：连接与响应头超时、上游 EOF/断连时 `copyTo` 自然返回、Netty 的空闲连接处理。
  回归用例见 `ProxyRouteTest` 的「a slow but live upstream is forwarded in full」。
- 静态资源缓存：`/assets/*`（带内容哈希）为 `max-age=31536000, immutable`，
  `index.html` 为 `no-cache`；缺失的资源请求返回 **404** 而不是回退 `index.html`——
  否则重新部署后，浏览器会把 HTML 当 ES module 执行，且 `React.lazy` 会永久记住这次失败。

> Bangumi 说明：官方 `api.bgm.tv` 在部分地区不可达。客户端按列表顺序尝试，首个可用的会被记住。
> 如果部署环境访问官方域名会长时间超时，建议直接把 `ANIMEKO_BANGUMI_BASE_URLS` 设为可用的镜像
> （如 `https://bgmapi.anibt.net`），避免每次冷启动等待官方超时。
> 旧版单值变量 `ANIMEKO_BANGUMI_BASE_URL` 仍作为兼容别名生效（优先于默认列表，被 `..._URLS` 覆盖）。

## 使用流程

1. **导入数据源**：首次启动会自动拉取默认订阅（compose 里 `ANIMEKO_SEED_SUBSCRIPTION_URL`，默认社区 css1.json）并导入全部在线源，打开就能用。之后想增删，进入「数据源」页粘贴 animeko 的 Selector 数据源 JSON（与 animeko App 内「设置 → 数据源管理」添加的配置格式一致），或再用聚合订阅入口挑源。
2. **搜索**：首页搜索 → Bangumi 条目 + 各数据源结果。
3. **播放**：
   - Bangumi 条目页 → 剧集「播放」→ 选择数据源「自动匹配」（或「手动」）→ 自动在数据源中匹配番剧与集数；
   - 或数据源搜索结果 →「剧集」→ 选线路播放；
   - 解析失败时在播放页底部「手动地址播放」粘贴 m3u8/mp4 地址。
4. **聚合订阅**：数据源页填一条社区订阅地址（如 `https://sub.creamycake.org/v1/css1.json`）→「拉取订阅」→ 列表里能看到每个源的名字、分级、站点主机名与是否已存在 → 勾选后导入。整包导入的坏处是每次搜索都要把打不开的站点各试一遍，所以默认只勾选「本机还没有」的那些。订阅站挂了可以展开「贴订阅正文」直接粘 JSON。预览时会记录内容指纹，导入时若订阅已被更新过会提示重新预览，避免按一份和刚看到的不一样的清单导入。
5. **登录**：首次打开是登录页，输入账号口令后由服务端签发会话 Cookie；退出登录会让会话在服务端立即作废。
6. **追番与连播**：条目页点「收藏」加入追番列表（导航栏「收藏」查看）；从剧集列表进入播放时会自动带上下一集，当前集播放结束后倒计时自动连播。
7. **播放快捷键**：`空格`/`K` 播放暂停、`←`/`→` ±5 秒、`J`/`L` ±10 秒、`↑`/`↓` 音量、`M` 静音、`F` 全屏。播放进度自动记忆（写在服务器），下次进入同一剧集时可「继续播放」。
8. **保存状态**：页面底部有一个「已保存 / 保存中 / 保存失败」的小圆点，个人数据是写内存后去抖回传服务器的，失败时鼠标悬停能看到原因。

### 数据源配置格式（animeko 兼容）

```json
{
  "factoryId": "web-selector",
  "version": 2,
  "arguments": {
    "name": "我的数据源",
    "description": "说明",
    "searchConfig": {
      "searchUrl": "https://example.com/search?keyword={keyword}",
      "rawBaseUrl": "https://example.com",
      "userAgent": "Mozilla/5.0 ...",
      "subjectFormatId": "a",
      "selectorSubjectFormatA": { "selectLists": "div.video-info-header > a" },
      "channelFormatId": "no-channel",
      "selectorChannelFormatNoChannel": {
        "selectEpisodes": "#glist-1 a",
        "matchEpisodeSortFromName": "第\\s*(?<ep>.+)\\s*[话集]"
      },
      "matchVideo": {
        "enableNestedUrl": true,
        "matchVideoUrl": "(^http(s)?:\\/\\/.*((\\.mp4)|(\\.mkv)|(m3u8)).*)|(akamaized)"
      }
    }
  }
}
```

### 社区数据源哪里找

上游 Animeko 有社区维护的聚合数据源仓库 **[creamycake-anime/animeko-subs](https://github.com/creamycake-anime/animeko-subs)**
（旧名 `ani-subs`），本项目已用其订阅文件实测：**19 个在线源全部可直接导入**。

- 单个源文件：仓库里 `subs/web/t0/` ～ `subs/web/t4/` 下的 `<数据源名>.json`（t0 质量最高），
  整段复制后到「数据源」页粘贴导入即可；BT 源在 `subs/bt/`，**本项目不支持 BT 源**。
- 聚合订阅：`https://sub.creamycake.org/v1/css1.json`（在线）与 `v1/bt1.json`（BT，本项目不支持 BT 源）。
  **直接把订阅地址填进「数据源 → 聚合订阅导入」就行**，不用手工拆包：服务端会拉取
  `{exportedMediaSourceDataList:{mediaSources:[…]}}`，列出每个源的名字、`tier`、站点主机名与
  「本机已存在」标记，勾选后再导入；结构不完整（没有 `searchUrl`）的条目会在预览里单独列出并说明原因。
  订阅站打不开时，同一张卡片可以展开「贴订阅正文」直接粘 JSON。命令行等价写法：
  ```bash
  # 预览
  curl -X POST -H 'Content-Type: application/json' -u animeko:口令 \
    -d '{"url":"https://sub.creamycake.org/v1/css1.json"}' \
    http://127.0.0.1:8080/api/selector/subscriptions/preview
  # 只导入勾选的两个
  curl -X POST -H 'Content-Type: application/json' -u animeko:口令 \
    -d '{"url":"https://sub.creamycake.org/v1/css1.json","names":["稀饭动漫","嘀嗒影视"]}' \
    http://127.0.0.1:8080/api/selector/subscriptions/import
  ```
  之所以不整包导入：一次塞进几十个源，实际可用的往往只有几个，剩下的会让每次搜索都多打一遍不可达的站点。
  同名再导入会**覆盖**本机配置（返回里区分 `added` / `updated`），所以改过配置的手动源请别勾它。
- 社区源里的 `arguments.tier` / `channelTiers` 是数字（本项目内置示例里是字符串 `"Fallback"`），
  两种写法都能解析；本服务不做源优先级/画质/字幕仲裁，这些字段仅保留不生效。
- 数据源名多为中文：**必须以 UTF-8 locale 运行**（镜像内已 `ENV LANG=C.UTF-8`）。
  裸 `java -jar` / `installDist` 启动时如果环境里没有 `LANG`，JVM 的 `sun.jnu.encoding` 会退化成
  `ANSI_X3.4-1968`，中文源名连文件名都写不出来；此时服务启动日志会直接打印这条结论，
  导入接口返回的也是「请设置 LANG=C.UTF-8」而不是原始的 `Malformed input or input contains unmappable characters`。
  ```bash
  LANG=C.UTF-8 LC_ALL=C.UTF-8 ANIMEKO_DATA_DIR=./data ./build/install/animeko-web/bin/animeko-web
  ```

`subjectFormatId` 支持 `a`（CSS 选 `<a>`）、`indexed`（名字/链接两条 CSS）、`json-path-indexed`（JsonPath）；
`channelFormatId` 支持 `no-channel`、`index-grouped`（线路分组）。字段语义与 animeko 源码一一对应。

## 架构

```
┌──────────────────────── ARM64 Docker 容器 ────────────────────────┐
│  浏览器 ──► Ktor 服务（单端口 8080）                               │
│    /api/bangumi/*     Bangumi 开放 API                             │
│    /api/selector/*    Selector 引擎（Jsoup + JsonPath + 正则）     │
│    /api/session/*     登录 / 注销（会话 Cookie）                   │
│    /api/data          个人数据（收藏 / 进度 / 历史）                │
│    /api/proxy         视频/HLS 代理（Range 透传 + 清单重写）        │
│    /*                 前端静态资源（React + hls.js）                │
│  卷 /data：selectors/ 数据源配置 + userdata.json 个人数据           │
└───────────────────────────────────────────────────────────────────┘
```

## 本地开发

```bash
# 后端（Kotlin 2.4 / Ktor 3.1 / JDK 17+ 编译、17 字节码）
cd backend && ./gradlew installDist
LANG=C.UTF-8 LC_ALL=C.UTF-8 ANIMEKO_FRONTEND_DIR=../frontend/dist \
  ./build/install/animeko-web/bin/animeko-web

# 前端（React 19 + Vite 7）
cd frontend && NODE_ENV=development npm install && npm run dev   # http://localhost:5173，/api 代理到 8080
```

两个坑都踩过，写下来省时间：

- **`npm install` 必须显式 `NODE_ENV=development`**。环境变量里带着 `NODE_ENV=production`
  时 npm 会跳过 devDependencies，`vite`/`typescript` 装不上，`npm run build` 报「command not found」，
  而 `npm install` 本身是成功退出 0 的。
- **起服务要带 UTF-8 locale**（原因见上面「社区数据源哪里找」）。

单元测试：`cd backend && ./gradlew test` —— 150+ 个用例，覆盖 JsonPath / Selector 引擎与数据源仓库 /
聚合订阅（含逐跳重定向、订阅内重名、坏条目单独失败、预览指纹防内容漂移）/ HLS 重写 /
代理会话绑定与过期 / 登录会话与限速（含 XFF 伪造与可信代理层数）/ 凭据预言机 /
路径写法绕过 / 个人数据落盘与条件写 / SSRF 拦截不回显内网地址 / 候选 URL 尾缀净化 /
停用源服务端拒绝 / API 路由与静态托管缓存策略 / Bangumi 客户端。要求 0 编译警告。
GitHub Actions（`.github/workflows/ci.yml`）在 push/PR 上自动跑后端测试与前端 `tsc + vite build`。

端到端冒烟（对**真实二进制**跑，不是测试桩）：`bash scripts/live-smoke.sh` —— 12 节 71 项，
含登录门控、服务端个人数据往返与 ETag/409、真实订阅站预览导入、静态缓存策略、
注销后会话不可复用、限速、`--path-as-is` 的绕过探针、以及 LANG 缺失时的自检结论。
它需要 `./gradlew installDist` 与 `cd frontend && npm run build` 的产物；
**改完后端代码后一定要重新 installDist**，否则脚本跑的是旧 jar（这个坑真实存在过：
单元测试全绿、活体测试全红，因为测试客户端把 `//api/...` 归一化了，只有真实 Netty 才暴露问题）。

## 目录结构

```
animeko-web/
├── Dockerfile              # 三阶段：node 前端 → gradle 后端 → JRE 运行（单镜像，含 ENV LANG=C.UTF-8）
├── docker-entrypoint.sh     # 入口：root 起来只为给 /data 改属主，随后 gosu 降权到 app 用户
├── docker-compose.yml      # 凭据与两个安全开关都有注释
├── .gitattributes           # 锁 LF：*.sh / Dockerfile / *.yml（否则 autocrlf 会把它们转成 CRLF，容器与 build 全挂）
├── .github/workflows/ci.yml # push/PR 自动跑后端测试 + 前端 tsc/build
├── scripts/live-smoke.sh   # 端到端冒烟（真实进程 + 真实订阅站 + 攻击探针）
├── backend/                # Kotlin + Ktor
│   └── src/main/kotlin/com/animeko/web/
│       ├── auth/           # 登录会话与凭据限速（进程内内存表，滑动 TTL，有容量上界）
│       ├── bangumi/        # Bangumi 客户端（多地址故障转移）
│       ├── data/           # 个人数据文档（原子写、.bak、ETag 内容指纹）
│       ├── http/           # Fetcher（含边读边限）与 UrlGuard（SSRF）
│       ├── proxy/          # HLS 清单重写与播放会话（绑定目标主机）
│       ├── selector/       # animeko 兼容的 Selector 引擎、仓库与聚合订阅
│       └── api/            # REST 路由 + 鉴权/CSRF 门控 + 静态托管
└── frontend/               # React + Vite + TypeScript + hls.js
    └── src/
        ├── lib/storage.ts  # 个人数据：内存文档 + 去抖条件写 + 冲突合并
        ├── lib/auth.ts     # 会话探测/登录/注销与失效广播
        └── pages/          # 登录页在 gates 之前，其余路由懒加载
```

> `.gitignore` 里的运行时数据目录必须写成 `/data/`（锚在仓库根）。
> 裸写 `data/` 会把 `backend/src/main/kotlin/com/animeko/web/data/` 这个**源码包**一起忽略掉 ——
> 后果是 `UserDataStore.kt` 永远进不了提交，别人克隆下来直接编译不过。

## 路线图

- [x] Bangumi 元数据 / Selector 引擎 / HLS 代理 / 手动地址播放
- [x] 登录门控 + 服务端会话 + 个人数据上服务器
- [x] 聚合订阅预览与挑源导入
- [ ] BT / PikPak 数据源（animeko 的 bt 模块）
- [ ] RSS 数据源（animeko 的 RssMediaSource）
- [x] 断点续播与播放记忆
- [ ] 与 Bangumi 账号双向同步收藏（当前只存自己服务器）
- [ ] 多账号（当前是单共享账号，凭据来自环境变量）

## 许可证

本项目以 [AGPL-3.0](LICENSE) 授权（继承自 animeko）。第三方组件版权归其作者所有
（Ktor / kotlinx.serialization / Jsoup / React / hls.js 等，均为其各自许可证）。

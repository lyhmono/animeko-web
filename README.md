# Animeko Web

[animeko](https://github.com/open-ani/animeko)（open-ani，100% Kotlin/Compose Multiplatform 的弹幕追番平台）的**服务端化实现**：把 animeko 的数据源采集、Bangumi 元数据、视频代理搬进服务端容器，浏览器负责界面与播放。**面向单人自托管**：打开网站先登录，收藏/进度/数据源都保存在服务器上。

**单容器部署，ARM64 原生支持**（播放在浏览器完成，服务端无原生依赖，x86_64 / arm64 同一份代码）。

> ⚠️ 本项目与 animeko 同样采用 **AGPL-3.0** 许可证；数据源抓取的视频内容版权归源站所有。请遵守所在地法律法规，仅用于个人学习使用，不要公网传播。

## 功能

| 模块 | 说明 |
|---|---|
| Bangumi 元数据 | 搜索 / 条目详情 / 剧集列表（开放 API，无需密钥） |
| Selector 数据源 | **一键检查可用性**（对每个启用源跑一次宽泛搜索，死源当场标出 —— 死源留在链路里会让每次搜索都等它超时）；可直接导入 animeko 社区配置（`factoryId: web-selector`，version 2，含 tier 为数字的社区格式）；支持 CSS / XPath / JsonPath 条目格式、线路分组、剧集集号正则，并从条目的 `<img>`（含 `data-src` 懒加载属性）与 JsonPath 字段里提取**封面图**。`factoryId` 只接受 `web-selector`：社区订阅里混装的 BT / PikPak 源会被明确拒绝，而不是被当成网页源存下来、搜索永远 0 结果。**注意**：与采集相关的字段全部生效，而 `tier`、`channelTiers`、`defaultResolution`、`defaultSubtitleLanguage`、`onlySupportsPlayers`、`selectMedia`、`searchCacheTtl` 这些属于 App 侧选择策略的字段会被接受但**忽略**（服务端不做画质/字幕/源优先级仲裁） |
| 登录门控 | 打开站点先进登录页，会话是服务端签发的 HttpOnly + SameSite=Strict Cookie（`<video>`、封面、hls.js 分片都能自动带上）；口令错误按 IP 限速 |
| 服务端个人数据 | 收藏 / 观看进度 / 最近观看 / 搜索历史存在 `ANIMEKO_DATA_DIR/userdata.json`，换设备或清浏览器缓存都不丢 |
| 聚合订阅导入 | 填一条订阅 URL（`{exportedMediaSourceDataList:{mediaSources:[…]}}`）先预览，再勾选要导入的几个源；也可粘贴订阅正文。想彻底免去手动勾选，用 `ANIMEKO_SUBSCRIPTION_AUTO_IMPORT` 开机自动导入（见环境变量） |
| 视频代理 | Range 透传、服务端会话化的 UA/Referer/Cookie 注入、HLS(m3u8) 清单与分片重写（分片同样带防盗链凭据）、逐跳校验的重定向跟随、内网地址拦截 |
| 播放器 | hls.js（m3u8）+ 原生 mp4/webm；**记住音量/静音/倍速**（存浏览器本地：这些是设备属性，同步到服务器会让手机与台式机互相打架）；**影院化控制层**（播放中自动隐藏、缓冲进度层、缓冲转圈）、**触屏手势**（左右滑进度、左半屏亮度、右半屏音量）、**双击 ±10 秒**、倍速菜单、画中画、全屏；**手动地址播放**兜底 |
| 播放体验 | 键盘快捷键（空格/`K` 播放暂停、`←`/`→` ±5 秒、`J`/`L` ±10 秒、`↑`/`↓` 音量、`M` 静音、`F` 全屏；触屏手势见播放页快捷键卡片）、**断点续播**、**自动连播**（下一集倒计时）|
| 数据备份 | 设置页**一键导出 / 导入**个人数据（收藏、条目进度、每集进度、搜索历史、最近观看）。导入是**合并**不是覆盖：按 id / 播放地址去重、进度取更新的一条、条目进度取更大集号 —— 选错文件也不会把现有数据冲掉 |
| 个人数据 | 追番收藏（**分想看 / 在看 / 看过 / 搁置**四档，可在收藏页直接改）、**数据源收藏**（没有 Bangumi 条目的源站作品也能收）、最近观看、搜索历史、每集观看进度、**条目级「看到第几话」** —— 全部存服务器的 `userdata.json`，**不写浏览器 localStorage**（只有主题与播放器音量/倍速这类设备偏好留在本地：它们必须在首屏或播放前读到，且换设备本就该各自独立） |
| PWA | **可安装到主屏幕**（manifest + Service Worker，standalone 全屏、无地址栏）；手机端导航改为**底部 tab bar**（5 项，带安全区适配，播放页自动隐藏）。SW 策略：导航请求 network-first、`/assets/*`（带内容哈希）cache-first、`/api/*` 完全不拦截；**不调 skipWaiting**，新版本等旧页面关掉再接管 —— 立即接管会让老页面去请求已删除的旧 chunk。**注意**：Service Worker 只在 HTTPS 或 `localhost` 下生效；用纯 HTTP 访问内网 IP 时 PWA 装不上（页面本身照常工作，注册失败是静默的）。反代配好证书后把 `ANIMEKO_COOKIE_SECURE=true` 一起设上 |
| 界面 | 登录页 + 暗色 / 亮色双主题（「午夜影院」风格：深黑放映厅 / 暖白画报两套底色，琥珀金 accent，衬线标题）、响应式布局，桌面与手机浏览器均可；**首页继续观看**（上次那集还剩几分钟，一键续播）、**首页每日放送**（Bangumi 放送表按星期分组，可「只看我追的」并标出已收藏，镜像不支持该端点时自动隐藏整块）、**发现页排行榜**（Bangumi 动画评分榜，分页加载）；**顶栏常驻搜索框**（在任何页面都能改关键词，不必回首页）；搜索结果按**海报卡片**排布（源站结果同样带封面），**结果逐个回填**（不被最慢的死源拖着，见下方说明）；**返回搜索页不重搜**（每个关键词的结果在会话内记忆，标题旁可手动刷新）；页脚常驻「已保存 / 待保存 / 未同步」状态点。封面直连失败时自动改走 `/api/proxy` 回退，避免源站防盗链导致一片灰图；两条路都不通时落到统一的占位块而不是留个空洞 |
| Bangumi 通道 | 设置页可选**自动 / 仅官方 / 仅镜像 / 自定义**（可填自建中转），带逐地址连通性探测。持久化在 `data/bangumi-channel.json`，改完立即生效无需重启。官方与镜像能力不对等：每日放送只有官方有，境外 VPS 上镜像通常更快 —— 交给站长按自己的网络选。自定义地址**只接受公网 http/https**（内网/保留地址会被拒，与 proxy、订阅同一条规则；局域网中转需设 `ANIMEKO_ALLOW_PRIVATE_TARGETS=true`） |

与 animeko App 的差异：App 用 WebView 拦截网络请求提取视频地址，服务端版改为抓播放页 HTML，
先用数据源自带的 `matchVideo` 正则匹配，**配置没命中时再按通用视频后缀**（`.m3u8`/`.mp4`/`.mkv`/`.flv`）
兜底认一次。加上这一步是因为社区配置里的 `matchVideoUrl` 往往是一份 **CDN 域名白名单**，
而源站换 CDN 比改配置勤得多 —— 只按配置匹配就会出现「页面里明明写着 `index.m3u8`，
却报 NO_VIDEO_URL_FOUND」（实测订阅里就有这种源，加兜底后立刻可播）。走兜底会记一条 INFO 日志，
方便你回头更新那份配置。

仍有一类源服务端拿不到地址，报错同样是 NO_VIDEO_URL_FOUND，但**不是本项目的问题**：
`player_aaaa.encrypt` 为 `2/3` 的源，地址是站点 JS 用密钥解出来的密文
（实测 `encrypt=3` 那串 hex→base64 之后是 AES 密文，整个页面里没有一处明文 m3u8/mp4）；
以及地址要前端调 JSON 接口才拿到的站。这两类只有浏览器执行 JS 后才看得到地址，
服务端无法还原（逐个逆向等于和站点对抗）。用播放页的「手动地址播放」兜底：
浏览器 F12 → Network → 过滤 `m3u8`，把那条地址粘进去即可。

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

**只能挂在域名/端口的根路径下。** 构建产物用的是根绝对路径（`/assets/…`、`/favicon.svg`），
后端发给播放器的代理地址与 HLS 清单改写出的分片地址也都是 `/api/proxy?…` 这种根绝对形式。
所以 `nginx` 里写成 `location /animeko/ { proxy_pass http://127.0.0.1:8080/; }` 这种
**子路径部署会得到一个白屏**：浏览器去要 `https://你的域名/assets/index-xxx.js`，
而那个路径下什么都没有。要放在子路径下，得让反代把 `/assets`、`/api`、`/favicon.svg`
一并映射进来（或者干脆用一个子域名 / 独立端口）。实测确认过这个行为，
不是假设；改成支持 base path 需要同时动 vite 的 `base`、SPA 深链和后端拼的代理地址，
是一项独立改动。

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

> 前端依赖默认从 `https://registry.npmjs.org` 装（锁文件里 124 条 `resolved` 都指向它）。
> 国内构建机如果连官方源慢，加 `--build-arg NPM_REGISTRY=https://registry.npmmirror.com`
> 即可换源；不传就是官方源。**不要**把锁文件重新生成到某个第三方镜像上 —— 那会让
> 每次构建都依赖那台镜像的可用性（这里就发生过一次：镜像抖动，部署在 `npm ci` 上 ETIMEDOUT）。

### 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `ANIMEKO_HOST` | `0.0.0.0` | 监听地址 |
| `ANIMEKO_PORT` | `8080` | 服务端口 |
| `ANIMEKO_DATA_DIR` | `/data`（镜像内） | 数据目录：`selectors/` 数据源配置、`userdata.json` 个人数据 |
| `ANIMEKO_FRONTEND_DIR` | `frontend/dist`（镜像用 ENV 设成 `/app/frontend/dist`） | 前端构建产物目录。**代码默认值与镜像里不一样**：裸跑 jar 时它找的是相对当前工作目录的 `frontend/dist`，找不到就只服务 API（首页 404）并留一条 warn 说清是哪个目录 |
| `ANIMEKO_AUTH_USER` / `ANIMEKO_AUTH_PASSWORD` | — | 登录页的账号与口令；**配了就自动开启登录** |
| `ANIMEKO_AUTH_TOKEN` | — | 只配一个口令也行：登录页把它当密码（用户名任意），API 调用可用 `Authorization: Bearer` |
| `ANIMEKO_AUTH_ENABLED` | 自动 | 默认跟随凭据。显式写 `false` 可关掉登录页（前面已有反代鉴权时才需要）；**公网暴露时务必开启** |
| `ANIMEKO_COOKIE_SECURE` | `false` | 会话 Cookie 是否加 `Secure`，并**同时改用 `__Host-` 前缀**（要求 Secure、不许带 Domain、Path=/，于是别的子域再也种不进同名会话）。反代配好 HTTPS 后设为 `true`；在纯 HTTP（非 localhost）下设 `true` 会让浏览器丢弃 Cookie、登录页反复回弹，所以前缀只跟着它走 |
| `ANIMEKO_ALLOW_PRIVATE_TARGETS` | `false` | 是否允许代理/抓取内网地址（见下方安全说明） |
| `ANIMEKO_ALLOW_CROSS_ORIGIN` | `false` | 是否放开「写接口仅限同源」的 CSRF 检查（见下方安全说明） |
| `ANIMEKO_TRUSTED_PROXY_HOPS` | `0` | 服务前面有几层**你控制**的反代。0=直连（X-Forwarded-* 一律不信）；填 N 后限速按 XFF 从右数第 N 个真实客户端记账、同源判定改看 `X-Forwarded-Host`。层数要如实填：少了把代理 IP 当客户端（限速全员共享），多了把客户端伪造的 XFF 当真值 |
| `ANIMEKO_SEED_SUBSCRIPTION_URL` | 空 | `ANIMEKO_SUBSCRIPTION_AUTO_IMPORT` 的**别名**（旧名字），等价于往那个列表里追加一个地址。两个只配一个。曾经它代表另一套「只在空仓库时同步阻塞启动拉一次」的机制，最坏情况会让端口迟迟不监听，现已统一到异步这一套 |
| `ANIMEKO_BANGUMI_BASE_URLS` | `https://api.bgm.tv,https://bgmapi.anibt.net` | Bangumi API 地址列表（逗号分隔，按顺序故障转移；官方域名在部分地区被墙，可改用社区镜像） |
| `ANIMEKO_REQUEST_TIMEOUT_MS` | `20000` | 数据源抓取类出站请求超时 |
| `ANIMEKO_BANGUMI_TIMEOUT_MS` | `45000` | **Bangumi 元数据单独一档**。社区镜像实测要 13~45 秒才返回一次搜索，套用上面那个 20 秒上限会把它正好掐死——症状是「搜索页永远没有 Bangumi 条目和简介」，而你手动 curl 镜像又是通的 |
| `ANIMEKO_PROXY_TIMEOUT_MS` | `120000` | 视频代理的连接与**响应头**超时（body 阶段刻意不设时限，见下方安全说明） |
| `ANIMEKO_SUBSCRIPTION_AUTO_IMPORT` | 空 | 开机自动导入的聚合订阅地址（逗号分隔可多个），免去每次去界面里勾选。**只覆盖同名源、只增不删**，且沿用本机已有的启用/关闭状态——你在界面上关掉的源，重启后仍然是关掉的。订阅站挂了只写日志，不影响服务启动 |
| `ANIMEKO_SUBSCRIPTION_AUTO_TIERS` | 空 | 自动导入时的 tier 白名单（逗号分隔，如 `0,1`；tier 越低质量越好）。空=全导。**只作用于自动导入**，界面里的勾选导入不受限制 |

### 配置写错时会拒绝启动

配置解析是**失败关闭**的：认不出来的值不会悄悄回落到默认值，而是打印一条带变量名的错误后
退出（实测第一行就是 `Exception in thread "main" java.lang.IllegalStateException: ANIMEKO_...`，
退出码 1）。原因是这类静默回落恰好落在安全属性上——`ANIMEKO_AUTH_ENABLED=TRUE`
（大写）以前会被当成「没配」，鉴权因此被推导为关闭，运维以为上了锁，实际得到一台敞开的主机。

会拒绝启动的几种写法：

- `ANIMEKO_AUTH_ENABLED` / `ANIMEKO_COOKIE_SECURE` / `ANIMEKO_ALLOW_PRIVATE_TARGETS` /
  `ANIMEKO_ALLOW_CROSS_ORIGIN` 填了布尔之外的东西。接受 `true`/`false`（大小写不限），
  以及 `1`/`0`、`yes`/`no`、`on`/`off`、`y`/`n`、`t`/`f`；其它一律拒绝，不做「猜你想写什么」
- **只配了 `ANIMEKO_AUTH_USER` 或只配了 `ANIMEKO_AUTH_PASSWORD`**：账号口令必须成对，
  否则谁也登录不进来，而鉴权又会被「没有完整凭据」推导为关闭。要关掉登录页请显式写
  `ANIMEKO_AUTH_ENABLED=false`（前提是前面已有反代鉴权）
- `ANIMEKO_PORT` 不是 1–65535 的整数；各类 `*_TIMEOUT_MS` 不是正数或超过上限
  （抓取与元数据类 600000，视频代理 3600000——写成 1e15 的请求不是「永不超时」，是把连接钉死）
- `ANIMEKO_TRUSTED_PROXY_HOPS` 不是 0–8 的整数（越界不再夹取：夹到 8 会让限速键取错位置）

容器因此可能进入重启循环。看到 `IllegalStateException: ANIMEKO_...` 就是配置的问题，
`docker logs` 第一行会直接写明是哪个变量、错在哪。

另外：`ANIMEKO_SUBSCRIPTION_AUTO_IMPORT` 指向的聚合订阅如果源数超过 500 个，
现在只导入前 500 个并在预览的「未导入」列表里说明剩余数量，不再因为超量而整份订阅导入失败。

### 接口一览

「凭据」指会话 Cookie、`Authorization: Basic`、或 `Authorization: Bearer <token>` 三者之一
（浏览器子资源只能靠 Cookie，命令行用 `-u 账号:口令`）。未启用鉴权时所有接口都免凭据。

| 方法 | 路径 | 凭据 | 说明 |
|---|---|---|---|
| GET | `/api/health` | 免 | `{status, version, authEnabled, authenticated}`；`authenticated` **只反映会话 Cookie**（见下）。容器 `HEALTHCHECK` 依赖它，所以永远免鉴权 |
| POST | `/api/session/login` | 免 | `{username, password}` → `Set-Cookie: animeko_session=…`（开了 `ANIMEKO_COOKIE_SECURE` 则是 `__Host-animeko_session`）。按 IP 对**猜错**限速，超限 `429` + `Retry-After`（正确口令不受该窗口影响）；请求体上限 32KB |
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
  开了 `ANIMEKO_COOKIE_SECURE` 时名字变成 `__Host-animeko_session`：浏览器对这个前缀强制「必须 Secure、不许带 Domain、Path 必须是 /」，
  于是同级子域再也无法往这台机器上**种**一个同名会话（cookie tossing）。读侧两个名字都认（升级/降级那次不至于把已登录的人踢下线），
  注销时两个都覆盖。
  用 Cookie 而不是让前端带 `Authorization` 头，是因为 `<video>`、`<img>`、hls.js 的分片请求
  都加不了自定义头 —— 只有 Cookie 会被浏览器自动带到这些子资源上。
  未认证请求同时下发 **Basic 质询**，命令行与不带 Cookie 的场景仍可用：
  `curl -u 账号:口令`，或只配了 token 时**用户名任意、密码填 token**；
  API 客户端也可以用 `Authorization: Bearer <ANIMEKO_AUTH_TOKEN>`。
- 凭据校验失败按客户端 IP 限速：**5 分钟内 8 次**，超过后**继续猜错**只得到 `429`（带 `Retry-After`），
  不再回答「这次对不对」。**但猜对的人不会被这个窗口挡在门外** —— 把正确口令也拒掉拦不住任何人
  （他等 5 分钟再来一次照样进得来），却能把机主锁死：会话只存内存，容器一重启就必须重新走登录页，
  而下面那种「全站共用一个限速键」的部署里，任何匿名访客都能反复占住这条路。
  限速是**猜测速率**的闸门，不是给合法用户设的门槛。
  **直连部署（默认）按 `remoteHost` 记账**：X-Forwarded-For 完全由客户端提供，信它就能靠
  轮换假 IP 无限重置计数器（有回归用例）。**反代部署必须设 `ANIMEKO_TRUSTED_PROXY_HOPS=<层数>`**：
  否则所有请求的 `remoteHost` 都是代理自己，全站共用一个计数器 —— 一个访客猜错到限额后，
  别人的猜测会一起被 429 挡掉（正确口令仍然进得来，但每个人的爆破预算被并成了同一份）。
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
  主机名**认不出来时按不匹配处理**（而不是放行）：早先的判断写成
  `host != null && host !in allowedHosts`，于是 `URI.host` 解析失败的主机名
  （带下划线那种，DNS 里真实存在）会跳过绑定检查、把源站 Cookie 直接发出去 ——
  与它上一行注释说的正好相反。现在主机名提取与 `UrlGuard` 共用同一套算法，
  避免出现「守卫按 A 主机放行、会话按 B 主机绑定」的错位。
  `/api/proxy` 不再接受 `ua`/`referer`/`cookie` 参数，顺带关掉了
  「任意 URL + 任意请求头」这个组合面。
- HLS 清单只在**上游 2xx** 时才改写并以 `200 + application/vnd.apple.mpegurl` 返回。
  以前这条分支无论上游回什么都走 `respondText(...)`，而它默认 200：源站一个 403
  防盗链页会被包装成「一份看起来合法的 m3u8」发给播放器，hls.js 既不会报错也不会
  切换下一条线路/下一个源，用户只看到「加载中」然后黑屏。现在上游的状态码与类型原样透传。
  另外清单开头带 BOM 时（部分国内打包站），`#EXTM3U` 会被当成一行分片地址改写掉，
  清单头就此消失；畸形到无法解析的分片 URI 也不再被改写成必然 400 的代理地址，而是原样留着一行。
- 两张会话表（播放代理的 `sid`、登录会话）都是**进程内内存**，因此只支持**单副本**部署：
  多副本各持一份、请求被负载均衡轮转时，分片会随机 403/410、登录会随机掉线。
  要横向扩展需把它们换成共享存储（如 Redis）。容器重启后需要重新登录。
- `/api/data` 与数据源写入有 **1MB 请求体上限**，个人数据文档本身有 512KB 上限；
  超限返回 `400`，且请求体是边读边计数的，不会先把整个请求读进堆里。
- 个人数据落盘是「写临时文件 → `fsync` → 备份当前文件 → rename」，而**读的一侧会认这份备份**：
  主文档缺失、为空或不是合法 JSON 时，先拿同目录的 `userdata.json.bak` 恢复（复制回主文件，
  这样恢复动作本身再被打断也还能再来一次），只有备份也不可用时才退回空文档。
  这一条以前是缺的 —— `.bak` 一直在写却没人读，等于备了份没人看的档案：掉电让主文件只剩半个，
  读侧就返回 `{}`，前端把它当空文档，用户下一次点收藏把整份数据覆盖掉。
  坏掉的那份会留成 `*.bad` 作为证据。`read()` 与 `write()` 共用同一把锁：
  读操作会把坏文件移走，不加锁时能与并发的写操作交错，把刚提交好的文件当坏文件搬走。
- `If-Match` 按 HTTP 语义解释，不是整串比字面：`*` 表示「资源已存在才写」，
  `"a", "b"` 表示任一强标签匹配即可，`W/"a"` 这类弱标签不参与强比较。
  以前是把头整个 `trim('"')` 后与当前指纹逐字比较，于是 `*` 和「多值里含正确指纹」
  这两种合法写法都恒定 409 —— 照 HTTP 惯例写的脚本客户端永远写不进去。
- 内网/保留地址的判定不只看 Java 的 `isLoopbackAddress` 那几个谓词：实测
  `http://100.64.0.1/`（运营商 NAT）、`http://0.1.2.3/`（0.0.0.0/8 整段，Java 只把
  精确的 0.0.0.0 算 anyLocal）、`198.18.0.0/15`、三个 TEST-NET 以及 `100::/64`、
  `2001:db8::/32`、`2002::/16` 都能穿过原来的清单（表现是 20 秒连接超时，也就是真的去连了）。
  现在这些段全部显式拒绝。
- `/api/data` 是**条件写**：`GET` 返回 `ETag`（内容指纹），`PUT` 带上同一个 `If-Match` 时，
  版本已经变了会返回 `409 DATA_STALE` 而不是静默覆盖。前端据此重读服务器文档、
  只把自己改过的键贴回去再写一次（`progress` 再细一层到剧集），所以两个标签页/两台设备
  不会互相吃掉对方的收藏。不带 `If-Match`（curl、旧客户端）则按明确意图直接覆盖。
- 前端数据层在**没读到服务器文档时禁止回写**（读取失败保持「未同步」状态并可重试）。
  这不是保守，是必须的：否则「读失败 → 文档视作空 → 一次收藏点击」就会把
  `{favorites:[…]}` 整份覆盖上去，收藏、进度、历史当场清零。
- 搜索页**逐个回填结果**，且对数据源搜索**限制并发为 3**。两条都是浏览器里实测出来的：
  攒齐再渲染时，几个死源各自要等 20 秒超时，这期间所有源都显示「0 个结果」，看起来像功能坏了；
  而全部源一起发请求会占满浏览器对同一来源的 6 条连接，连 `/api/health`、`/api/data`
  都要排在超时后面 —— 真实症状是**重开标签页时整个应用卡在「正在连接服务器…」**。
  换关键词或离开页面时还会 `abort()` 在飞请求，光靠「不采用结果」不够，连接仍然被占着。
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
    - 填 hops 的**前提**是那位代理会**改写**（不是追加）`X-Forwarded-Host` / `X-Forwarded-Proto` /
      `X-Forwarded-For`。nginx 用 `proxy_set_header`，不要用 `proxy_add_header`。
      如果设了 `hops>=1` 而代理把客户端发来的 XFH 原样透传，攻击者就能自己填一个与
      `Origin` 相同的 `X-Forwarded-Host`，从而把「写接口仅限同源」这一层整体绕过 ——
      这不是猜测，是信任 N 层转发头这个决定的直接后果。同理，代理不改写头部时，
      限速键会落到客户端伪造的地址上（见上面那段），爆破预算形同不存在。
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

> Bangumi 说明：官方 `api.bgm.tv` 在部分地区不可达。客户端先试上次成功的那个地址；
> 还没有记录、或它已经不好用时，把列表里所有地址**同时发出去竞速**，第一个成功的赢、其余取消
> （串行按顺序试的话，官方域名挂死时每次搜索都要先白等满超时，实测 42 秒后仍然失败）。
> 首次成功之后该地址会被记住，稳态下不再给已知的死域名开连接。
> 如果部署环境访问官方域名会长时间超时，建议直接把 `ANIMEKO_BANGUMI_BASE_URLS` 设为可用的镜像
> （如 `https://bgmapi.anibt.net`），避免每次冷启动等待官方超时。
> 旧版单值变量 `ANIMEKO_BANGUMI_BASE_URL` 仍作为兼容别名生效（优先于默认列表，被 `..._URLS` 覆盖）。

## 使用流程

1. **导入数据源**：首次启动会先种内置示例源，再在后台协程里拉取默认订阅并导入（compose 里 `ANIMEKO_SUBSCRIPTION_AUTO_IMPORT`，默认社区 css1.json），打开就能用；订阅站挂了或很慢都不影响启动。之后想增删，进入「数据源」页粘贴 animeko 的 Selector 数据源 JSON（与 animeko App 内「设置 → 数据源管理」添加的配置格式一致），或再用聚合订阅入口挑源。
2. **搜索**：首页搜索 → Bangumi 条目 + 各数据源结果。
3. **播放**：
   - Bangumi 条目页 → 剧集「播放」→ 选择数据源「自动匹配」（或「手动」）→ 自动在数据源中匹配番剧与集数；
   - 或数据源搜索结果 →「剧集」→ 选线路播放；
   - 解析失败时在播放页底部「手动地址播放」粘贴 m3u8/mp4 地址。
   - **解析失败的三种原因**，报错文案里都区分得出来：① 播放页里根本没有可提取的地址（`NO_VIDEO_URL_FOUND`，多是强依赖 JS 执行的源，服务端拿不到地址，只能手动播放）；② 地址提到了但上游拒绝本机（`403/401` 或连不上，常见于只对国内 IP 开放的 CDN，境外 VPS 无解，换国内线路的源即可）；③ 源站超时（`ANIMEKO_REQUEST_TIMEOUT_MS` 可调，但别指望调大能救死站）。
4. **聚合订阅**：数据源页填一条社区订阅地址（如 `https://sub.creamycake.org/v1/css1.json`）→「拉取订阅」→ 列表里能看到每个源的名字、分级、站点主机名与是否已存在 → 勾选后导入。整包导入的坏处是每次搜索都要把打不开的站点各试一遍，所以默认只勾选「本机还没有」的那些。订阅站挂了可以展开「贴订阅正文」直接粘 JSON。预览时会记录内容指纹，导入时若订阅已被更新过会提示重新预览，避免按一份和刚看到的不一样的清单导入。
   **不想每次都手动勾**：设 `ANIMEKO_SUBSCRIPTION_AUTO_IMPORT=https://sub.creamycake.org/v1/css1.json`（compose 里已默认写好），服务每次启动自动导入，再配 `ANIMEKO_SUBSCRIPTION_AUTO_TIERS=0,1` 只留高质量分级。自动导入只覆盖同名源、不删你手工加的源，也不会重启你关掉的源。
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
（旧名 `ani-subs`），本项目已用其订阅文件实测：**里面的在线源都能直接导入**（不写具体条数 —— 那份文件由社区持续增删，写死一个数字必然过期，这里就曾经写着 19 而实测已是 18）。

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

单元测试：`cd backend && ./gradlew test` —— 用例数以 `grep -rho '@Test' backend/src/test | wc -l` 为准（写死一个数字必然过期，这里就曾经写着 178 而实际早已 260+）。覆盖 JsonPath / Selector 引擎与数据源仓库 /
聚合订阅（含逐跳重定向、订阅内重名、坏条目单独失败、预览指纹防内容漂移、自动导入沿用已关闭的源、
一个订阅挂了不连带其它订阅）/ HLS 重写 /
代理会话绑定与过期（含主机名解析不出来时不得放行凭据）/ HLS 上游非 2xx 不得伪装成合法清单 / 个人数据从 .bak 恢复与读写同锁 / 登录会话与限速（含 XFF 伪造与可信代理层数）/ 凭据预言机 /
路径写法绕过 / 个人数据落盘与条件写 / SSRF 拦截不回显内网地址 / 候选 URL 尾缀净化 /
条目封面三种形态（自身属性、子树 img、同容器平级缩略图，含「不许拿邻居的图」）/
配置正则过期时的通用后缀兜底（且配置命中优先）/
停用源服务端拒绝 / API 路由与静态托管缓存策略 / Bangumi 客户端。
编译会有告警但**不会因此失败**（没开 `-Werror`/`allWarningsAsErrors`）——与其写一句没人执行的「要求 0 警告」，不如把它写成现状：一个不生效的承诺比没有承诺更坏。
GitHub Actions（`.github/workflows/ci.yml`）在 push/PR 上自动跑后端测试与前端 `tsc + vite build`。

端到端冒烟（对**真实二进制**跑，不是测试桩）：`bash scripts/live-smoke.sh` —— 13 节 78 项，
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
- [x] 聚合订阅预览与挑源导入，以及 `ANIMEKO_SUBSCRIPTION_AUTO_IMPORT` 开机自动导入
- [x] 搜索结果海报（含懒加载属性与 stui/myui 的「标题与缩略图平级」形态）+ 数据源收藏
- [ ] BT / PikPak 数据源（animeko 的 bt 模块）
- [ ] RSS 数据源（animeko 的 RssMediaSource）
- [x] 断点续播与播放记忆
- [ ] 与 Bangumi 账号双向同步收藏（当前只存自己服务器）
- [ ] 多账号（当前是单共享账号，凭据来自环境变量）

## 许可证

本项目以 [AGPL-3.0](LICENSE) 授权（继承自 animeko）。第三方组件版权归其作者所有
（Ktor / kotlinx.serialization / Jsoup / React / hls.js 等，均为其各自许可证）。

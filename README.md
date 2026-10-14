# Animeko Web

[animeko](https://github.com/open-ani/animeko)（open-ani，100% Kotlin/Compose Multiplatform 的弹幕追番平台）的**服务端化实现**：把 animeko 的数据源采集、Bangumi 元数据、弹弹play 弹幕、视频代理全部搬进服务端容器，浏览器负责播放与弹幕渲染。

**单容器部署，ARM64 原生支持**（播放在浏览器完成，服务端无原生依赖，x86_64 / arm64 同一份代码）。

> ⚠️ 本项目与 animeko 同样采用 **AGPL-3.0** 许可证；数据源抓取的视频内容版权归源站所有。请遵守所在地法律法规，仅用于个人学习使用，不要公网传播。

## 功能

| 模块 | 说明 |
|---|---|
| Bangumi 元数据 | 搜索 / 条目详情 / 剧集列表（开放 API，无需密钥） |
| Selector 数据源 | 可直接导入 animeko 社区配置（`factoryId: web-selector`，version 2）；支持 CSS / XPath / JsonPath 条目格式、线路分组、剧集集号正则。**注意**：与采集相关的字段全部生效，而 `tier`、`channelTiers`、`defaultResolution`、`defaultSubtitleLanguage`、`onlySupportsPlayers`、`selectMedia`、`searchCacheTtl` 这些属于 App 侧选择策略的字段会被接受但**忽略**（服务端不做画质/字幕/源优先级仲裁） |
| 弹弹play 弹幕 | 剧集自动匹配 + 评论拉取（需自行申请 appId/appSecret），前端 Canvas 渲染（滚动/顶部/底部、屏蔽词、字号/透明度/速度/密度） |
| 视频代理 | Range 透传、UA/Referer/Cookie 注入、HLS(m3u8) 清单与分片重写（分片同样携带防盗链头）、逐跳校验的重定向跟随、内网地址拦截 |
| 播放器 | hls.js（m3u8）+ 原生 mp4/webm；倍速、进度、音量、全屏；**手动地址播放**兜底 |
| 播放体验 | 键盘快捷键（空格/←/→/J/L/↑/↓/M/F/D）、**断点续播**、**自动连播**（下一集倒计时）、最近观看 |
| 本地功能 | 追番收藏、最近观看、搜索历史（均存浏览器 localStorage，不上传服务端） |
| 界面 | 暗色 / 亮色双主题（切换记忆在本地）、响应式 UI，桌面/手机浏览器均可 |

与 animeko App 的差异：App 用 WebView 拦截网络请求提取视频地址，服务端版改为抓播放页 HTML 后用 `matchVideo` 正则匹配（覆盖绝大多数源）；强依赖 JS 执行的源可用播放页「手动地址播放」兜底。

## 快速开始（Docker）

```bash
# 克隆后直接构建启动（x86_64 与 ARM64 均可）
docker compose up -d --build

# 打开浏览器
open http://localhost:8080
```

数据（数据源配置等）持久化在 `./data`，删除容器不丢失。

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
| `ANIMEKO_DATA_DIR` | `/data`（镜像内） | 数据目录（数据源配置） |
| `ANIMEKO_FRONTEND_DIR` | `/app/frontend/dist` | 前端构建产物目录 |
| `ANIMEKO_AUTH_ENABLED` | `false` | 开启访问鉴权（**公网暴露时务必开启**） |
| `ANIMEKO_AUTH_USER` / `ANIMEKO_AUTH_PASSWORD` | — | Basic 凭据，**浏览器访问推荐用这一对** |
| `ANIMEKO_AUTH_TOKEN` | — | Bearer Token，供 API 调用方使用 |
| `ANIMEKO_ALLOW_PRIVATE_TARGETS` | `false` | 是否允许代理/抓取内网地址（见下方安全说明） |
| `ANIMEKO_ALLOW_CROSS_ORIGIN` | `false` | 是否放开「写接口仅限同源」的 CSRF 检查（见下方安全说明） |
| `ANIMEKO_DANMAKU_APP_ID` / `ANIMEKO_DANMAKU_APP_SECRET` | — | [弹弹play 开放平台](https://dev.dandanplay.com) 申请 |
| `ANIMEKO_BANGUMI_BASE_URLS` | `https://api.bgm.tv,https://bgmapi.anibt.net` | Bangumi API 地址列表（逗号分隔，按顺序故障转移；官方域名在部分地区被墙，可改用社区镜像） |
| `ANIMEKO_REQUEST_TIMEOUT_MS` | `20000` | 元数据/抓取类出站请求超时 |
| `ANIMEKO_PROXY_TIMEOUT_MS` | `120000` | 视频代理（分片下载）超时 |

### 鉴权与代理的安全说明

- 开启 `ANIMEKO_AUTH_ENABLED` 后，服务端对未认证的 `/api/*` 请求下发 **Basic 质询**。
  浏览器弹出的输入框只需输一次，之后的视频分片、封面图片这些子资源会自动带上凭据
  （`<video>`/`<img>`/hls.js 都无法携带自定义 `Authorization` 头，所以纯 Bearer 模式仅供 API 调用方）。
  用 `ANIMEKO_AUTH_TOKEN` 时，弹窗里**用户名任意、密码填 token** 即可。
- `/api/health` 不参与鉴权：容器 `HEALTHCHECK` 和前端都需要免凭据读到它，
  否则开启鉴权会让容器永久显示 unhealthy。该接口只返回状态、版本与两个布尔开关。
- `/api/proxy` 与数据源抓取默认**拒绝内网与保留地址**（127.0.0.0/8、10/192.168/172.16 私有段、
  169.254.169.254 元数据地址、`*.localhost`/`*.internal` 等），并逐跳校验重定向目标。
  确实要代理局域网内 NAS 时，把 `ANIMEKO_ALLOW_PRIVATE_TARGETS=true` 打开。
- `/api/*` 的**写操作只允许同源**（对比 `Origin` 与 `Host`）：服务默认无鉴权且监听 0.0.0.0，
  否则用户访问任意恶意网页即可被跨站 POST 塞入数据源（`text/plain` 属简单请求，不会触发预检）。
  被拦时返回 `403 CROSS_SITE_REQUEST`。反向代理若改写了 `Host` 造成误判，用
  `ANIMEKO_ALLOW_CROSS_ORIGIN=true` 关闭该检查（此时请自行保证服务不被不可信网络访问）。
  命令行/API 客户端不带 `Origin`，不受影响。
- 静态资源缓存：`/assets/*`（带内容哈希）为 `max-age=31536000, immutable`，
  `index.html` 为 `no-cache`；缺失的资源请求返回 **404** 而不是回退 `index.html`——
  否则重新部署后，浏览器会把 HTML 当 ES module 执行，且 `React.lazy` 会永久记住这次失败。

> Bangumi 说明：官方 `api.bgm.tv` 在部分地区不可达。客户端按列表顺序尝试，首个可用的会被记住。
> 如果部署环境访问官方域名会长时间超时，建议直接把 `ANIMEKO_BANGUMI_BASE_URLS` 设为可用的镜像
> （如 `https://bgmapi.anibt.net`），避免每次冷启动等待官方超时。
> 旧版单值变量 `ANIMEKO_BANGUMI_BASE_URL` 仍作为兼容别名生效（优先于默认列表，被 `..._URLS` 覆盖）。

## 使用流程

1. **导入数据源**：进入「数据源」页，粘贴 animeko 的 Selector 数据源 JSON（与 animeko App 内「设置 → 数据源管理」添加的配置格式一致），点击导入并启用。
2. **搜索**：首页搜索 → Bangumi 条目 + 各数据源结果。
3. **播放**：
   - Bangumi 条目页 → 剧集「播放」→ 选择数据源「自动匹配」（或「手动」）→ 自动在数据源中匹配番剧与集数；
   - 或数据源搜索结果 →「剧集」→ 选线路播放；
   - 解析失败时在播放页底部「手动地址播放」粘贴 m3u8/mp4 地址。
4. **弹幕**：播放时自动按番剧名+集数向弹弹play 匹配（需配置 appId/appSecret），未配置时仅无弹幕，不影响播放。
5. **追番与连播**：条目页点「收藏」加入追番列表（导航栏「收藏」查看）；从剧集列表进入播放时会自动带上下一集，当前集播放结束后倒计时自动连播。
6. **播放快捷键**：`空格`/`K` 播放暂停、`←`/`→` ±5 秒、`J`/`L` ±10 秒、`↑`/`↓` 音量、`M` 静音、`F` 全屏、`D` 弹幕开关。播放进度自动记忆，下次进入同一剧集时可「继续播放」。

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

`subjectFormatId` 支持 `a`（CSS 选 `<a>`）、`indexed`（名字/链接两条 CSS）、`json-path-indexed`（JsonPath）；
`channelFormatId` 支持 `no-channel`、`index-grouped`（线路分组）。字段语义与 animeko 源码一一对应。

## 架构

```
┌──────────────────────── ARM64 Docker 容器 ────────────────────────┐
│  浏览器 ──► Ktor 服务（单端口 8080）                               │
│    /api/bangumi/*     Bangumi 开放 API                             │
│    /api/selector/*    Selector 引擎（Jsoup + JsonPath + 正则）     │
│    /api/danmaku/*     弹弹play（签名代理，密钥只在服务端）          │
│    /api/proxy          视频/HLS 代理（Range 透传 + 清单重写）        │
│    /*                 前端静态资源（React + hls.js + Canvas 弹幕） │
│  卷 /data：数据源配置                                              │
└───────────────────────────────────────────────────────────────────┘
```

## 本地开发

```bash
# 后端（Kotlin 2.4 / Ktor 3.1 / JDK 17+）
cd backend && ./gradlew installDist
ANIMEKO_FRONTEND_DIR=../frontend/dist ./build/install/animeko-web/bin/animeko-web

# 前端（React 19 + Vite 7）
cd frontend && npm install && npm run dev   # http://localhost:5173，/api 代理到 8080
```

测试：`cd backend && ./gradlew test`（JsonPath / Selector 引擎 / 弹幕签名 / HLS 重写 / 视频代理与 SSRF 防护 / API 路由 / Bangumi 客户端）。

## 目录结构

```
animeko-web/
├── Dockerfile              # 三阶段：node 前端 → gradle 后端 → JRE 运行（单镜像）
├── docker-compose.yml
├── backend/                # Kotlin + Ktor
│   └── src/main/kotlin/com/animeko/web/
│       ├── bangumi/        # Bangumi 客户端
│       ├── danmaku/        # 弹弹play 客户端（base64(sha256(appId+ts+path+secret))）
│       ├── selector/       # animeko 兼容的 Selector 引擎与仓库
│       ├── proxy/          # HLS 重写
│       └── api/            # REST 路由 + 可选鉴权 + 静态托管
└── frontend/               # React + Vite + hls.js + Canvas 弹幕
```

## 路线图

- [x] Bangumi 元数据 / Selector 引擎 / 弹弹play 弹幕 / HLS 代理 / 手动地址播放
- [ ] BT / PikPak 数据源（animeko 的 bt 模块）
- [ ] RSS 数据源（animeko 的 RssMediaSource）
- [ ] 追番收藏与进度同步（本地 + Bangumi 账号）
- [ ] 弹幕发送 / 弹幕云同步
- [ ] 播放记忆（断点续播）

## 许可证

本项目以 [AGPL-3.0](LICENSE) 授权（继承自 animeko）。第三方组件版权归其作者所有
（Ktor / kotlinx.serialization / Jsoup / React / hls.js 等，均为其各自许可证）。

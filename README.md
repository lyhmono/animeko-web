# Animeko Web

自托管的动漫追番 Web 应用：**Next.js 全栈（App Router + Tailwind CSS）**，单容器部署，收藏与观看进度保存在你自己的服务器上。

本版本在原有功能基础上对标 MoonTVPlus 做了全面翻新：全新绿色主题 UI、ArtPlayer 播放器、弹幕系统、外挂字幕、外部播放器跳转与浏览器内视频下载。

## 功能

**全新界面（对标 MoonTVPlus）**
- 品牌绿主题 + 玻璃拟态：桌面可折叠侧边栏、移动端顶部搜索 + 底部导航、明暗双主题
- 首页 Banner 轮播（自动播放 / 触屏滑动）、继续观看、今日新番、评分排行榜、在看收藏
- 海报卡片：评分分档角标、年份徽章、hover 播放遮罩、观看进度条
- 条目页沉浸式模糊背景、播放页「选集 / 弹幕」双栏面板

**浏览与观看**
- Bangumi 搜索、条目详情、评分排行榜、每日放送；多通道（官方 / 镜像 / 自定义）竞速与 2.5 秒对冲、首选地址记忆、通道探测
- 社区数据源（web-selector）导入、启停、搜索与剧集解析、订阅预览后勾选导入、健康监控
- 订阅自动化：开机自动导入（支持 tier 过滤）与已登记订阅的定时自动刷新（首个请求触发，保留已有源的启停状态）
- ArtPlayer + HLS.js：倍速记忆、画中画、全屏、截图、AirPlay、快捷键
- 断点续播、自动连播下一集（5 秒倒计时）、跳过片头、连播预解析、直连失败自动回退代理

**播放器增强（新增）**
- 💬 **弹幕系统**：弹弹 play `/api/v2` 协议搜索 → 分集 → 加载，支持本地上传 B 站 / 弹弹 play XML 弹幕；不透明度 / 字号 / 速度 / 屏蔽规则（普通 + 正则）设置持久化
- 🎞️ **外挂字幕**：本地 SRT / VTT / SBV / LRC 自动转 WebVTT；ASS / SSA 由 JASSUB（WASM）渲染，保留特效样式，自托管 worker / 字体
- 🎮 **外部播放器跳转**：PotPlayer、VLC、MPV、MX Player、nPlayer、IINA 一键唤起
- 📥 **视频下载**：浏览器内 m3u8 分片并发下载（支持 Master 清单选流、AES-128 解密、失败重试），合并为 TS 文件；直链视频直接另存
- ⚙️ **播放设置持久化**：音量 / 倍速（全局 + 番剧级）、弹幕设置、直连开关、自动连播、片头片尾秒数

**个人数据（服务端同步）**
- 收藏（想看 / 在看 / 看过 / 搁置）、观看进度、最近观看、搜索历史跨设备同步
- 播放进度走条目级增量写（PATCH），ETag 条件写 + 409 合并重试，断网自动重试
- 一键导出 / 导入备份（JSON，合并式导入）

**运维与安全**
- 数据源健康监控（成功率、平均耗时、最近错误）、运行指标端点
- 可选登录鉴权（账号密码或 Token）、SameSite=Strict 会话（按天滑动重写 Cookie）、写请求同源校验、登录限流
- 媒体代理：SSRF 逐跳校验、HLS 清单改写、代理会话请求头服务端保管（UA / Referer / Cookie 不下发浏览器）
- 个人数据原子落盘 + 备份恢复，存储故障返回可重试的 503

## 快速开始（Docker）

```bash
git clone https://github.com/lyhmono/animeko-web.git
cd animeko-web
docker compose up -d --build
```

打开 <http://127.0.0.1:8080>。数据保存在 `./data`（收藏、进度、数据源配置、会话）。

默认不启用登录鉴权，且只监听本机回环地址。**需要局域网或公网访问时，先配置登录凭据**：

```yaml
# docker-compose.yml
environment:
  - ANIMEKO_AUTH_USER=your-name
  - ANIMEKO_AUTH_PASSWORD=your-strong-password
```

## 本地开发

```bash
npm ci
npm run dev        # http://127.0.0.1:8080
npm run build      # 类型检查 + 生产构建（standalone）
npm start          # 生产模式启动
```

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `PORT` / `HOSTNAME` | `8080` / `0.0.0.0`（镜像） | Next.js 监听地址 |
| `ANIMEKO_DATA_DIR` | `./data`（镜像 `/data`） | 数据目录 |
| `ANIMEKO_AUTH_USER` / `ANIMEKO_AUTH_PASSWORD` | 空 | 登录账号密码（需成对配置） |
| `ANIMEKO_AUTH_TOKEN` | 空 | 单独 Token 鉴权（与账号密码二选一） |
| `ANIMEKO_AUTH_ENABLED` | 按凭据推导 | 显式开关鉴权 |
| `ANIMEKO_COOKIE_SECURE` | `false` | HTTPS 部署时设为 `true` |
| `ANIMEKO_ALLOW_NO_AUTH` | `false` | 无鉴权对外暴露的显式确认（勿用于公网） |
| `ANIMEKO_BANGUMI_BASE_URLS` | 官方 + 社区镜像 | Bangumi 候选地址（逗号分隔，最多 8 个） |
| `ANIMEKO_DANMAKU_API_BASE` | `https://api.dandanplay.net` | 弹幕服务（弹弹 play `/api/v2` 协议兼容服务） |
| `ANIMEKO_DANMAKU_APP_ID` / `ANIMEKO_DANMAKU_APP_SECRET` | 空 | 官方弹弹 play 接口签名凭据（自建兼容服务可留空） |
| `ANIMEKO_SUBSCRIPTION_AUTO_IMPORT` | 空 | 开机自动导入的订阅地址（逗号分隔；`ANIMEKO_SEED_SUBSCRIPTION_URL` 为旧别名） |
| `ANIMEKO_SUBSCRIPTION_AUTO_TIERS` | 空（全部） | 自动导入时的 tier 过滤（逗号分隔，仅影响自动导入） |
| `ANIMEKO_SUBSCRIPTION_REFRESH_MS` | `21600000`（6 小时） | 已登记订阅的定时自动刷新间隔 |
| `ANIMEKO_REQUEST_TIMEOUT_MS` | `20000` | 上游请求超时 |
| `ANIMEKO_BANGUMI_TIMEOUT_MS` | `45000` | Bangumi 请求超时 |
| `ANIMEKO_PROXY_TIMEOUT_MS` | `120000` | 媒体代理超时 |
| `ANIMEKO_ALLOW_PRIVATE_TARGETS` | `false` | 允许代理 / 抓取内网地址 |
| `ANIMEKO_ALLOW_CROSS_ORIGIN` | `false` | 关闭 API 写接口同源检查 |
| `ANIMEKO_TRUSTED_PROXY_HOPS` | `0` | 反代层数（真实客户端 IP / Origin 判定） |

## 使用流程

1. **搜索番剧**：搜索页输入名称，同时检索 Bangumi 与全部启用数据源。
2. **导入数据源**：「数据源」页粘贴订阅链接或 JSON，预览勾选后导入；可测试关键词、查看健康指标。
3. **选集播放**：条目页自动匹配各源最佳结果 → 选集进入播放页；右侧「弹幕」页签搜索弹幕库或上传本地 XML。
4. **增强播放**：工具栏加载外挂字幕、唤起外部播放器、下载当前集、切换直连 / 代理。
5. **追番同步**：收藏、进度、最近观看自动同步；「设置 → 备份与恢复」导出 JSON 备份。

## 架构

```
src/
  app/                 Next.js App Router
    page.tsx           首页（轮播 / 继续观看 / 新番 / 排行）
    search|discover|favorites|sources|settings|play/
    subject/[id]/      Bangumi 条目页
    source/[name]/     数据源条目选集页
    api/               Route Handlers：会话、Bangumi、个人数据、数据源、媒体代理、弹幕
  components/          布局导航、海报卡片、轮播、播放器与弹幕面板
  lib/                 前端数据层（同步状态机 / 收藏 / 进度 / 偏好）
    player/            弹幕设置、字幕转换、外部播放器、m3u8 下载
    server/            配置、鉴权、SSRF 守卫、原子存储、Bangumi 客户端、Selector 引擎
public/assets/jassub/  自托管 ASS 字幕渲染 worker / wasm / 字体
Dockerfile             多阶段构建（npm ci → next build → standalone 运行时）
```

**与原 Kotlin + React 版本的关系**：本版本为整体迁移重写，API 路径与个人数据格式保持兼容，`data/` 目录可直接复用。Selector 引擎使用 Cheerio（CSS）+ 自研受限 JsonPath，**不支持 XPath 选择器与需要执行页面 JS 的源**（与原实现一致，不执行 `<script>`）。

**部署注意**：登录会话与媒体代理会话为进程内存态，请单副本部署；重启后需重新登录，正在播放的代理链接会失效（播放页会自动重新解析一次）。

## 许可证

基于 animeko（AGPL-3.0）架构的服务端化实现。数据源解析逻辑来自社区生态，请遵守各数据源站点的使用条款；弹幕服务遵循弹弹 play 开放接口协议。

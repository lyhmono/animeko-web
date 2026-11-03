# Animeko Web

自托管的动漫追番 Web 应用：Kotlin (Ktor) 后端 + React 前端，单容器部署，收藏与观看进度保存在你自己的服务器上。

## 功能

**浏览与观看**
- Plex 风深色界面：海报墙、横向分区、沉浸式条目页、移动端底部导航
- Bangumi 搜索、条目详情、评分排行榜、每日放送
- 社区数据源（JS 选择器）导入、启用/停用、搜索与剧集解析
- 视频播放（HLS / MP4）：快捷键、画中画、倍速、跳过片头、自动连播下一集
- 连播预取：下一集地址提前解析，切换更快

**个人数据（服务端同步）**
- 收藏、观看进度、最近观看、搜索历史跨设备同步
- 播放中进度走条目级增量写（PATCH），不再整档覆盖
- 冲突按条目合并，断网自动重试，409 不丢本地改动
- 一键导出 / 导入备份（JSON）

**运维与安全**
- 数据源健康监控：成功率、平均耗时、最近错误
- 运行指标端点（请求数、代理数、运行时长）
- 可选登录鉴权（用户密码或 Token）、CSRF 防护、SSRF 逐跳校验、点击劫持防护
- 个人数据原子落盘 + 备份恢复，存储故障返回可重试的 503

## 快速开始（Docker）

### Docker Compose（推荐）

```bash
git clone https://github.com/lyhmono/animeko-web.git
cd animeko-web
docker compose up -d
```

打开 <http://127.0.0.1:8080>。数据保存在 `./data`（收藏、进度、数据源配置、会话）。

默认不启用登录鉴权，但只监听本机回环地址。需要局域网或公网访问时，**先配置登录凭据**：

```yaml
# docker-compose.yml
environment:
  - ANIMEKO_AUTH_USER=your-name
  - ANIMEKO_AUTH_PASSWORD=your-strong-password
```

然后 `docker compose up -d` 重建。

### 单容器

```bash
docker build -t animeko-web .
docker run -d -p 127.0.0.1:8080:8080 -v ./data:/data animeko-web
```

## 部署安全（务必先读）

- 裸跑 jar 默认只监听 `127.0.0.1`；镜像内监听 `0.0.0.0`，但 **没有配置登录凭据、又监听非回环地址时，服务会拒绝启动**。
- 想让其他机器访问，二选一：
  1. 配置 `ANIMEKO_AUTH_USER` + `ANIMEKO_AUTH_PASSWORD`（或 `ANIMEKO_AUTH_TOKEN`）
  2. 明确接受无鉴权暴露，设置 `ANIMEKO_ALLOW_NO_AUTH=true`（**不要用于公网**）
- 不要把 8080 直接暴露到公网：推荐「Tailscale/内网穿透」或「反代 + 登录鉴权」。
- 收藏、数据源、代理地址都能反映你的观看习惯和内网拓扑，请按私密数据对待。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `ANIMEKO_HOST` | `127.0.0.1`（镜像内 `0.0.0.0`） | 监听地址 |
| `ANIMEKO_PORT` | `8080` | 监听端口 |
| `ANIMEKO_DATA_DIR` | `/data`（镜像）/ `data`（本地） | 数据目录 |
| `ANIMEKO_AUTH_USER` | 空 | 登录用户名 |
| `ANIMEKO_AUTH_PASSWORD` | 空 | 登录密码 |
| `ANIMEKO_AUTH_TOKEN` | 空 | 单独使用 Token 鉴权（与用户名密码二选一） |
| `ANIMEKO_AUTH_ENABLED` | 按凭据推导 | 显式开关鉴权；有凭据但想临时关闭可设 `false` |
| `ANIMEKO_ALLOW_NO_AUTH` | `false` | 无凭据且监听非回环地址时是否允许启动 |
| `ANIMEKO_SESSION_TTL_DAYS` | `30` | 登录会话有效期（小时或天数，按实现） |
| `ANIMEKO_COOKIE_SECURE` | `false` | HTTPS 部署时设为 `true` |
| `ANIMEKO_TRUSTED_PROXY_HOPS` | `0` | 反代层数，用于限速的真实客户端 IP |
| `ANIMEKO_BANGUMI_API_BASE` | 官方 API | 自定义 Bangumi API 地址（多地址逗号分隔） |
| `ANIMEKO_REQUEST_TIMEOUT_MS` | `20000` | 上游请求超时（毫秒） |
| `JAVA_OPTS` | 镜像默认堆上限 50% | JVM 参数 |

> 具体默认值以 `backend/src/main/kotlin/com/animeko/web/config/AppConfig.kt` 为准。

## 使用流程

1. **搜索番剧**：顶部搜索框输入名称，同时检索 Bangumi 与已启用数据源。
2. **导入数据源**：「数据源」页粘贴社区订阅链接或 JSON，预览后导入；可随时启用/停用、测试关键词。
3. **收藏与追番**：条目页点收藏，播放页自动记录进度，「继续观看」随时接着看。
4. **多设备同步**：同一部署登录后，收藏、进度、最近观看自动同步。
5. **备份**：「设置 → 备份与恢复」导出 JSON；换机或重装后直接导入。

## 架构

```
backend/   Kotlin + Ktor：API、鉴权、数据源解析、媒体代理、个人数据存储
frontend/  React + Vite：Plex 风 UI、播放器、同步状态机
e2e/       Playwright 浏览器冒烟与视觉回归
scripts/   构建契约与冒烟自检
Dockerfile 多阶段构建（前端 → 后端 → JRE 运行时）
```

**目录结构**

```
backend/src/main/kotlin/com/animeko/web/
  api/        路由：会话、数据源、个人数据、Bangumi、代理、静态托管
  auth/       会话与凭据校验、限流
  bangumi/    Bangumi 客户端与通道
  data/       个人数据原子存储（含条目级 PATCH）
  selector/   数据源引擎、健康监控、订阅导入
  proxy/      代理会话与 HLS 改写
  http/       SSRF 守卫、JSON 深度限制
frontend/src/
  components/ 播放器、布局、海报、Rail 分区
  pages/      发现/搜索/条目/播放/收藏/数据源/设置
  lib/        storage（同步）、prefs（设备偏好）、userDataMerge（合并）
```

## 开发与测试

**后端**

```bash
cd backend && ./gradlew test          # 全部测试 + JaCoCo 覆盖率报告
```

**前端**

```bash
cd frontend
npm ci
npm run lint           # ESLint
npm run test           # Vitest
npm run test:coverage  # 覆盖率（阈值见 vite.config.ts）
npm run build          # 类型检查 + 生产构建
```

**端到端**

```bash
python3 -m unittest discover -s scripts -p 'test_build_contracts.py'
python3 -m unittest discover -s e2e -p 'test_smoke.py'
# 浏览器冒烟（需要 Playwright）：见 e2e/smoke.py
# 视觉回归（可选，非 CI 门禁）：python e2e/visual.py --update
```

CI（`.github/workflows/ci.yml`）在 push/PR 上运行：构建契约、后端测试 + JaCoCo、前端 lint/build/测试 + 覆盖率、Docker 构建与浏览器冒烟。

## 数据与备份

- 数据目录默认 `data/`（容器内 `/data`）：`userdata.json`（个人数据）、`selectors/`（数据源）、`bangumi-channel.json`、`sessions.db`。
- 个人数据写入是「临时文件 + 原子替换 + 上次备份」，异常时自动从 `.bak` 恢复。
- 备份导出只包含收藏、进度、源收藏、搜索历史、最近观看，不含数据源配置与凭据。

## 常见问题

**为什么搜索不到结果？**
到「数据源」页检查是否有已启用的源，并用「测试」验证关键词；健康列会显示成功率与最近错误。

**播放失败/一直加载？**
先切换「直连播放」开关（设置页）；某些源必须走服务端代理才能带 Referer/Cookie。仍失败时看数据源健康状态与浏览器控制台。

**收藏或进度丢失？**
先确认登录的是同一个部署与账号；再到「设置 → 备份与恢复」导出当前数据，必要时从备份导入。

## 许可证

基于 animeko（AGPL-3.0）架构的服务端化实现。数据源解析逻辑来自社区生态，请遵守各数据源站点的使用条款。

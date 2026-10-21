# syntax=docker/dockerfile:1

# ---------- Stage 1: 构建前端 ----------
FROM node:22-alpine AS frontend-build
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
# 锁文件里的 resolved 一律指向 https://registry.npmjs.org，不再绑死某个第三方镜像。
# 之前 124 条全指向 http://mirrors.tencent.com（还是明文 HTTP），那台镜像一抖，
# 任何一次构建都直接 ETIMEDOUT —— 部署失败与项目代码无关，只是它替我们选了个源。
# 国内构建机要走镜像时：--build-arg NPM_REGISTRY=https://registry.npmmirror.com
# （实测 npm ci 会把锁文件里指向默认源的地址替换成这里给的源）。
# 再给 npm 加退避重试：一次网络抖动不该让整次部署失败。
ARG NPM_REGISTRY=https://registry.npmjs.org
RUN npm ci --no-audit --no-fund --registry=${NPM_REGISTRY} \
      --fetch-retries=5 --fetch-timeout=120000
COPY frontend/ ./
RUN npm run build

# ---------- Stage 2: 构建后端 ----------
FROM gradle:9.3.1-jdk21-jammy AS backend-build
WORKDIR /app
COPY backend/ ./
RUN gradle installDist --no-daemon -x test

# ---------- Stage 3: 运行 ----------
FROM eclipse-temurin:21-jre-jammy
LABEL org.opencontainers.image.title="Animeko Web" \
      org.opencontainers.image.description="animeko 的服务端化实现：登录门控 + Bangumi + Selector 数据源 + 个人数据 + HLS 代理" \
      org.opencontainers.image.licenses="AGPL-3.0"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata gosu \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --create-home app

WORKDIR /app
COPY --from=backend-build /app/build/install/animeko-web /app/
COPY --from=frontend-build /web/dist /app/frontend/dist
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

# LANG 必须显式设为 UTF-8：数据源文件名由（多为中文的）数据源名派生，
# 而 sun.jnu.encoding 跟随系统 locale。基础镜像默认 POSIX locale 时，
# 保存中文名数据源会直接失败（Malformed input or input contains unmappable characters）。
ENV LANG=C.UTF-8 \
    ANIMEKO_HOST=0.0.0.0 \
    ANIMEKO_PORT=8080 \
    ANIMEKO_DATA_DIR=/data \
    ANIMEKO_FRONTEND_DIR=/app/frontend/dist \
    TZ=Asia/Shanghai

VOLUME /data
EXPOSE 8080

# 端口必须从 ANIMEKO_PORT 取：它是被明文支持的操作者开关（README 环境变量表第一行，
# AppConfig 还专门把 0 与非法值判成拒绝启动）。写死 8080 的话，换了端口的部署
# 服务明明健康却永远 unhealthy —— 而 HEALTHCHECK 是容器编排里唯一的健康信号，
# k8s/compose 会据此重启一个根本没坏的进程。shell 形式的 CMD 在运行时展开变量。
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${ANIMEKO_PORT:-8080}/api/health" || exit 1

# 入口脚本：root 起来只为给挂载的 /data 改属主，随后 gosu 降权到 app 用户。
# 服务本体只读 /app（root 属主、全局可读），只写 /data。
ENTRYPOINT ["docker-entrypoint.sh"]

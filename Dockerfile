# syntax=docker/dockerfile:1

# ---------- Stage 1: 构建前端 ----------
FROM node:22-alpine AS frontend-build
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
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
      org.opencontainers.image.description="animeko 的服务端化实现：Bangumi + Selector 数据源 + 弹弹play 弹幕 + HLS 代理" \
      org.opencontainers.image.licenses="AGPL-3.0"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=backend-build /app/build/install/animeko-web /app/
COPY --from=frontend-build /web/dist /app/frontend/dist

ENV ANIMEKO_HOST=0.0.0.0 \
    ANIMEKO_PORT=8080 \
    ANIMEKO_DATA_DIR=/data \
    ANIMEKO_FRONTEND_DIR=/app/frontend/dist \
    TZ=Asia/Shanghai

VOLUME /data
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/api/health || exit 1

ENTRYPOINT ["/app/bin/animeko-web"]

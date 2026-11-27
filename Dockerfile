# syntax=docker/dockerfile:1

# === 构建阶段：安装依赖并产出 Next.js standalone ===
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund

COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

# === 运行时：standalone + 静态资源，非 root 运行 ===
FROM node:22-alpine
WORKDIR /app

RUN apk add --no-cache su-exec curl tzdata \
    && addgroup -g 10001 app && adduser -u 10001 -G app -D app

ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    ANIMEKO_DATA_DIR=/data \
    PORT=8080 \
    HOSTNAME=0.0.0.0 \
    TZ=Asia/Shanghai

COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh && mkdir -p /data

VOLUME /data
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${PORT}/api/health" || exit 1

ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["node", "server.js"]

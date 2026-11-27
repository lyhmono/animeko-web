#!/bin/sh
# 数据目录属主修正后降权运行；chown 失败仅告警（保留只读浏览能力）。
set -eu

if [ "$(id -u)" = "0" ]; then
  mkdir -p "${ANIMEKO_DATA_DIR:-/data}" || true
  chown -R app:app "${ANIMEKO_DATA_DIR:-/data}" 2>/dev/null \
    || echo "[entrypoint] warn: 无法调整数据目录属主，写入可能失败" >&2
  if [ "$#" -gt 0 ]; then
    exec su-exec app "$@"
  fi
  exec su-exec app node server.js
fi

if [ "$#" -gt 0 ]; then
  exec "$@"
fi
exec node server.js

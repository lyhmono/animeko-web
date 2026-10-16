#!/bin/sh
set -e

# 默认让容器以 root 跑起来，只是为了能给挂载进来的 /data 改属主；
# 改完立刻通过 gosu 降权到 app 用户——服务本身绝不需要 root。
#
# 如果 compose 里显式写了 user:（或用 k8s 的 runAsUser），进来时已经不是
# root，这段跳过：那种部署下卷权限由编排层负责，脚本不做假设。
if [ "$(id -u)" = "0" ]; then
  chown -R app:app /data 2>/dev/null || true
  exec gosu app "$0" "$@"
fi

exec /app/bin/animeko-web

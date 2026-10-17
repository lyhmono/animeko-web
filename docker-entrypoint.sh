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

# 降权后重跑会带着原来的参数进来（上面那行 "$@"）。这时候如果参数非空，
# 说明用户是 `docker run <image> /bin/sh` 想进容器调试 —— 必须执行它，
# 而不是无条件起服务。实测旧写法会把 /bin/sh 静默丢掉、直接启动服务器，
# 于是「docker run 进去看看」这条常规调试路径根本不成立。
# 没给参数（compose 里 ENTRYPOINT 没有 CMD 的常态）才起服务本体。
if [ "$#" -gt 0 ]; then
  exec "$@"
fi

exec /app/bin/animeko-web

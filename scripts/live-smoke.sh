#!/usr/bin/env bash
# 端到端冒烟：登录门控 → 服务端个人数据 → 聚合订阅预览/导入 → 注销 → 限速 → 文件名字符集自检
set -uo pipefail
cd "$(dirname "$0")/.."
declare -a PIDS=()

BIN=backend/build/install/animeko-web/bin/animeko-web
DIST=$PWD/frontend/dist
pass=0; fail=0
ok()   { echo "  ok   $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL $1: $2"; fail=$((fail+1)); }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expect [$2] got [$3]"; fi; }
contains() { case "$3" in *"$2"*) ok "$1";; *) bad "$1" "输出里没有 [$2]：$(printf '%s' "$3" | head -c 220)";; esac; }

start() { # dataDir port [nolang] [extraEnv...]
  local d=$1 p=$2 nolang=${3:-}
  local extra=""
  [ $# -gt 3 ] && extra="${*:4}"
  local pre="env"
  # 故意不带 LANG 起第二个实例：模拟裸 java -jar 部署（Docker 镜像里已经设了 C.UTF-8）
  [ -n "$nolang" ] && pre="env -u LANG -u LC_ALL"
  # 端口必须先是空的。上一次跑挂了的 JVM（或任何别的服务）还在监听时，
  # readiness 会立刻成功，于是整份冒烟报告验证的是**另一个进程**——
  # 而且是被旧代码答应的旧进程，绿色结果完全可信却什么都证明不了。
  if ! port_free "$p"; then
    echo "端口 $p 已被占用（脚本自己没清干净，或别的服务在用）。先处理再跑。"
    command -v ss >/dev/null 2>&1 && ss -ltnp 2>/dev/null | grep ":$p "
    return 1
  fi
  # 额外变量放在 `env` 之后而不是和前面那些赋值并列：shell 只认命令字之前
  # 字面写出来的赋值，变量展开出来的 A=b 会被当成命令名（No such file or directory）
  # 自动导入必须显式清空：脚本若在设了 ANIMEKO_SUBSCRIPTION_AUTO_IMPORT 的终端里跑，
  # 后台导入会悄悄往「全新目录」里塞源，后面「没有预置数据源」那条就会偶发失败。
  # 第 13 节要通过 $extra 主动打开它，env 参数会覆盖这里的空值。
  ( ANIMEKO_DATA_DIR=$d ANIMEKO_AUTH_USER=me ANIMEKO_AUTH_PASSWORD='s3cret-pass' \
    ANIMEKO_PORT=$p ANIMEKO_HOST=127.0.0.1 ANIMEKO_FRONTEND_DIR=$DIST \
    ANIMEKO_SUBSCRIPTION_AUTO_IMPORT='' ANIMEKO_SEED_SUBSCRIPTION_URL='' \
    $pre $extra $BIN ) > "$d/server.log" 2>&1 &
  PIDS+=("$!")
  printf '%s\n' "${PIDS[-1]}" > "$d/pid"
  for _ in $(seq 1 80); do
    curl -fsS -m 2 "http://127.0.0.1:$p/api/health" >/dev/null 2>&1 && break
    kill -0 "$(cat "$d/pid")" 2>/dev/null || break
    sleep 0.5
  done
  # readiness 成功还不够，得确认答应的是我们刚拉起的那个进程
  if ! kill -0 "$(cat "$d/pid")" 2>/dev/null; then
    echo "我们启动的进程已退出（端口 $p）"; tail -20 "$d/server.log"; return 1
  fi
  curl -fsS -m 2 "http://127.0.0.1:$p/api/health" >/dev/null 2>&1 || {
    echo "服务没起来：$p"; tail -20 "$d/server.log"; return 1; }
  return 0
}
stop() {
  local pid owned=0
  local -a keep=()
  [ -f "$1/pid" ] || return 0
  read -r pid < "$1/pid"
  for active in "${PIDS[@]}"; do
    if [ "$active" = "$pid" ]; then owned=1; else keep+=("$active"); fi
  done
  PIDS=("${keep[@]}")
  if [ "$owned" = "1" ]; then
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
  fi
}
cleanup() {
  for pid in "${PIDS[@]}"; do kill "$pid" 2>/dev/null || true; done
  for pid in "${PIDS[@]}"; do wait "$pid" 2>/dev/null || true; done
  PIDS=()
  if [ "${SMOKE_KEEP_LOGS:-0}" = "1" ]; then
    printf '冒烟日志保留：%s\n' "$WORK"
  else
    rm -rf -- "$WORK"
  fi
}

port_free() { # bash 的 /dev/tcp：连不上才是空的。
  # 不能用 curl /api/health 判 —— 别的 HTTP 服务回 404 时 curl 也失败，
  # 那会把「端口被占」误判成「空闲」，正好绕过这道防线。
  (exec 3<>/dev/tcp/127.0.0.1/"$1") 2>/dev/null && { exec 3>&- 3<&-; return 1; }
  return 0
}

require_fresh_build() {
  # 冒烟跑的是 build/install 与 frontend/dist 里的产物。改了源码忘了重新构建时，
  # 全套断言会对着旧字节码亮绿灯 —— 这是本脚本最危险的一种假通过。
  # 只看 src/main 与前端源文件：测试文件的新改动不影响产物，不该逼人重编。
  local stale=""
  local newer
  # 比的是 jar 本体，不是 bin 下的启动脚本：gradle 在输入没变时不会重写启动脚本，
  # 拿它的 mtime 判会把「刚构建过的产物」说成过期（我自己就被这条误判过一次）。
  local artifact
  artifact=$(dirname "$(dirname "$BIN")")/lib/animeko-web-0.1.0.jar
  [ -f "$artifact" ] || artifact=$BIN
  # 比的是 jar 的输入：**只有后端**。上一版加宽这里时误把 frontend/** 也塞进来了，
  # 于是「只改前端」也会报「jar 过期」——假警报会让人去重编一个并不过期的产物，
  # 久而久之就有人直接绕过这道闸门，那比没有闸门更糟。前端由下面 dist 那一格管。
  newer=$(find backend/src/main backend/build.gradle.kts backend/settings.gradle.kts \
      -newer "$artifact" -type f \
    \( -name '*.kt' -o -name '*.kts' -o -name '*.xml' -o -name '*.json' \) 2>/dev/null | head -3)
  [ -n "$newer" ] && stale="后端源码比 $(basename "$artifact") 新：$(printf '%s ' $newer)"
  if [ -f "$DIST/index.html" ]; then
    # 前端侧清单：src 与 public 下的任何东西都会进 dist
    newer=$(find frontend/src frontend/public frontend/index.html frontend/vite.config.ts \
        frontend/package.json frontend/package-lock.json frontend/tsconfig.json \
        -newer "$DIST/index.html" -type f \
      \( -name '*.ts' -o -name '*.tsx' -o -name '*.css' -o -name '*.html' -o -name '*.js' \
         -o -name '*.json' -o -name '*.png' -o -name '*.svg' -o -name '*.webmanifest' \) 2>/dev/null | head -3)
    [ -n "$newer" ] && stale="$stale 前端源码比 dist/index.html 新：$(printf '%s ' $newer)"
  fi
  if [ -n "$stale" ]; then
    echo "产物过期，冒烟会验证到旧构建。先重新构建（gradle installDist / npm run build）。"
    printf '  %s\n' "$stale"
    return 1
  fi
  return 0
}

WORK=$(mktemp -d "${TMPDIR:-/tmp}/animeko-live.XXXXXX")
A="$WORK/a"; B="$WORK/b"; C="$WORK/c"; E="$WORK/e"
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -p "$A" "$B"
export LANG=C.UTF-8 LC_ALL=C.UTF-8
[ -x "$BIN" ] || { echo "没有 $BIN，先 gradle installDist"; exit 1; }
require_fresh_build || exit 1
start "$A" 18099 || exit 1
JAR=$A/cookies.txt; BASE=http://127.0.0.1:18099


echo "== 1. 门控"
H=$(curl -sS -m 5 "$BASE/api/health")
contains "health 说需要登录" '"authEnabled":true' "$H"
contains "health 说未认证" '"authenticated":false' "$H"
check "未登录取数据源" "401" "$(curl -sS -o /dev/null -w '%{http_code}' -m 5 "$BASE/api/selector/sources")"
check "未登录取个人数据" "401" "$(curl -sS -o /dev/null -w '%{http_code}' -m 5 "$BASE/api/data")"
check "未登录仍可打开前端" "200" "$(curl -sS -o /dev/null -w '%{http_code}' -m 5 "$BASE/")"

echo "== 2. 登录"
check "错口令" "401" "$(curl -sS -o /dev/null -w '%{http_code}' -m 5 -X POST "$BASE/api/session/login" -d '{"username":"me","password":"nope"}')"
R=$(curl -sS -m 5 -D "$A/h.txt" -c "$JAR" -X POST "$BASE/api/session/login" -H "Origin: $BASE" -d '{"username":"me","password":"s3cret-pass"}')
HDR=$(tr -d '\r' < "$A/h.txt")
contains "登录返回已认证" '"authenticated":true' "$R"
contains "Set-Cookie 有 HttpOnly" 'HttpOnly' "$HDR"
contains "Set-Cookie 有 SameSite" 'SameSite=Strict' "$HDR"
contains "Set-Cookie 有 Max-Age" 'Max-Age=' "$HDR"
contains "jar 里有会话号" 'animeko_session' "$(cat "$JAR")"
# `__Host-` 前缀只在开了 Secure（= 确实是 https）时才用：默认部署是 http，
# 带前缀浏览器会整条丢弃 Set-Cookie，症状是「口令对了却一直在登录页」
SCNAME=$(printf '%s' "$HDR" | tr ';' '\n' | grep -o 'Set-Cookie: *[^= ]*' | head -1 | sed 's/.*: *//')
check "http 部署的会话 Cookie 名不带 __Host- 前缀" "animeko_session" "$SCNAME"
# 读侧必须两个名字都试：改过 ANIMEKO_COOKIE_SECURE 的浏览器会同时送出一份陈旧的
# __Host- 和一份新登录种下的普通名。只认优先的那个 → 每个请求 401，而注销接口自己
# 也要鉴权、同样 401，Cookie 永远清不掉（实测过，不修就是把人锁在门外）。
SVAL=$(grep animeko_session "$JAR" | awk '{print $NF}')
check "陈旧 __Host- 不遮蔽有效会话" "200" "$(curl -sS -o /dev/null -w '%{http_code}' -m 5 -H "Cookie: __Host-animeko_session=deadbeef; animeko_session=$SVAL" "$BASE/api/selector/sources")"
# 登录接口现在先读体再判限速，所以它自己必须有一个远小于 1MB 的上限，
# 否则被限流的客户端照样能按请求体压带宽与堆
BIG=$(python3 -c "print('{\"username\":\"me\",\"password\":\"' + 'a'*40000 + '\"}')")
check "登录请求体超过 32KB 被拒" "400" "$(curl -sS -o /dev/null -w '%{http_code}' -m 5 -X POST "$BASE/api/session/login" -d "$BIG")"
contains "响应不回显口令" 'NOLEAK' "$(printf '%s' "$R$HDR" | grep -ci 's3cret-pass' | sed 's/^0$/NOLEAK/')"
check "登录后取数据源" "200" "$(curl -sS -b "$JAR" -o /dev/null -w '%{http_code}' -m 5 "$BASE/api/selector/sources")"
# 全新数据目录里不该有任何源：以前会预置两条指向 example-*.test 的示例源
check "全新安装没有预置数据源" "[]" "$(curl -sS -b "$JAR" -m 5 "$BASE/api/selector/sources" | tr -d '[:space:]')"
contains "登录后 health 已认证" '"authenticated":true' "$(curl -sS -b "$JAR" -m 5 "$BASE/api/health")"
check "跨站写仍被拒（带会话也一样）" "403" "$(curl -sS -b "$JAR" -o /dev/null -w '%{http_code}' -m 5 -X PUT "$BASE/api/data" -H 'Origin: https://evil.example' -d '{}')"
check "登录页自身可跨站？不应可" "403" "$(curl -sS -o /dev/null -w '%{http_code}' -m 5 -X POST "$BASE/api/session/login" -H 'Origin: https://evil.example' -d '{"username":"me","password":"s3cret-pass"}')"

echo "== 3. 个人数据落服务端"
check "首次读是空文档" "{}" "$(curl -sS -b "$JAR" -m 5 "$BASE/api/data")"
check "写入收藏" "200" "$(curl -sS -b "$JAR" -o /dev/null -w '%{http_code}' -m 5 -X PUT "$BASE/api/data" -d '{"favorites":[{"id":41,"title":"孤独摇滚"}],"progress":{"me:1":12.5}}')"
contains "磁盘上有 userdata.json" '孤独摇滚' "$(cat "$A/userdata.json" 2>/dev/null || echo MISSING)"
contains "结构可回读" '孤独摇滚' "$(curl -sS -b "$JAR" -m 5 "$BASE/api/data")"
check "非对象被拒" "400" "$(curl -sS -b "$JAR" -o /dev/null -w '%{http_code}' -m 5 -X PUT "$BASE/api/data" -d '[1,2]')"
check "覆盖写" "200" "$(curl -sS -b "$JAR" -o /dev/null -w '%{http_code}' -m 5 -X PUT "$BASE/api/data" -d '{"favorites":[]}')"
# 目录不存在时 ls 报错、grep -c 仍然输出 0：那会把「压根没查」报成「查过且干净」
if [ -d "$A" ]; then
  check "残留 tmp 文件" "0" "$(find "$A" -maxdepth 1 -name '*.tmp' | wc -l | tr -d ' ')"
else
  bad "数据目录不存在，无法检查残留临时文件" "$A"
fi

echo "== 4. 聚合订阅（真实订阅站）"
# 可以用 SMOKE_SUB 指向别的订阅源（例如一个必然连不上或只给 0 条的假地址），
# 用来验证「订阅站挂了」时这份脚本是响亮地失败，而不是静默跳过一堆断言
SUB="${SMOKE_SUB:-https://sub.creamycake.org/v1/css1.json}"
P=$(curl -sS -b "$JAR" -m 60 -X POST "$BASE/api/selector/subscriptions/preview" -d "{\"url\":\"$SUB\"}")
CNT=$(printf '%s' "$P" | python3 -c 'import json,sys;print(len(json.load(sys.stdin).get("candidates",[])))' 2>/dev/null || echo ERR)
if [ "$CNT" = "ERR" ]; then
  bad "预览返回不可解析" "$(printf '%s' "$P" | head -c 200)"
elif [ "${CNT:-0}" -gt 3 ] 2>/dev/null; then
  ok "预览到 $CNT 个候选源"
else
  # 以前这里是「只要能解析就 ok」，包括 0 个：订阅站挂了或改版时，
  # 下面那一整段导入断言会被静默跳过，脚本照样退出 0 —— 绿灯什么也没证明
  bad "订阅站只给出 $CNT 个候选，导入链路无法验证" "$SUB"
fi

# ---- 与订阅站无关的安全断言：无条件跑，不能被上面的 if 吞掉 ----
SSRF=$(curl -sS -b "$JAR" -w '\n%{http_code}' -m 10 -X POST "$BASE/api/selector/subscriptions/preview" -d '{"url":"http://127.0.0.1:8080/x.json"}')
check "内网订阅地址被拦" "403" "${SSRF##*$'\n'}"
# 拒绝原因里有「主机名 -> 内网 IP」，那对任何能连到这台机器的人都是网络拓扑泄露：只进日志
if printf '%s' "$SSRF" | grep -q '127\.0\.0\.1'; then bad "403 响应不得回显内网地址" "$SSRF"; else ok "403 响应不回显内网地址"; fi
check "没勾选就导入被拒" "400" "$(curl -sS -b "$JAR" -o /dev/null -w '%{http_code}' -m 10 -X POST "$BASE/api/selector/subscriptions/import" -d "{\"url\":\"$SUB\"}")"

if [ "${CNT:-0}" -gt 3 ] 2>/dev/null; then
  FIRST=$(printf '%s' "$P" | python3 -c 'import json,sys;print(json.load(sys.stdin)["candidates"][0]["name"])')
  SITE=$(printf '%s' "$P" | python3 -c 'import json,sys;print(json.load(sys.stdin)["candidates"][0]["site"])')
  contains "候选带站点主机名" '.' "$SITE"
  I=$(curl -sS -b "$JAR" -m 60 -X POST "$BASE/api/selector/subscriptions/import" -d "{\"url\":\"$SUB\",\"names\":[\"$FIRST\"]}")
  ADDED=$(printf '%s' "$I" | python3 -c 'import json,sys;print(",".join(json.load(sys.stdin)["added"]))' 2>/dev/null || echo ERR)
  check "导入返回 added" "$FIRST" "$ADDED"
  # 数据目录里只有导入的那一个源（不再预置两条示例源）
  check "源文件已落盘" "1" "$(ls "$A/selectors" | wc -l | tr -d ' ')"
  contains "列表里能看到" "$FIRST" "$(curl -sS -b "$JAR" -m 10 "$BASE/api/selector/sources")"
  U=$(curl -sS -b "$JAR" -m 60 -X POST "$BASE/api/selector/subscriptions/import" -d "{\"url\":\"$SUB\",\"names\":[\"$FIRST\"]}")
  check "再导入同一条报 updated" "$FIRST" "$(printf '%s' "$U" | python3 -c 'import json,sys;print(",".join(json.load(sys.stdin)["updated"]))')"
  check "重复导入不新增文件" "1" "$(ls "$A/selectors" | wc -l | tr -d ' ')"
fi

echo "== 5. 静态资源与缓存"
ASSET=$(curl -sS -b "$JAR" -m 5 "$BASE/" | grep -o 'assets/index-[^"]*\.js' | head -1)
if [ -z "$ASSET" ]; then
  # 不守住这一格，"$BASE/$ASSET" 就退化成 "$BASE/" —— 首页回 200，于是凭空记一个 ok。
  # 填一个必然 404 的路径，让后面几项响亮地失败，而不是被首页蒙过去
  bad "首页没有引用任何 JS 产物（构建或托管坏了）" "$(curl -sS -m 5 "$BASE/" | head -c 160)"
  ASSET="assets/NO-BUILD-PRESENT.js"
fi
check "带哈希的 JS 存在" "200" "$(curl -sS -o /dev/null -w '%{http_code}' -m 5 "$BASE/$ASSET")"
contains "强缓存" 'immutable' "$(curl -sS -D - -o /dev/null -m 5 "$BASE/$ASSET" | tr -d '\r' | grep -i '^cache-control')"
contains "入口不缓存" 'no-cache' "$(curl -sS -D - -o /dev/null -m 5 "$BASE/" | tr -d '\r' | grep -i '^cache-control')"
check "缺失资源 404 而非 SPA 回退" "404" "$(curl -sS -o /dev/null -w '%{http_code}' -m 5 "$BASE/assets/nope-does-not-exist.js")"
check "SPA 路由回退 200" "200" "$(curl -sS -o /dev/null -w '%{http_code}' -m 5 "$BASE/favorites")"
contains "文本响应 gzip" 'gzip' "$(curl -sS -D - -o /dev/null -H 'Accept-Encoding: gzip' -m 5 "$BASE/$ASSET" | tr -d '\r' | grep -i '^content-encoding')"

echo "== 6. 注销与会话作废"
SID=$(grep animeko_session "$JAR" | awk '{print $NF}')
check "注销" "200" "$(curl -sS -b "$JAR" -c "$JAR" -o /dev/null -w '%{http_code}' -m 5 -X POST "$BASE/api/session/logout")"
check "注销后数据源要重新登录" "401" "$(curl -sS -b "$JAR" -o /dev/null -w '%{http_code}' -m 5 "$BASE/api/selector/sources")"
check "注销后个人数据同样" "401" "$(curl -sS -b "$JAR" -o /dev/null -w '%{http_code}' -m 5 "$BASE/api/data")"
check "旧会话号不能当 Bearer 复用" "401" "$(curl -sS -o /dev/null -w '%{http_code}' -m 5 -H "Authorization: Bearer $SID" "$BASE/api/selector/sources")"
check "Basic 口令依旧可用（给命令行/播放器）" "200" "$(curl -sS -o /dev/null -w '%{http_code}' -m 5 -u me:s3cret-pass "$BASE/api/selector/sources")"
contains "会话号不进日志" 'NOLEAK' "$(grep -c "$SID" "$A/server.log" | sed 's/^0$/NOLEAK/')"

echo "== 7. 限速：超限后所有候选都不得继续验密"
for _ in $(seq 1 8); do
  curl -sS -o /dev/null -m 5 -X POST "$BASE/api/session/login" -d '{"username":"me","password":"bad"}'
done
# 超限期间**继续猜错**：不再给它 yes/no 的反馈
RESP=$(curl -sS -D "$A/429.txt" -o /dev/null -w '%{http_code}' -m 5 -X POST "$BASE/api/session/login" -d '{"username":"me","password":"bad"}')
check "连错 8 次后再猜错是 429 而不是 401" "429" "$RESP"
contains "429 带 Retry-After" 'Retry-After' "$(tr -d '\r' < "$A/429.txt")"
# 不因候选真假改变超限响应，否则 429/200 仍是可连续查询的口令预言机。
# 已登录随机会话另有豁免；正确的代理来源配置用于避免共享限流桶。
check "超限期间正确候选也不被验证" "429" "$(curl -sS -o /dev/null -w '%{http_code}' -m 5 -X POST "$BASE/api/session/login" -d '{"username":"me","password":"s3cret-pass"}')"
check "超限期间再次猜测仍被拒" "429" "$(curl -sS -o /dev/null -w '%{http_code}' -m 5 -X POST "$BASE/api/session/login" -d '{"username":"me","password":"bad"}')"

echo "== 8. 文件名字符集自检（LANG 被拿掉的第二个实例）"
start $B 18098 nolang || exit 1
JB=$B/cookies.txt; BB=http://127.0.0.1:18098
curl -sS -m 5 -c $JB -X POST $BB/api/session/login -d '{"username":"me","password":"s3cret-pass"}' > /dev/null
E=$(curl -sS -b $JB -m 5 -X POST $BB/api/selector/sources -d '{"factoryId":"web-selector","version":2,"arguments":{"name":"中文源名测试","searchConfig":{"searchUrl":"https://a.test/s"}}}')
contains "坏字符集下给出可读 remedy" 'LANG=C.UTF-8' "$E"
contains "启动日志里也有自检结论" 'sun.jnu.encoding' "$(cat "$B/server.log")"
check "坏字符集下列表接口仍可用" "200" "$(curl -sS -b $JB -o /dev/null -w '%{http_code}' -m 5 $BB/api/selector/sources)"
stop $B

echo "== 9. 门控与预言机（用一个干净的实例：限速表是进程内存，前面章节已经把 A 打到限速了）"
mkdir -p "$C"
start $C 18095 || exit 1
CB=http://127.0.0.1:18095

for pth in "//api/selector/sources" "/%61pi/selector/sources" "/api//selector/sources" "//api/data" "/%61pi/data" "//api/selector/subscriptions/preview" "//api/proxy?url=http://example.test/a.m3u8"; do
  code=$(curl -sS --path-as-is -o /dev/null -w '%{http_code}' -m 5 "$CB$pth")
  # 只接受 401：404/405 既可能表示「路由被改名/删掉」，也可能表示门禁位置变了。
  # 把它们当通过，等于只要路由不存在就永远绿。带凭据的正向对照仍在下面一条。
  case "$code" in
    401) ok "$pth -> $code" ;;
    *) bad "$pth 绕过了鉴权或路由不存在" "HTTP $code：$(curl -sS --path-as-is -m5 "$CB$pth" | head -c 120)" ;;
  esac
done
# 反面对照：归一化之后这些写法确实还是同一个接口，否则上面的 401 只是「没匹配上」
GOOD=$(curl -sS -m5 -c "$C/j.txt" -X POST "$CB/api/session/login" -d '{"username":"me","password":"s3cret-pass"}')
contains "  对照：登录可用" '"authenticated":true' "$GOOD"
# 数据源列表在全新目录里是空数组（不再预置示例源），对照要精确比 JSON 而不是找某个字段
check "  对照：//api 带凭据仍打到接口" "[]" "$(curl -sS --path-as-is -b "$C/j.txt" -m5 "$CB//api/selector/sources" | tr -d '[:space:]')"

echo "== 10. 免鉴权接口不能当口令预言机"
contains "错误口令：健康检查说不" '"authenticated":false' "$(curl -sS -m5 -u me:totally-wrong "$CB/api/health")"
contains "正确口令：健康检查也说不（只看会话 Cookie）" '"authenticated":false' "$(curl -sS -m5 -u me:s3cret-pass "$CB/api/health")"
for i in $(seq 1 8); do curl -sS -o /dev/null -m5 -u me:guess$i "$CB/api/selector/sources"; done
check "保护接口上的凭据猜测同样计入限速" "429" "$(curl -sS -o /dev/null -w '%{http_code}' -m5 -u me:guess9 "$CB/api/selector/sources")"
# Basic/Bearer 与登录接口共享原子验密预算，不能成为限流旁路。
check "超限期间 Basic 候选也被拒绝验密" "429" "$(curl -sS -o /dev/null -w '%{http_code}' -m5 -u me:s3cret-pass "$CB/api/selector/sources")"
check "限速期间会话 Cookie 照常可用" "200" "$(curl -sS -b "$C/j.txt" -o /dev/null -w '%{http_code}' -m5 "$CB/api/data")"

echo "== 11. 质询弹窗只给用得上它的客户端"
NAV=$(curl -sS -D - -o /dev/null -m5 -H 'Sec-Fetch-Mode: navigate' "$CB/api/selector/sources")
contains "地址栏访问有 Basic 质询" 'WWW-Authenticate' "$(printf '%s' "$NAV" | tr -d '\r')"
XHR=$(curl -sS -D - -o /dev/null -m5 -H 'Sec-Fetch-Mode: cors' "$CB/api/selector/sources")
if printf '%s' "$XHR" | tr -d '\r' | grep -qi 'www-authenticate'; then
  bad "XHR 不该收到质询" "浏览器会为分片请求弹原生口令框，盖住前端登录页"
else
  ok "XHR/媒体请求没有质询"
fi
CURLONLY=$(curl -sS -D - -o /dev/null -m5 "$CB/api/selector/sources")
contains "curl（无 Sec-Fetch-Mode）仍有质询" 'WWW-Authenticate' "$(printf '%s' "$CURLONLY" | tr -d '\r')"

echo "== 12. 个人数据是条件写，不是静默覆盖"
ET=$(curl -sS -b "$C/j.txt" -D - -o /dev/null -m5 "$CB/api/data" | tr -d '\r' | grep -i '^etag' | sed 's/.*"\(.*\)".*/\1/')
check "GET 给了 SHA-256 ETag" "64" "${#ET}"
curl -sS -b "$C/j.txt" -o /dev/null -m5 -X PUT "$CB/api/data" -d '{"favorites":[{"id":1}]}'
ET2=$(curl -sS -b "$C/j.txt" -D - -o /dev/null -m5 "$CB/api/data" | tr -d '\r' | grep -i '^etag' | sed 's/.*"\(.*\)".*/\1/')
check "版本随写入前进" "1" "$([ "$ET" != "$ET2" ] && echo 1)"
check "拿着旧版本覆盖会被拒" "409" "$(curl -sS -b "$C/j.txt" -o /dev/null -w '%{http_code}' -m5 -X PUT "$CB/api/data" -H "If-Match: \"$ET\"" -d '{"favorites":[]}')"
contains "冲突有可识别的错误码" 'DATA_STALE' "$(curl -sS -b "$C/j.txt" -m5 -X PUT "$CB/api/data" -H "If-Match: \"$ET\"" -d '{"favorites":[]}')"
check "带着当前版本可以写" "200" "$(curl -sS -b "$C/j.txt" -o /dev/null -w '%{http_code}' -m5 -X PUT "$CB/api/data" -H "If-Match: \"$ET2\"" -d '{"favorites":[{"id":2}]}')"
stop $C

echo "== 13. 开机自动导入订阅（compose 里默认打开的那条路径）"
# 数据目录直接用一个全新子目录，重启时**不删**，这样才等价于「容器重启、卷还在」
mkdir -p "$E"
SUBENV="ANIMEKO_SUBSCRIPTION_AUTO_IMPORT=$SUB ANIMEKO_SUBSCRIPTION_AUTO_TIERS=0"
start $E 18094 "" "$SUBENV" || exit 1
EB=http://127.0.0.1:18094
curl -sS -m 5 -c $E/j.txt -X POST $EB/api/session/login -d '{"username":"me","password":"s3cret-pass"}' > /dev/null
# 自动导入跑在后台协程里，起来到导完之间有几秒空档，等到源真的出现为止
wait_import() { # base jar
  local n=0
  while [ "$n" -lt 40 ]; do
    curl -sS -b "$2" -m 8 "$1/api/selector/sources" | grep -q '稀饭动漫' && return 0
    n=$((n+1)); sleep 1
  done
  return 1
}
wait_import $EB $E/j.txt
check "启动日志记了导入结果" "1" "$(grep -c '订阅自动导入完成' "$E/server.log")"
S1=$(curl -sS -b $E/j.txt -m 8 "$EB/api/selector/sources")
check "t0 白名单里的源导进来了" "1" "$(echo "$S1" | grep -c 稀饭动漫)"
check "tier 0 之外的源没被塞进来" "0" "$(echo "$S1" | grep -c 影视森林)"
# 关掉的源不能因为重启被偷偷打开：那是替用户重做他明确拒绝过的决定
curl -sS -b $E/j.txt -m 8 -X POST "$EB/api/selector/sources/%E7%A8%80%E9%A5%AD%E5%8A%A8%E6%BC%AB/enable" \
  -d '{"enabled":false}' > /dev/null
check "手工关掉稀饭动漫" '"enabled":false' "$(curl -sS -b $E/j.txt -m 8 "$EB/api/selector/sources" | grep -o '"enabled":false' | head -1)"
stop $E
start $E 18093 "" "$SUBENV" || exit 1
EB=http://127.0.0.1:18093
curl -sS -m 5 -c $E/j2.txt -X POST $EB/api/session/login -d '{"username":"me","password":"s3cret-pass"}' > /dev/null
n=0
while [ "$n" -lt 40 ]; do
  S2=$(curl -sS -b $E/j2.txt -m 8 "$EB/api/selector/sources")
  echo "$S2" | grep -q '稀饭动漫' && break
  n=$((n+1)); sleep 1
done
contains "重启后源还在（卷没白留）" '稀饭动漫' "$S2"
check "重启没有把关掉的源偷偷打开" "0" "$(echo "$S2" | python3 -c "
import json,sys
print(1 if any(s['arguments']['name'] == '稀饭动漫' and s['enabled'] for s in json.load(sys.stdin)) else 0)")"
check "其它源照常是启用的（不是「全部变 false」这种假通过）" "1" "$(echo "$S2" | python3 -c "
import json,sys
print(1 if any(s['enabled'] for s in json.load(sys.stdin)) else 0)")"
stop $E

echo
echo "通过 $pass，失败 $fail"
[ "$fail" = "0" ] || { echo "--- A 日志尾部"; tail -12 "$A/server.log"; exit 1; }
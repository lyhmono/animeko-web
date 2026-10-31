#!/usr/bin/env bash
# 在一次性干净副本里跑后端测试：没有 build/ 残留、没有 TMPDIR，等价 CI 的干净检出。
# 用法: scripts/clean-room-test.sh <git-ref> <日志路径>   （KEEP=1 保留副本）
set -uo pipefail
cd "$(dirname "$0")/.."
REPO=$PWD
TOOLS=$HOME/.hermes/cache/scratch/animeko-toolchain
FIX=$PWD/../animeko-web-fixes-eb3c84b
REF=${1:?用法: clean-room-test.sh <git-ref|WORKTREE> <日志路径> [FAKE_HOME]}
LOG=${2:?给个日志名}
# 第三个参数模拟「CI runner 上不存在兜底路径」：把 HOME 指向一个空目录
FAKE_HOME=${3:-}
WORK=$(mktemp -d "/tmp/animeko-cleanroom.XXXXXX")
PIDS=''
cleanup() {
  for pid in $PIDS; do kill "$pid" 2>/dev/null || true; done
  if [ "${KEEP:-0}" = 1 ]; then echo "副本保留：$WORK"; else rm -rf -- "$WORK"; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [ "$REF" = WORKTREE ]; then
  # 用 stash 对象导出当前工作树（含未提交改动），不碰索引、不改仓库
  TREE_SHA=$(git -C "$REPO" stash create "clean-room probe") || true
  if [ -z "$TREE_SHA" ]; then TREE_SHA=$(git -C "$REPO" rev-parse HEAD); fi
  echo "== 导出工作树 $TREE_SHA"
  git -C "$REPO" archive "$TREE_SHA" | tar -x -C "$WORK" || { echo '导出工作树失败'; exit 1; }
  # stash create 只含已跟踪文件的改动，新增未跟踪文件需要单独补
  git -C "$REPO" ls-files --others --exclude-standard -z | while IFS= read -r -d '' f; do
    mkdir -p "$WORK/$(dirname "$f")" && cp -a "$REPO/$f" "$WORK/$f"
  done
else
  git -C "$REPO" archive "$REF" | tar -x -C "$WORK" || { echo '导出 ref 失败'; exit 1; }
fi
test -d "$WORK/backend" || { echo '副本里没有 backend'; exit 1; }
mkdir -p "$WORK/tmp"
if [ "$REF" = WORKTREE ]; then
  echo "== 干净副本 $WORK（工作树 $TREE_SHA）：无 TMPDIR、无 build 残留"
else
  echo "== 干净副本 $WORK（ref $(git -C "$REPO" rev-parse --short "$REF")）：无 TMPDIR、无 build 残留"
fi

# 与主审的后端构建串行，避免抢同一份 Gradle 缓存写
exec 9>"$FIX/backend-build.lock"
flock 9

(
  cd "$WORK/backend" || exit 1
  unset TMPDIR
  export JAVA_HOME="$TOOLS/jdk"
  export PATH="$TOOLS/jdk/bin:$PATH"
  export GRADLE_USER_HOME="$TOOLS/gradle-cache"
  # CI runner 上没有 ~/.hermes：user.home 在 Linux 取自 passwd，不跟 HOME 走，
  # 所以必须用 JAVA_TOOL_OPTIONS 把 user.home 指到空目录，才等价于 runner 的真实形状
  if [ -n "$FAKE_HOME" ]; then
    export HOME="$FAKE_HOME"
    export JAVA_TOOL_OPTIONS="-Duser.home=$FAKE_HOME"
    echo "   HOME=$FAKE_HOME + -Duser.home=$FAKE_HOME（模拟 CI runner 上没有 ~/.hermes）"
  fi
  exec "$TOOLS/gradle-9.3.1/bin/gradle" test --no-daemon --console=plain \
    --no-build-cache --rerun-tasks \
    -Dorg.gradle.jvmargs="-Xmx1g -XX:MaxMetaspaceSize=512m -Djava.io.tmpdir=$WORK/tmp" \
    -Dorg.gradle.parallel=false --max-workers=2 \
    -Pkotlin.compiler.execution.strategy=in-process
) > "$LOG" 2>&1 &
PID=$!
PIDS="$PIDS $PID"
wait $PID; CODE=$?
PIDS=${PIDS// $PID/}

echo "== 退出码 $CODE  日志 $LOG"
# FROM-CACHE / UP-TO-DATE 意味着根本没执行，这种"绿"要当场识破
if grep -qE '^> Task :test (FROM-CACHE|UP-TO-DATE)$' "$LOG"; then
  echo '   !! :test 没有真的执行（缓存命中），本次结果不可信'
  exit 70
fi
grep -E '\(\) FAILED$' "$LOG" | sed 's/^/   /' || echo '   （没有 FAILED 用例行）'
grep -E 'tests completed|BUILD (SUCCESSFUL|FAILED)' "$LOG" | sed 's/^/   /' || true
exit $CODE

#!/usr/bin/env bash
# =====================================================================
#  ci-seed-demo-data.sh —— CI 里「起一次后端播种演示数据」的**单一实现**
#
#  为什么需要它（2026-10-08 CI 实测）：
#    system / analysis / web 的 *IT 与确定性评测集都按"演示库"写（21 机构 / 单棵 11 部门树 /
#    300 用户 / 角色权限矩阵 / 15 万订单），而这些 IT 自己都显式
#    `guarantee.data-init.enabled=false`；CI 的 job 只起 MySQL/Redis service、**从不启动
#    guarantee-web** → DataInitializer 不执行 → 库是空的 → 用例以"查不到 admin / 机构数 0≠21"
#    的面目红（Job1 首跑 44 个用例、Job2 的 EvaluationDeterministicIT 12 个用例）。
#    本脚本把两个 job 需要的这一步收敛成一份定义：Job1（verify）与 Job2（确定性集）都调它；
#    Job3 自己起后端并复用同一实例跑评测，因此不用它。
#
#  用法（job 里 DB_* 已由 workflow 的 env 提供）：
#      bash scripts/ci-seed-demo-data.sh [jar路径] [端口]
#    默认：jar = guarantee-web/target/guarantee-ai-admin.jar，端口 = 8093
#    （绝不用 8081 = 用户自己的实例 / 8092 = Job3 的实例）
#
#  退出码：0 = 播种成功且形状正确；非 0 = 打印原因 + 应用日志尾部
# =====================================================================
set -euo pipefail

JAR="${1:-guarantee-web/target/guarantee-ai-admin.jar}"
PORT="${2:-8093}"
LOG="${CI_SEED_LOG:-ci-seed-app.log}"

: "${DB_HOST:=127.0.0.1}"
: "${DB_PORT:=3306}"
: "${DB_NAME:=guarantee_ai_admin}"
: "${DB_USER:=guarantee}"
: "${DB_PASSWORD:=guarantee@2026}"

q() {
  mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASSWORD" -N -B \
    -e "SELECT $1 FROM $DB_NAME.$2" 2>/dev/null || echo 0
}

if [ ! -f "$JAR" ]; then
  echo "::error::找不到 $JAR（请先 mvn -pl guarantee-web -am -DskipTests package/install）" >&2
  exit 1
fi
# 注意：本脚本开了 `set -o pipefail`，而 `unzip -l | grep -q` 会因 grep 提前退出触发
# SIGPIPE（unzip 以 141 结束）→ 管道整体非 0 → 把一个**好 jar** 误判成坏 jar。
# 因此这一处显式关掉 pipefail（Job3 的同款检查没有 pipefail，所以那边没这个问题）。
if ! (set +o pipefail; unzip -l "$JAR" | grep -q 'BOOT-INF/'); then
  echo "::error::$JAR 不是 fat jar（BOOT-INF 缺失）——不要传 -Dspring-boot.repackage.skip=true" >&2
  exit 1
fi

echo "开始播种：jar=$JAR port=$PORT db=$DB_HOST:$DB_PORT/$DB_NAME"
java -jar "$JAR" --server.port="$PORT" > "$LOG" 2>&1 &
APP_PID=$!
trap 'kill "$APP_PID" 2>/dev/null || true' EXIT

seeded=0
for _ in $(seq 1 120); do
  if grep -q '演示数据初始化完成' "$LOG"; then
    seeded=1
    break
  fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "::error::播种进程提前退出" >&2
    tail -n 60 "$LOG" >&2
    exit 1
  fi
  sleep 2
done
if [ "$seeded" != "1" ]; then
  echo "::error::240s 内未见「演示数据初始化完成」" >&2
  tail -n 60 "$LOG" >&2
  exit 1
fi
grep '演示数据初始化完成' "$LOG" || true

# DataInitializer.run() 带 @Transactional：日志在方法内打印、提交在其后
# → 必须**以库里可见的状态为准**，而不是"看到日志就杀进程"。
users=0
for _ in $(seq 1 30); do
  users=$(q 'COUNT(*)' sys_user)
  if [ "$users" = "300" ]; then
    break
  fi
  sleep 2
done

kill "$APP_PID" 2>/dev/null || true
wait "$APP_PID" 2>/dev/null || true
trap - EXIT

# 形状断言：DataInitializer 的规格一旦漂移，就在这里**响亮地**红，
# 而不是让下游 IT 以"数据缺失"的面目失败（首跑就是这么红的）。
orgs=$(q 'COUNT(*)' sys_org)
depts=$(q 'COUNT(*)' sys_department)
roots=$(q 'COUNT(*)' "sys_department WHERE parent_id = 0")
tender=$(q 'COUNT(*)' tender_order)
perf=$(q 'COUNT(*)' performance_order)
echo "播种结果：机构=$orgs 部门=$depts（顶级=$roots）用户=$users 投标订单=$tender 履约订单=$perf"

if [ "$orgs" != "21" ] || [ "$depts" != "11" ] || [ "$roots" != "1" ] || [ "$users" != "300" ] \
  || [ "$tender" -lt 1000 ] || [ "$perf" -lt 1000 ]; then
  echo "::error::演示数据形状不符合预期（期望 21 机构 / 11 部门 / 1 个顶级 / 300 用户 / 订单各 ≥1000）" >&2
  exit 1
fi

#!/usr/bin/env bash
# ============================================================
# 六维达康 · 水卡生命周期验收环境编排（唯一入口）
#
# 每轮验收 = rebuild（down -v → up → 等就绪 → 按文件名顺序执行 ../mysql/migrations/*.sql → 灌
# acc-seed.sql）→ backend-start → sim-start → 跑 miniapp/e2e/run-card-lifecycle.js。
# schema 来源只有 ../mysql/init（compose 首启自动执行）与 ../mysql/migrations，不存在第三份 DDL。
#
# 安全边界：本脚本只操作 dakang-acc-* 容器（compose 项目 dakang-acc）；
# 所有 SQL 都通过 `docker exec dakang-acc-mysql` 执行，物理上到不了主库 3308。
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# 凭据一律来自仓库根 .env（不入库；模板见 .env.example），本文件不留口令明文。
# set -a 让 source 进来的键自动导出，供下面的 compose 与后端命令行参数使用。
if [ -f "${REPO_ROOT}/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "${REPO_ROOT}/.env"
  set +a
fi
# fail-closed：缺任何一项立即退出，绝不回落到硬编码口令——回落会让「验收口令必须与主库不同」
# 这道防触主库护栏在配置缺失时静默失效。
: "${DAKANG_ACC_DB_PASSWORD:?请在仓库根 .env 配置 DAKANG_ACC_DB_PASSWORD（参照 .env.example）}"
: "${DAKANG_ACC_REDIS_PASSWORD:?请在仓库根 .env 配置 DAKANG_ACC_REDIS_PASSWORD（参照 .env.example）}"
# 「验收口令 ≠ 主库口令」原本由源码写死两个不同常量保证；口令改为 .env 注入后，
# 这条前提只能由配置保证，故显式断言：两者一旦配成相同，误连 3308 就不再被认证挡下，护栏形同虚设。
if [ -n "${DAKANG_DB_PASSWORD:-}" ] && [ "${DAKANG_ACC_DB_PASSWORD}" = "${DAKANG_DB_PASSWORD}" ]; then
  echo "错误：DAKANG_ACC_DB_PASSWORD 与主库 DAKANG_DB_PASSWORD 相同，防误触主库的第二道护栏失效；请在 .env 改成不同口令。" >&2
  exit 2
fi

COMPOSE=(docker compose -f "${SCRIPT_DIR}/docker-compose.acc.yml")
MYSQL_CONTAINER="dakang-acc-mysql"
MYSQL_PWD="${DAKANG_ACC_DB_PASSWORD}"
REDIS_PWD="${DAKANG_ACC_REDIS_PASSWORD}"
DB_NAME="dakang"
BACKEND_PORT=13340
RUN_DIR="${SCRIPT_DIR}/.run"
JAR="${REPO_ROOT}/server/target/dakang-server.jar"
SIM="${REPO_ROOT}/tools/device-sim/sim.js"

mkdir -p "${RUN_DIR}"

acc_mysql() {
  # 只对验收容器执行。必须 -h127.0.0.1 走 TCP：首启时 entrypoint 的**临时初始化实例**
  # 只监听 socket（--skip-networking），走 socket 会把临时实例误判为就绪，
  # 随后临时实例关闭、正式实例尚未拉起，迁移就会撞上 ERROR 2002。
  docker exec -i "${MYSQL_CONTAINER}" mysql -h127.0.0.1 -uroot -p"${MYSQL_PWD}" --default-character-set=utf8mb4 "$@"
}

wait_mysql_ready() {
  # 就绪判据：正式实例（TCP）可查询 + 底座表已由 init SQL 建出。
  echo "等待验收 MySQL 就绪（init SQL 执行完毕 + 正式实例监听 TCP）..."
  for i in $(seq 1 120); do
    if acc_mysql -e "SELECT 1 FROM ${DB_NAME}.api_employee LIMIT 1" >/dev/null 2>&1; then
      echo "验收 MySQL 就绪。"
      return 0
    fi
    sleep 2
  done
  echo "错误：验收 MySQL 120 次探测内未就绪" >&2
  return 1
}

cmd_up() {
  "${COMPOSE[@]}" up -d
  wait_mysql_ready
}

cmd_down() {
  # -v 一并删除数据卷：下一轮 up 时 init SQL 重新执行，实现「每轮从 init+迁移重建」
  "${COMPOSE[@]}" down -v --remove-orphans
}

cmd_migrate() {
  # 与主库运维同一口径：按文件名（日期前缀）顺序执行全部迁移；迁移自身幂等/污染即中止
  local f
  for f in "${REPO_ROOT}"/deploy/mysql/migrations/*.sql; do
    echo "执行迁移：$(basename "$f")"
    acc_mysql "${DB_NAME}" < "$f"
  done
}

cmd_seed() {
  echo "灌入验收种子 acc-seed.sql"
  acc_mysql "${DB_NAME}" < "${SCRIPT_DIR}/acc-seed.sql"
  # 种子哨兵：验收 runner 开跑前也会复核这两条
  acc_mysql -e "SELECT ID, USER_PHONE FROM ${DB_NAME}.ws_user WHERE ID IN (9001, 9002); SELECT ID, PACKAGE_NAME FROM ${DB_NAME}.ws_package WHERE ID = 9501;"
}

cmd_rebuild() {
  cmd_down
  cmd_up
  cmd_migrate
  cmd_seed
  echo "验收库重建完成（init + migrations + seed）。"
}

cmd_backend_start() {
  if [ ! -f "${JAR}" ]; then
    echo "错误：缺少 ${JAR}；先执行 cd server && mvn -DskipTests package" >&2
    exit 1
  fi
  if [ -f "${RUN_DIR}/backend.pid" ] && kill -0 "$(cat "${RUN_DIR}/backend.pid")" 2>/dev/null; then
    echo "验收后端已在运行（pid $(cat "${RUN_DIR}/backend.pid")）"
    return 0
  fi
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  # 与生产同 profile；数据源/Redis/MQTT/端口/开关全部指向验收容器（命令行参数优先级最高）。
  # Pay-Sim 与测试登录同开关（mini.pay-sim.enabled）；设备监控关闭：验收场景不依赖离线/超时兜底，
  # 且避免无模拟器的 ACC-DEV-0002 在长跑中被翻离线，污染 S6「因范围被拒」的语义。
  TZ=Asia/Shanghai nohup "${JAVA_HOME}/bin/java" -Xms256m -Xmx512m -Dfile.encoding=UTF-8 \
    -jar "${JAR}" \
    --spring.profiles.active=prod \
    --server.port="${BACKEND_PORT}" \
    --spring.datasource.druid.url="jdbc:mysql://127.0.0.1:3309/${DB_NAME}?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&serverTimezone=UTC&nullCatalogMeansCurrent=true" \
    --spring.datasource.druid.username=root \
    --spring.datasource.druid.password="${MYSQL_PWD}" \
    --spring.redis.host=127.0.0.1 \
    --spring.redis.port=6381 \
    --spring.redis.password="${REDIS_PWD}" \
    --spring.ratelimiter.redis-address=redis://127.0.0.1:6381 \
    --spring.ratelimiter.redis-password="${REDIS_PWD}" \
    --mqtt.enabled=true \
    --mqtt.broker-url=tcp://127.0.0.1:1884 \
    --mqtt.client-id=dakang-server-acc \
    --mini.pay-sim.enabled=true \
    --dakang.device.monitor-enabled=false \
    > "${RUN_DIR}/backend.log" 2>&1 &
  echo $! > "${RUN_DIR}/backend.pid"
  echo "验收后端启动中（pid $(cat "${RUN_DIR}/backend.pid")），等待端口 ${BACKEND_PORT} ..."
  for i in $(seq 1 90); do
    local code
    code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:${BACKEND_PORT}/dakangApi/mini/package/list" -H 'Content-Type: application/json' -d '{}' || true)"
    if [ "${code}" != "000" ] && [ -n "${code}" ]; then
      echo "验收后端已就绪（HTTP ${code}）。"
      return 0
    fi
    sleep 2
  done
  echo "错误：验收后端 180s 内未就绪，见 ${RUN_DIR}/backend.log" >&2
  return 1
}

cmd_backend_stop() {
  if [ -f "${RUN_DIR}/backend.pid" ]; then
    kill "$(cat "${RUN_DIR}/backend.pid")" 2>/dev/null || true
    rm -f "${RUN_DIR}/backend.pid"
    echo "验收后端已停止。"
  fi
}

cmd_sim_start() {
  if [ -f "${RUN_DIR}/sim.pid" ] && kill -0 "$(cat "${RUN_DIR}/sim.pid")" 2>/dev/null; then
    echo "设备模拟器已在运行（pid $(cat "${RUN_DIR}/sim.pid")）"
    return 0
  fi
  # 正常模式：心跳 + 遥测 + ack/result（出水指令回 actualMl=4980，S7 退差断言依赖该值）
  BROKER=tcp://127.0.0.1:1884 nohup node "${SIM}" ACC-DEV-0001 > "${RUN_DIR}/sim.log" 2>&1 &
  echo $! > "${RUN_DIR}/sim.pid"
  echo "设备模拟器已启动（ACC-DEV-0001 → tcp://127.0.0.1:1884，pid $(cat "${RUN_DIR}/sim.pid")）"
}

cmd_sim_stop() {
  if [ -f "${RUN_DIR}/sim.pid" ]; then
    kill "$(cat "${RUN_DIR}/sim.pid")" 2>/dev/null || true
    rm -f "${RUN_DIR}/sim.pid"
    echo "设备模拟器已停止。"
  fi
}

cmd_status() {
  "${COMPOSE[@]}" ps
  for name in backend sim; do
    if [ -f "${RUN_DIR}/${name}.pid" ] && kill -0 "$(cat "${RUN_DIR}/${name}.pid")" 2>/dev/null; then
      echo "${name}: 运行中（pid $(cat "${RUN_DIR}/${name}.pid")）"
    else
      echo "${name}: 未运行"
    fi
  done
}

case "${1:-}" in
  up)            cmd_up ;;
  down)          cmd_down ;;
  migrate)       cmd_migrate ;;
  seed)          cmd_seed ;;
  rebuild)       cmd_rebuild ;;
  backend-start) cmd_backend_start ;;
  backend-stop)  cmd_backend_stop ;;
  sim-start)     cmd_sim_start ;;
  sim-stop)      cmd_sim_stop ;;
  status)        cmd_status ;;
  *)
    echo "用法：$0 <rebuild|up|down|migrate|seed|backend-start|backend-stop|sim-start|sim-stop|status>"
    echo "  rebuild        每轮验收前重建验收库（down -v → up → init 等待 → migrations → seed）"
    echo "  backend-start  以验收数据源/Redis/EMQX + Pay-Sim 开启启动后端（端口 ${BACKEND_PORT}）"
    echo "  sim-start      启动 tools/device-sim（ACC-DEV-0001，连验收 EMQX 1884）"
    exit 2
    ;;
esac

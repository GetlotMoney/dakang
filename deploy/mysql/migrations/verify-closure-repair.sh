#!/usr/bin/env bash
# ============================================================
# 迁移 v5 自动化验证（独立临时 MySQL 容器；禁止连接主库）
# 用法：deploy/mysql/migrations/verify-closure-repair.sh
# 退出码：0=全部场景通过；非 0=至少一个场景未达预期。
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
INIT_DIR="$ROOT/deploy/mysql/init"
MIG="$ROOT/deploy/mysql/migrations/2026-07-20-closure-repair.sql"
CT="dakang-migtest-$$"
PW="migtest"
IMG="mysql:8.0"
PASS=0
FAIL=0

log() { printf '%s\n' "$*"; }
ok() { PASS=$((PASS + 1)); log "  ✅ $*"; }
bad() { FAIL=$((FAIL + 1)); log "  ❌ $*"; }

cleanup() {
  docker rm -fv "$CT" >/dev/null 2>&1 || true
}
trap cleanup EXIT

msql() {
  docker exec -i "$CT" mysql --default-character-set=utf8mb4 -h127.0.0.1 -uroot -p"$PW" "$@" 2>/dev/null
}

run_mig() {
  sed 's/^SET NAMES/-- SET NAMES/' "$MIG" |
    docker exec -i "$CT" mysql --default-character-set=utf8mb4 -h127.0.0.1 -uroot -p"$PW" "$1" 2>&1
}

seed_fresh() {
  local db="$1"
  msql -e "DROP DATABASE IF EXISTS $db; CREATE DATABASE $db CHARACTER SET utf8mb4;" || return 1
  local f
  for f in 01-base.sql 02-ws-business.sql 03-demo-baseline.sql; do
    sed '/^USE dakang;$/d; /^CREATE DATABASE/d' "$INIT_DIR/$f" | msql "$db" || return 1
  done
}

# 对迁移可能触及或作为 invariant 依据的表做全量行数据快照（不把自增计数器称为业务数据）。
snapshot_core() {
  local db="$1"
  docker exec "$CT" mysqldump -h127.0.0.1 -uroot -p"$PW" \
    --compact --skip-comments --skip-triggers --no-create-info --order-by-primary --hex-blob \
    "$db" api_dict_type api_dict_data ws_order ws_wallet_flow ws_command ws_delivery_task ws_domain_event \
    2>/dev/null | LC_ALL=C LANG=C shasum -a 256 | awk '{print $1}'
}

invariant_orphans() {
  msql -N "$1" -e "SELECT COUNT(*) FROM ws_delivery_task t WHERE t.ORDER_ID IS NOT NULL AND NOT EXISTS(SELECT 1 FROM ws_order o WHERE o.ID=t.ORDER_ID);"
}

expect_signal_unchanged() {
  local db="$1"
  local label="$2"
  local before after output rc
  before="$(snapshot_core "$db")" || { bad "$label：迁移前行数据快照失败"; exit 1; }
  output="$(run_mig "$db")"
  rc=$?
  after="$(snapshot_core "$db")" || { bad "$label：迁移后行数据快照失败"; exit 1; }
  if [ "$rc" -ne 0 ] && printf '%s' "$output" | grep -qE '1644|45000' && [ "$before" = "$after" ]; then
    ok "$label：SIGNAL 且相关表全量行数据零变化"
  else
    bad "$label：rc=$rc signal=$(printf '%s' "$output" | grep -cE '1644|45000') snapshot=$before/$after"
  fi
}

log "==> 启动临时容器 $CT ($IMG，无宿主端口、无主库挂载)"
docker run --rm -d --name "$CT" -e MYSQL_ROOT_PASSWORD="$PW" "$IMG" \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci >/dev/null || exit 1

READY=0
for _ in $(seq 1 60); do
  if docker exec "$CT" mysqladmin ping -h127.0.0.1 -uroot -p"$PW" >/dev/null 2>&1; then
    READY=1
    break
  fi
  sleep 2
done
if [ "$READY" -ne 1 ]; then
  log "MySQL 临时容器在时限内未就绪"
  exit 1
fi

# ---------- 场景 1：fresh init 零影响 ----------
log "==> 场景1 fresh init 零影响"
seed_fresh s1 || exit 1
B="$(snapshot_core s1)" || { bad "fresh 迁移前行数据快照失败"; exit 1; }
S1OUT="$(run_mig s1)"
S1RC=$?
A="$(snapshot_core s1)" || { bad "fresh 迁移后行数据快照失败"; exit 1; }
T1="$(msql -N s1 -e "SELECT ORDER_ID FROM ws_delivery_task WHERE ID=1;")"
if [ "$S1RC" -eq 0 ] && [ "$B" = "$A" ] && [ "$T1" = "3" ] && [ "$(invariant_orphans s1)" = "0" ]; then
  ok "fresh 相关表全量行数据零影响、task#1→3、无孤儿"
else
  bad "fresh 异常 (rc=$S1RC snapshot=$B/$A task#1=$T1 output=$S1OUT)"
fi

# ---------- 场景 2：已知旧库缺陷可修复 ----------
log "==> 场景2 旧库修复"
seed_fresh s2 || exit 1
msql s2 -e "DELETE FROM ws_order WHERE ID=3; UPDATE ws_command SET ORDER_ID=NULL,CMD_STATUS=2,CMD_PAYLOAD='{\"outletNo\":1,\"waterType\":\"纯净水\",\"planMl\":5000}',RESULT_PAYLOAD=NULL,CREATE_TIME='20260710123000',UPDATE_BY=0 WHERE ID=4; UPDATE ws_delivery_task SET ORDER_ID=2 WHERE ID=1; INSERT INTO ws_command (ID,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,CMD_NO,DEVICE_ID,ORDER_ID,CMD_TYPE,CMD_PAYLOAD,CMD_STATUS,SENT_TIME,RETRY_COUNT) VALUES (16,0,1,'20260720002636',1,'20260720002636','CMD20260720002636177626',1,34,1,'{\"outletNo\":1,\"waterTypeId\":1,\"waterType\":\"纯净水\",\"planMl\":10000,\"orderNo\":\"WO202607200CD3C9DAE79C\"}',2,'20260720002636',0);" || exit 1
S2OUT="$(run_mig s2)"
S2RC=$?
R="$(msql -N s2 -e "SELECT CONCAT((SELECT COUNT(*) FROM ws_order WHERE ID=3 AND ORDER_TYPE=3),'|',(SELECT ORDER_ID FROM ws_delivery_task WHERE ID=1),'|',(SELECT CMD_STATUS FROM ws_command WHERE ID=4),'|',(SELECT ORDER_ID FROM ws_command WHERE ID=4),'|',IFNULL((SELECT CMD_STATUS FROM ws_command WHERE ID=16),'-'),'|',(SELECT COUNT(*) FROM ws_domain_event WHERE EVENT_KEY='CMD20260720002636177626' AND EVENT_TYPE=8 AND EVENT_PAYLOAD LIKE '%\"migration\":\"2026-07-20-closure-repair\"%'),'|',(SELECT COUNT(*) FROM api_dict_data WHERE DICT_TYPE='1363' AND DICT_VALUE=8),'|',$(invariant_orphans s2));")"
if [ "$S2RC" -eq 0 ] && [ "$R" = "1|3|4|1|5|1|1|0" ]; then
  ok "旧库已修复 ($R)"
else
  bad "旧库修复异常 (rc=$S2RC got=$R output=$S2OUT)"
fi

# ---------- 场景 3：幂等复跑 ----------
log "==> 场景3 幂等复跑"
B="$(snapshot_core s2)" || { bad "幂等复跑前行数据快照失败"; exit 1; }
S3OUT="$(run_mig s2)"
S3RC=$?
A="$(snapshot_core s2)" || { bad "幂等复跑后行数据快照失败"; exit 1; }
if [ "$S3RC" -eq 0 ] && [ "$B" = "$A" ]; then
  ok "幂等复跑相关表全量行数据零变化"
else
  bad "幂等异常 (rc=$S3RC snapshot=$B/$A output=$S3OUT)"
fi

# ---------- 场景 4：order#3 状态错 ----------
log "==> 场景4 order#3 共键错误中止"
seed_fresh s4 || exit 1
msql s4 -e "UPDATE ws_order SET ORDER_STATUS=4 WHERE ID=3;" || exit 1
expect_signal_unchanged s4 "order#3 状态错"

# ---------- 场景 5：task#1 用户错位 ----------
log "==> 场景5 task#1 用户错位中止"
seed_fresh s5 || exit 1
msql s5 -e "UPDATE ws_delivery_task SET USER_ID=999 WHERE ID=1;" || exit 1
expect_signal_unchanged s5 "task#1 用户错位"

# ---------- 场景 6：payload 五变体均被权威重写 ----------
log "==> 场景6 command#4 payload 五变体"
S6OK=1
for variant in '{"outletNo":1,"planMl":5000}' 'NULL' 'not-json' '{"orderNo":123,"planMl":5000,"outletNo":1}' '{"nested":{"orderNo":"WO20260710130000001"},"planMl":5000,"outletNo":1}'; do
  seed_fresh s6 || exit 1
  msql s6 -e "DELETE FROM ws_order WHERE ID=3; UPDATE ws_delivery_task SET ORDER_ID=2 WHERE ID=1;" || exit 1
  if [ "$variant" = "NULL" ]; then
    msql s6 -e "UPDATE ws_command SET ORDER_ID=NULL,CMD_STATUS=2,CMD_PAYLOAD=NULL,RESULT_PAYLOAD=NULL,CREATE_TIME='20260710123000',UPDATE_BY=0 WHERE ID=4;" || exit 1
  else
    ESCAPED="$(printf '%s' "$variant" | sed "s/'/''/g")"
    msql s6 -e "UPDATE ws_command SET ORDER_ID=NULL,CMD_STATUS=2,CMD_PAYLOAD='$ESCAPED',RESULT_PAYLOAD=NULL,CREATE_TIME='20260710123000',UPDATE_BY=0 WHERE ID=4;" || exit 1
  fi
  S6OUT="$(run_mig s6)"
  S6RC=$?
  FINAL="$(msql -N s6 -e "SELECT CONCAT(JSON_TYPE(JSON_EXTRACT(CMD_PAYLOAD,'$.orderNo')),'|',JSON_UNQUOTE(JSON_EXTRACT(CMD_PAYLOAD,'$.orderNo')),'|',JSON_EXTRACT(CMD_PAYLOAD,'$.planMl'),'|',JSON_EXTRACT(CMD_PAYLOAD,'$.outletNo')) FROM ws_command WHERE ID=4;")"
  if [ "$S6RC" -ne 0 ] || [ "$FINAL" != "STRING|WO20260710130000001|5000|1" ]; then
    S6OK=0
    log "    变体失败: [$variant] rc=$S6RC final=$FINAL output=$S6OUT"
  fi
done
if [ "$S6OK" -eq 1 ]; then ok "payload 五变体均被权威重写并通过类型级 invariant"; else bad "payload 变体处理异常"; fi

# ---------- 场景 7：后置 invariant 故障整体回滚 ----------
log "==> 场景7 中途故障整体回滚"
seed_fresh s7 || exit 1
msql s7 -e "DELETE FROM ws_order WHERE ID=3; UPDATE ws_delivery_task SET ORDER_ID=2 WHERE ID=1; UPDATE ws_command SET ORDER_ID=NULL,CMD_STATUS=2 WHERE ID=4; INSERT INTO api_dict_type(DICT_NAME,DICT_TYPE,DICT_REMARK) VALUES('事件类型','1363','领域事件类型（n8n白名单订阅源）'); INSERT INTO api_dict_data(DICT_CLASS,DICT_DEFAULT_FLAG,DICT_TYPE,DICT_SORT,DICT_VALUE,DICT_LABEL) VALUES(NULL,1,'1363',8,8,'指令状态变化');" || exit 1
expect_signal_unchanged s7 "后置字典 invariant 故障"

# ---------- 场景 8：task#1 挂其他合法配送单 ----------
log "==> 场景8 task#1 挂 order#99 中止"
seed_fresh s8 || exit 1
msql s8 -e "INSERT INTO ws_order(ID,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,ORDER_NO,ORDER_TYPE,USER_ID,STATION_ID,ORDER_AMOUNT,PAY_WAY,ORDER_STATUS) VALUES(99,0,1,'20260710145500',1,'20260710150000','WO20260710145500099',3,1,1,3000,1,2); UPDATE ws_delivery_task SET ORDER_ID=99 WHERE ID=1;" || exit 1
expect_signal_unchanged s8 "task#1 关联其他有效配送单"

# ---------- 场景 9：order#3 nullable 字段为 NULL ----------
log "==> 场景9 order#3 STATION_ID=NULL 中止"
seed_fresh s9 || exit 1
msql s9 -e "UPDATE ws_order SET STATION_ID=NULL WHERE ID=3;" || exit 1
expect_signal_unchanged s9 "order#3 STATION_ID=NULL"

# ---------- 场景 10：command#4 身份错位 ----------
log "==> 场景10 command#4 身份错位中止"
seed_fresh s10 || exit 1
msql s10 -e "UPDATE ws_command SET CMD_NO='CMD-WRONG-IDENTITY',DATA_STATUS=1 WHERE ID=4;" || exit 1
expect_signal_unchanged s10 "command#4 CMD_NO/DATA_STATUS 错位"

# ---------- 场景 11：order#1 与 command#4 同时缺失 ----------
log "==> 场景11 固定对象缺行中止"
seed_fresh s11 || exit 1
msql s11 -e "DELETE FROM ws_command WHERE ID=4; DELETE FROM ws_order WHERE ID=1;" || exit 1
expect_signal_unchanged s11 "order#1 与 command#4 缺失"

# ---------- 场景 12：task#1 缺失 ----------
log "==> 场景12 task#1 缺失中止"
seed_fresh s12 || exit 1
msql s12 -e "DELETE FROM ws_delivery_task WHERE ID=1;" || exit 1
expect_signal_unchanged s12 "task#1 缺失"

# ---------- 场景 13：command#16 固定 ID 身份碰撞 ----------
log "==> 场景13 command#16 身份碰撞中止"
seed_fresh s13 || exit 1
msql s13 -e "INSERT INTO ws_command (ID,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,CMD_NO,DEVICE_ID,ORDER_ID,CMD_TYPE,CMD_PAYLOAD,CMD_STATUS,SENT_TIME,RETRY_COUNT) VALUES (16,0,1,'20260720002636',1,'20260720002636','CMD-WRONG-16',1,NULL,1,'{\"outletNo\":1,\"planMl\":10000,\"orderNo\":\"WO202607200CD3C9DAE79C\"}',2,'20260720002636',0);" || exit 1
expect_signal_unchanged s13 "command#16 CMD_NO 身份碰撞"

# ---------- 场景 14：command#16 逻辑删除不可归档 ----------
log "==> 场景14 command#16 逻辑删除中止"
seed_fresh s14 || exit 1
msql s14 -e "INSERT INTO ws_command (ID,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,CMD_NO,DEVICE_ID,ORDER_ID,CMD_TYPE,CMD_PAYLOAD,CMD_STATUS,SENT_TIME,RETRY_COUNT) VALUES (16,1,1,'20260720002636',1,'20260720002636','CMD20260720002636177626',1,NULL,1,'{\"outletNo\":1,\"planMl\":10000,\"orderNo\":\"WO202607200CD3C9DAE79C\"}',2,'20260720002636',0);" || exit 1
expect_signal_unchanged s14 "command#16 逻辑删除"

# ---------- 场景 15：伪归档事件不得抑制 canonical 事件 ----------
log "==> 场景15 伪归档事件不构成幂等证据"
seed_fresh s15 || exit 1
msql s15 -e "INSERT INTO ws_command (ID,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,CMD_NO,DEVICE_ID,ORDER_ID,CMD_TYPE,CMD_PAYLOAD,CMD_STATUS,SENT_TIME,RETRY_COUNT) VALUES (16,0,1,'20260720002636',1,'20260720002636','CMD20260720002636177626',1,NULL,1,'{\"outletNo\":1,\"planMl\":10000,\"orderNo\":\"WO202607200CD3C9DAE79C\"}',2,'20260720002636',0); INSERT INTO ws_domain_event(DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,EVENT_TYPE,EVENT_KEY,EVENT_PAYLOAD) VALUES(0,99,'20260720220000',99,'20260720220000',8,'CMD20260720002636177626','{\"action\":\"manual-archive\",\"bogus\":true}');" || exit 1
S15OUT="$(run_mig s15)"
S15RC=$?
S15CANON="$(msql -N s15 -e "SELECT COUNT(*) FROM ws_domain_event WHERE DATA_STATUS=0 AND EVENT_TYPE=8 AND EVENT_KEY='CMD20260720002636177626' AND EVENT_PAYLOAD LIKE '%\"migration\":\"2026-07-20-closure-repair\"%' AND EVENT_PAYLOAD LIKE '%\"cmdId\":16%';")"
S15TOTAL="$(msql -N s15 -e "SELECT COUNT(*) FROM ws_domain_event WHERE EVENT_TYPE=8 AND EVENT_KEY='CMD20260720002636177626';")"
if [ "$S15RC" -eq 0 ] && [ "$S15CANON" = "1" ] && [ "$S15TOTAL" = "2" ]; then
  ok "伪事件保留但不能替代 canonical 归档事件"
else
  bad "伪事件场景异常 (rc=$S15RC canonical=$S15CANON total=$S15TOTAL output=$S15OUT)"
fi

# ---------- 场景 16：同字典业务键的错误标签重复 ----------
log "==> 场景16 字典业务键错误重复中止"
seed_fresh s16 || exit 1
msql s16 -e "INSERT INTO api_dict_data(DICT_CLASS,DICT_DEFAULT_FLAG,DICT_TYPE,DICT_SORT,DICT_VALUE,DICT_LABEL) VALUES(NULL,1,'1363',99,8,'错误标签');" || exit 1
expect_signal_unchanged s16 "事件类型 8 错标签重复"

# ---------- 场景 17：一张配送订单被两条有效任务引用 ----------
log "==> 场景17 一单多配送任务中止"
seed_fresh s17 || exit 1
msql s17 -e "INSERT INTO ws_delivery_task(ID,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,ORDER_ID,USER_ID,WATER_TYPE,DELIVERY_COUNT,RECEIVE_ADDRESS,RECEIVE_PHONE,TASK_STATUS) SELECT 99,DATA_STATUS,CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,ORDER_ID,USER_ID,WATER_TYPE,DELIVERY_COUNT,RECEIVE_ADDRESS,RECEIVE_PHONE,TASK_STATUS FROM ws_delivery_task WHERE ID=1;" || exit 1
expect_signal_unchanged s17 "order#3 被两条有效配送任务关联"

TOTAL=$((PASS + FAIL))
log ""
log "==> 结果：$PASS 通过 / $FAIL 失败（共 $TOTAL 场景）"
[ "$FAIL" -eq 0 ] && [ "$TOTAL" -eq 17 ]

#!/usr/bin/env bash
# ============================================================
# L2-AUTH 迁移验证（独立临时 MySQL 容器；禁止连接主库）
# 覆盖（复审 P1-3）：
#   1 权威 fresh init（01-base 已含约束）+ 迁移幂等
#   2 legacy 旧库正向升级 + 功能约束
#   3 legacy 幂等
#   4 污染-重复手机号：中止且 schema+数据指纹零变化
#   5 污染-重复 openid：中止且指纹零变化
#   6 污染-异常手机号：中止
#   7 空串 openid 规范化为 NULL 并建约束
#   8 错误同名索引（非唯一 uk_user_phone）：中止且指纹零变化、目标约束未建
# 退出码：0=全部通过；非 0=有场景未达预期。
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
INIT_DIR="$ROOT/deploy/mysql/init"
MIG="$ROOT/deploy/mysql/migrations/2026-07-21-l2-auth.sql"
CT="dakang-l2auth-test-$$"
PW="migtest"
IMG="mysql:8.0"
PASS=0
FAIL=0

log() { printf '%s\n' "$*"; }
ok() { PASS=$((PASS + 1)); log "  ✅ $*"; }
bad() { FAIL=$((FAIL + 1)); log "  ❌ $*"; }
cleanup() { docker rm -fv "$CT" >/dev/null 2>&1 || true; }
trap cleanup EXIT

msql() { docker exec -i "$CT" mysql --default-character-set=utf8mb4 -h127.0.0.1 -uroot -p"$PW" "$@" 2>/dev/null; }
msql_err() { local db="$1"; shift; docker exec -i "$CT" mysql --default-character-set=utf8mb4 -h127.0.0.1 -uroot -p"$PW" "$db" -e "$1" 2>&1; }
run_mig() { sed 's/^SET NAMES/-- SET NAMES/' "$MIG" | docker exec -i "$CT" mysql --default-character-set=utf8mb4 -h127.0.0.1 -uroot -p"$PW" "$1" 2>&1; }

# 旧库（pre-L2-AUTH）ws_user：gender NOT NULL、openid general_ci、无身份唯一约束——迁移的真实目标形态。
LEGACY_DDL="CREATE TABLE ws_user (
  ID bigint NOT NULL AUTO_INCREMENT,
  CREATE_BY bigint NOT NULL, CREATE_TIME varchar(14) NOT NULL, UPDATE_BY bigint NOT NULL, UPDATE_TIME varchar(14) NOT NULL,
  DATA_STATUS tinyint NOT NULL,
  USER_NAME varchar(10) NOT NULL,
  USER_GENDER tinyint NOT NULL,
  USER_PHONE varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  DISABLED_FLAG tinyint NOT NULL, USER_STATUS tinyint NOT NULL, POINTS int NOT NULL,
  WECHAT_XCX_OPENID varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL,
  PRIMARY KEY (ID), KEY xcx (WECHAT_XCX_OPENID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;"

ins() { # ins <db> <name> <gender> <phone> <openid-or-NULL>
  local db="$1" name="$2" gender="$3" phone="$4" openid="$5"
  msql "$db" -e "INSERT INTO ws_user(CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,DATA_STATUS,USER_NAME,USER_GENDER,USER_PHONE,DISABLED_FLAG,USER_STATUS,POINTS,WECHAT_XCX_OPENID) VALUES(1,'20260721000000',1,'20260721000000',0,'$name',$gender,'$phone',1,1,0,$openid);"
}

create_legacy() {
  local db="$1"
  msql -e "DROP DATABASE IF EXISTS $db; CREATE DATABASE $db CHARACTER SET utf8mb4;" || return 1
  msql "$db" -e "$LEGACY_DDL" || return 1
}

seed_fresh_full() {
  local db="$1" f
  msql -e "DROP DATABASE IF EXISTS $db; CREATE DATABASE $db CHARACTER SET utf8mb4;" || return 1
  for f in 01-base.sql 02-ws-business.sql 03-demo-baseline.sql; do
    sed '/^USE dakang;$/d; /^CREATE DATABASE/d' "$INIT_DIR/$f" | msql "$db" || return 1
  done
}

idx_count() { msql -N "$1" -e "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='$1' AND table_name='ws_user' AND index_name='$2';"; }
col_nullable() { msql -N "$1" -e "SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema='$1' AND table_name='ws_user' AND column_name='USER_GENDER';"; }
col_collation() { msql -N "$1" -e "SELECT COLLATION_NAME FROM information_schema.columns WHERE table_schema='$1' AND table_name='ws_user' AND column_name='WECHAT_XCX_OPENID';"; }
# schema（去 AUTO_INCREMENT 计数噪声）+ 数据指纹。
fingerprint() {
  local db="$1"
  msql -N "$db" -e "SHOW CREATE TABLE ws_user\\G" | sed 's/AUTO_INCREMENT=[0-9]*//g'
  msql -N "$db" -e "CHECKSUM TABLE ws_user;"
}

log "==> 启动临时容器 $CT ($IMG，无宿主端口、无主库挂载)"
docker run --rm -d --name "$CT" -e MYSQL_ROOT_PASSWORD="$PW" "$IMG" \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci >/dev/null || exit 1
READY=0
for _ in $(seq 1 60); do
  docker exec "$CT" mysqladmin ping -h127.0.0.1 -uroot -p"$PW" >/dev/null 2>&1 && { READY=1; break; }
  sleep 2
done
[ "$READY" -eq 1 ] || { log "MySQL 临时容器未就绪"; exit 1; }

# ---------- 场景 1：权威 fresh init 已含约束 + 迁移幂等 ----------
log "==> 场景1 权威 fresh init（01-base 已含约束）+ 迁移幂等"
seed_fresh_full a1 || exit 1
PRE_PHONE="$(idx_count a1 uk_user_phone)"; PRE_OPENID="$(idx_count a1 uk_user_wechat_xcx_openid)"
PRE_NULL="$(col_nullable a1)"; PRE_COLL="$(col_collation a1)"
OUT="$(run_mig a1)"; RC=$?
if [ "$PRE_PHONE" = "1" ] && [ "$PRE_OPENID" = "1" ] && [ "$PRE_NULL" = "YES" ] && [ "$PRE_COLL" = "utf8mb4_bin" ] \
   && [ "$RC" -eq 0 ] && [ "$(idx_count a1 uk_user_phone)" = "1" ] && [ "$(idx_count a1 uk_user_wechat_xcx_openid)" = "1" ]; then
  ok "fresh init 已具两项唯一约束 + gender 可空 + openid utf8mb4_bin，迁移幂等零报错"
else
  bad "fresh init/幂等异常 (prePhone=$PRE_PHONE preOpenid=$PRE_OPENID null=$PRE_NULL coll=$PRE_COLL rc=$RC out=$OUT)"
fi

# ---------- 场景 2：legacy 正向升级 + 功能约束 ----------
log "==> 场景2 legacy 旧库正向升级 + 约束/大小写敏感"
create_legacy a2 || exit 1
ins a2 clean1 1 13500000001 "'oClean1'"
ins a2 clean2 1 13500000002 NULL
OUT="$(run_mig a2)"; RC=$?
PHONE_UK="$(idx_count a2 uk_user_phone)"; OPENID_UK="$(idx_count a2 uk_user_wechat_xcx_openid)"
NULLABLE="$(col_nullable a2)"; COLL="$(col_collation a2)"
NULL_GENDER=$(msql_err a2 "INSERT INTO ws_user(CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,DATA_STATUS,USER_NAME,USER_GENDER,USER_PHONE,DISABLED_FLAG,USER_STATUS,POINTS,WECHAT_XCX_OPENID) VALUES(1,'20260721000000',1,'20260721000000',0,'ng',NULL,'13512345678',1,1,0,'oCaseAAA');" | grep -ciE "error")
DUP_PHONE=$(msql_err a2 "INSERT INTO ws_user(CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,DATA_STATUS,USER_NAME,USER_GENDER,USER_PHONE,DISABLED_FLAG,USER_STATUS,POINTS) VALUES(1,'20260721000000',1,'20260721000000',0,'dup',1,'13512345678',1,1,0);" | grep -c "Duplicate")
DUP_OPENID=$(msql_err a2 "INSERT INTO ws_user(CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,DATA_STATUS,USER_NAME,USER_GENDER,USER_PHONE,DISABLED_FLAG,USER_STATUS,POINTS,WECHAT_XCX_OPENID) VALUES(1,'20260721000000',1,'20260721000000',0,'dupo',1,'13511110000',1,1,0,'oCaseAAA');" | grep -c "Duplicate")
CASE_DIFF=$(msql_err a2 "INSERT INTO ws_user(CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,DATA_STATUS,USER_NAME,USER_GENDER,USER_PHONE,DISABLED_FLAG,USER_STATUS,POINTS,WECHAT_XCX_OPENID) VALUES(1,'20260721000000',1,'20260721000000',0,'cs',1,'13511112222',1,1,0,'ocaseaaa');" | grep -c "Duplicate")
if [ "$RC" -eq 0 ] && [ "$PHONE_UK" = "1" ] && [ "$OPENID_UK" = "1" ] && [ "$NULLABLE" = "YES" ] && [ "$COLL" = "utf8mb4_bin" ] \
   && [ "$NULL_GENDER" = "0" ] && [ "$DUP_PHONE" = "1" ] && [ "$DUP_OPENID" = "1" ] && [ "$CASE_DIFF" = "0" ]; then
  ok "legacy 升级建两约束 + gender 可空 + openid utf8mb4_bin + 大小写敏感（同名拒/异名过）"
else
  bad "legacy 升级异常 (rc=$RC phoneUk=$PHONE_UK openidUk=$OPENID_UK null=$NULLABLE coll=$COLL nullGender=$NULL_GENDER dupPhone=$DUP_PHONE dupOpenid=$DUP_OPENID caseDiff=$CASE_DIFF)"
fi

# ---------- 场景 3：legacy 幂等 ----------
log "==> 场景3 legacy 重复执行幂等"
OUT="$(run_mig a2)"; RC=$?
if [ "$RC" -eq 0 ] && [ "$(idx_count a2 uk_user_phone)" = "1" ] && [ "$(idx_count a2 uk_user_wechat_xcx_openid)" = "1" ]; then
  ok "legacy 重复执行零报错、约束仍各一"
else
  bad "legacy 幂等异常 (rc=$RC out=$OUT)"
fi

# ---------- 场景 4：污染-重复手机号 → 中止且指纹零变化 ----------
log "==> 场景4 污染重复手机号：中止且 schema+数据指纹零变化"
create_legacy a4 || exit 1
ins a4 dup1 1 13900001111 NULL
ins a4 dup2 1 13900001111 NULL
FP_BEFORE="$(fingerprint a4)"
OUT="$(run_mig a4)"
FP_AFTER="$(fingerprint a4)"
if echo "$OUT" | grep -qE '1644|45000|中止' && [ "$FP_BEFORE" = "$FP_AFTER" ] && [ "$(idx_count a4 uk_user_phone)" = "0" ]; then
  ok "重复手机号被拒且指纹零变化、无约束新建"
else
  bad "重复手机号未按零副作用中止 (uk=$(idx_count a4 uk_user_phone) fpEq=$([ "$FP_BEFORE" = "$FP_AFTER" ] && echo Y || echo N) out=$OUT)"
fi

# ---------- 场景 5：污染-重复 openid → 中止且指纹零变化 ----------
log "==> 场景5 污染重复 openid：中止且指纹零变化"
create_legacy a5 || exit 1
ins a5 o1 1 13900002221 "'oDUP'"
ins a5 o2 1 13900002222 "'oDUP'"
FP_BEFORE="$(fingerprint a5)"
OUT="$(run_mig a5)"
FP_AFTER="$(fingerprint a5)"
if echo "$OUT" | grep -qE '1644|45000|中止' && [ "$FP_BEFORE" = "$FP_AFTER" ] && [ "$(idx_count a5 uk_user_wechat_xcx_openid)" = "0" ]; then
  ok "重复 openid 被拒且指纹零变化、无约束新建"
else
  bad "重复 openid 未按零副作用中止 (out=$OUT)"
fi

# ---------- 场景 6：污染-异常手机号 → 中止 ----------
log "==> 场景6 污染异常手机号：中止"
create_legacy a6 || exit 1
ins a6 bad 1 abc12 NULL
OUT="$(run_mig a6)"
if echo "$OUT" | grep -qE '1644|45000|中止'; then
  ok "异常手机号被拒"
else
  bad "异常手机号未拒绝 (out=$OUT)"
fi

# ---------- 场景 7：空串 openid 规范化为 NULL（不失败） ----------
log "==> 场景7 空串 openid 规范化"
create_legacy a7 || exit 1
ins a7 empt 1 13900007777 "''"
OUT="$(run_mig a7)"; RC=$?
EMPTY_LEFT="$(msql -N a7 -e "SELECT COUNT(*) FROM ws_user WHERE WECHAT_XCX_OPENID='';")"
NORMALIZED="$(msql -N a7 -e "SELECT COUNT(*) FROM ws_user WHERE WECHAT_XCX_OPENID IS NULL;")"
if [ "$RC" -eq 0 ] && [ "$EMPTY_LEFT" = "0" ] && [ "$NORMALIZED" = "1" ] && [ "$(idx_count a7 uk_user_wechat_xcx_openid)" = "1" ]; then
  ok "空串 openid 规范化为 NULL 且建约束"
else
  bad "空串规范化异常 (rc=$RC emptyLeft=$EMPTY_LEFT normalized=$NORMALIZED out=$OUT)"
fi

# ---------- 场景 8：错误同名索引（非唯一 uk_user_phone）→ 中止且指纹零变化 ----------
log "==> 场景8 错误同名索引（非唯一 uk_user_phone）：中止且指纹零变化"
create_legacy a8 || exit 1
ins a8 x1 1 13900008881 NULL
msql a8 -e "CREATE INDEX uk_user_phone ON ws_user(USER_PHONE);"   # 非唯一同名索引
FP_BEFORE="$(fingerprint a8)"
OUT="$(run_mig a8)"
FP_AFTER="$(fingerprint a8)"
if echo "$OUT" | grep -qE '1644|45000|结构不符' && [ "$FP_BEFORE" = "$FP_AFTER" ] && [ "$(idx_count a8 uk_user_wechat_xcx_openid)" = "0" ]; then
  ok "非唯一同名索引被识别并中止、指纹零变化、目标约束未建"
else
  bad "错误同名索引未按零副作用中止 (openidUk=$(idx_count a8 uk_user_wechat_xcx_openid) fpEq=$([ "$FP_BEFORE" = "$FP_AFTER" ] && echo Y || echo N) out=$OUT)"
fi

log ""
log "==> 结果：$PASS 通过 / $FAIL 失败（共 8 场景）"
[ "$FAIL" = "0" ]

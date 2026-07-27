#!/usr/bin/env bash
# ============================================================
# L2-DB 迁移验证（独立临时 MySQL 容器；禁止连接主库）
# 覆盖：
#   1 权威 fresh init（02 已含新结构）+ 迁移幂等
#   2 legacy 旧库正向升级（内嵌 pre-L2 表结构，不依赖 02 当前内容）
#   3 legacy 重复执行幂等
#   4 污染-重复支付单（同 ORDER_NO）→ 中止且 schema+数据指纹零变化
#   5 污染-幂等键冲突（ws_wallet_flow 重复 BIZ_IDEMPOTENCY_KEY）→ 中止且指纹零变化
#   6 错误同名索引（非唯一 uk_payment_order_no）→ 中止且指纹零变化
#   7 同名前缀唯一索引（SUB_PART，如 ORDER_NO(10)）→ 必须识别为不合格并中止
#   8 迁移失败后不残留存储过程（information_schema.routines 计数不变）
#   9 事件表已存在但结构残缺（缺唯一键/缺列）→ 必须中止（审计实测的 money-path 假绿）
#   0 静态断言：迁移脚本不含 CREATE PROCEDURE/FUNCTION/TRIGGER
# 退出码：0=全部通过；非 0=有场景未达预期。
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
INIT_DIR="$ROOT/deploy/mysql/init"
MIG="$ROOT/deploy/mysql/migrations/2026-07-22-l2-db.sql"
CT="dakang-l2db-test-$$"
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
# 保留 stderr：检测"是否被唯一键拒绝"必须能看到 Duplicate（2>/dev/null 会让 grep 恒为 0，造成假绿）。
msql_err() { local db="$1"; shift; docker exec -i "$CT" mysql --default-character-set=utf8mb4 -h127.0.0.1 -uroot -p"$PW" "$db" -e "$1" 2>&1; }
# 按交付形态原样喂入（不再用 sed 注掉 SET NAMES）：被测物必须等于交付物，
# 否则 SET NAMES 与 --default-character-set 的交互、以及中止消息的编码表现都从未被验证。
run_mig() { docker exec -i "$CT" mysql --default-character-set=utf8mb4 -h127.0.0.1 -uroot -p"$PW" "$1" < "$MIG" 2>&1; }

# pre-L2 旧库结构（无支付单唯一键、无幂等键列、无 ws_payment_event）——迁移的真实目标形态。
LEGACY_DDL="
CREATE TABLE ws_payment (
  ID bigint NOT NULL AUTO_INCREMENT, DATA_STATUS tinyint NOT NULL DEFAULT 0,
  CREATE_BY bigint NOT NULL, CREATE_TIME varchar(14) NOT NULL, UPDATE_BY bigint NOT NULL, UPDATE_TIME varchar(14) NOT NULL,
  ORDER_ID bigint NOT NULL, ORDER_NO varchar(32) NOT NULL, TRANSACTION_ID varchar(64), PAY_AMOUNT bigint NOT NULL,
  PAY_STATUS tinyint NOT NULL, PREPAY_ID varchar(64), CALLBACK_TIME varchar(14), CALLBACK_PAYLOAD text,
  PRIMARY KEY (ID), UNIQUE INDEX uk_transaction_id (TRANSACTION_ID), INDEX idx_payment_order (ORDER_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ws_wallet_flow (
  ID bigint NOT NULL AUTO_INCREMENT, DATA_STATUS tinyint NOT NULL DEFAULT 0,
  CREATE_BY bigint NOT NULL, CREATE_TIME varchar(14) NOT NULL, UPDATE_BY bigint NOT NULL, UPDATE_TIME varchar(14) NOT NULL,
  CARD_ID bigint NOT NULL, USER_ID bigint NOT NULL, FLOW_TYPE tinyint NOT NULL,
  AMOUNT_CHANGE bigint NOT NULL DEFAULT 0, ML_CHANGE bigint NOT NULL DEFAULT 0,
  AMOUNT_AFTER bigint NOT NULL, ML_AFTER bigint NOT NULL, ORDER_ID bigint, FLOW_REMARK varchar(500),
  PRIMARY KEY (ID), INDEX idx_flow_order (ORDER_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ws_domain_event (
  ID bigint NOT NULL AUTO_INCREMENT, DATA_STATUS tinyint NOT NULL DEFAULT 0,
  CREATE_BY bigint NOT NULL, CREATE_TIME varchar(14) NOT NULL, UPDATE_BY bigint NOT NULL, UPDATE_TIME varchar(14) NOT NULL,
  EVENT_TYPE tinyint NOT NULL, EVENT_KEY varchar(64) NOT NULL, EVENT_PAYLOAD text NOT NULL,
  WHITELIST_FLAG tinyint NOT NULL DEFAULT 1, CONSUMED_FLAG tinyint NOT NULL DEFAULT 1,
  PRIMARY KEY (ID), INDEX idx_event_key (EVENT_KEY)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"

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

pay() { # pay <db> <orderId> <orderNo>
  msql "$1" -e "INSERT INTO ws_payment(CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,DATA_STATUS,ORDER_ID,ORDER_NO,PAY_AMOUNT,PAY_STATUS) VALUES(1,'20260722000000',1,'20260722000000',0,$2,'$3',100,1);"
}

idx_count() { msql -N "$1" -e "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='$1' AND table_name='$2' AND index_name='$3';"; }
col_count() { msql -N "$1" -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$1' AND table_name='$2' AND column_name='$3';"; }
tbl_count() { msql -N "$1" -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$1' AND table_name='$2';"; }
routines_count() { msql -N "$1" -e "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='$1';"; }

# schema（剥离 AUTO_INCREMENT 计数噪声）+ 数据指纹，覆盖本迁移涉及的全部表。
fingerprint() {
  local db="$1" t
  for t in ws_payment ws_wallet_flow ws_domain_event ws_payment_event; do
    msql -N "$db" -e "SHOW CREATE TABLE $t\\G" 2>/dev/null | sed 's/AUTO_INCREMENT=[0-9]*//g'
    msql -N "$db" -e "CHECKSUM TABLE $t;" 2>/dev/null
  done
}

# ---------- 场景 0：静态断言——迁移不得使用存储程序 ----------
# 运行期的 routines 计数断言只能在"有人重新引入存储过程"时才变红；这条静态断言把
# "本迁移不使用存储过程"这个前提本身变成可证伪项，成本为零。
log "==> 场景0 静态断言：迁移脚本不含存储程序定义"
PROC_HITS="$(grep -cE 'CREATE[[:space:]]+(DEFINER=[^ ]*[[:space:]]+)?(PROCEDURE|FUNCTION|TRIGGER)' "$MIG" || true)"
if [ "$PROC_HITS" = "0" ]; then
  ok "迁移脚本无 CREATE PROCEDURE/FUNCTION/TRIGGER（静态）"
else
  bad "迁移脚本出现存储程序定义 $PROC_HITS 处，失败残留问题会复活"
fi

log "==> 启动临时容器 $CT ($IMG，无宿主端口、无主库挂载)"
docker run --rm -d --name "$CT" -e MYSQL_ROOT_PASSWORD="$PW" "$IMG" \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci >/dev/null || exit 1
READY=0
for _ in $(seq 1 60); do
  docker exec "$CT" mysqladmin ping -h127.0.0.1 -uroot -p"$PW" >/dev/null 2>&1 && { READY=1; break; }
  sleep 2
done
[ "$READY" -eq 1 ] || { log "MySQL 临时容器未就绪"; exit 1; }

# ---------- 场景 1：权威 fresh init + 迁移幂等 ----------
log "==> 场景1 权威 fresh init（02 已含新结构）+ 迁移幂等"
seed_fresh_full d1 || exit 1
PRE_NO="$(idx_count d1 ws_payment uk_payment_order_no)"
PRE_ID="$(idx_count d1 ws_payment uk_payment_order_id)"
PRE_EVT="$(tbl_count d1 ws_payment_event)"
PRE_WF="$(idx_count d1 ws_wallet_flow uk_wallet_flow_biz_key)"
PRE_DE="$(idx_count d1 ws_domain_event uk_domain_event_biz_key)"
# 指纹前后比对：证明迁移对权威 schema 是真正的 no-op（此前 8 个场景无一验证这一点）。
FP_BEFORE="$(fingerprint d1)"
OUT="$(run_mig d1)"; RC=$?
FP_AFTER="$(fingerprint d1)"
if [ "$PRE_NO" = "1" ] && [ "$PRE_ID" = "1" ] && [ "$PRE_EVT" = "1" ] && [ "$PRE_WF" = "1" ] && [ "$PRE_DE" = "1" ] \
   && [ "$RC" -eq 0 ] && [ -n "$FP_BEFORE" ] && [ "$FP_BEFORE" = "$FP_AFTER" ] \
   && [ "$(idx_count d1 ws_payment uk_payment_order_no)" = "1" ] \
   && [ "$(idx_count d1 ws_payment_event uk_payment_event_source_channel_key)" = "3" ]; then
  ok "fresh init 已含全部新结构，迁移幂等零报错且对权威 schema 零改动"
else
  bad "fresh init/幂等异常 (no=$PRE_NO id=$PRE_ID evt=$PRE_EVT wf=$PRE_WF de=$PRE_DE rc=$RC fpEq=$([ "$FP_BEFORE" = "$FP_AFTER" ] && echo Y || echo N) out=$OUT)"
fi

# ---------- 场景 2：legacy 正向升级 ----------
log "==> 场景2 legacy 旧库正向升级"
create_legacy d2 || exit 1
pay d2 1001 'RO20260722AAA'
OUT="$(run_mig d2)"; RC=$?
C1="$(col_count d2 ws_payment PAY_SOURCE)"; C2="$(col_count d2 ws_payment CURRENCY)"
C3="$(col_count d2 ws_payment PAY_EXPIRE_TIME)"; C4="$(col_count d2 ws_payment PAY_SUCCESS_TIME)"
I1="$(idx_count d2 ws_payment uk_payment_order_no)"; I2="$(idx_count d2 ws_payment uk_payment_order_id)"
T1="$(tbl_count d2 ws_payment_event)"; I3="$(idx_count d2 ws_payment_event uk_payment_event_source_channel_key)"
C5="$(col_count d2 ws_wallet_flow BIZ_IDEMPOTENCY_KEY)"; I4="$(idx_count d2 ws_wallet_flow uk_wallet_flow_biz_key)"
C6="$(col_count d2 ws_domain_event BIZ_IDEMPOTENCY_KEY)"; I5="$(idx_count d2 ws_domain_event uk_domain_event_biz_key)"
# 功能性：同 ORDER_NO 第二张支付单必须被唯一键拒绝。
# 注意必须显式给 PAY_SOURCE/PAY_EXPIRE_TIME：迁移完成后这两列 NOT NULL 且**无默认值**（fail-closed），
# 漏写会先撞 ER_1364 而非唯一键，断言就测不到唯一键了。
DUP_PAY=$(msql_err d2 "INSERT INTO ws_payment(CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,DATA_STATUS,ORDER_ID,ORDER_NO,PAY_AMOUNT,PAY_STATUS,PAY_SOURCE,PAY_EXPIRE_TIME) VALUES(1,'20260722000000',1,'20260722000000',0,1002,'RO20260722AAA',100,1,1,'20260722235959');" | grep -c "Duplicate")
# 反向证明 fail-closed：漏写 PAY_SOURCE 必须被数据库拒绝（而不是静默默认成 1=微信）
NO_SRC=$(msql_err d2 "INSERT INTO ws_payment(CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,DATA_STATUS,ORDER_ID,ORDER_NO,PAY_AMOUNT,PAY_STATUS,PAY_EXPIRE_TIME) VALUES(1,'20260722000000',1,'20260722000000',0,1003,'RO20260722BBB',100,1,'20260722235959');" | grep -c "doesn't have a default value")
DUP_KEY=$(msql_err d2 "INSERT INTO ws_wallet_flow(CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,DATA_STATUS,CARD_ID,USER_ID,FLOW_TYPE,AMOUNT_AFTER,ML_AFTER,BIZ_IDEMPOTENCY_KEY) VALUES(1,'20260722000000',1,'20260722000000',0,1,1,1,0,0,'RECHARGE:RO20260722AAA'),(1,'20260722000000',1,'20260722000000',0,1,1,1,0,0,'RECHARGE:RO20260722AAA');" | grep -c "Duplicate")
# 事件表唯一键必须做**功能性**验证：仅断言 idx_count=3 只数行数，
# 把它写成非唯一三列索引或颠倒列序，计数仍是 3、断言照样绿。这是 money-path 的最后防线。
evt_insert() { # evt_insert <paySource> <factChannel> <eventKey>
  printf "INSERT INTO ws_payment_event(CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,DATA_STATUS,PAY_SOURCE,FACT_CHANNEL,PROVIDER_EVENT_KEY,ORDER_NO,TRADE_STATE,RAW_BODY_SHA256,VERIFY_METHOD,PROCESSING_STATUS,RETRY_COUNT,RECEIVED_TIME) VALUES(1,'20260722000000',1,'20260722000000',0,%s,%s,'%s','RO20260722AAA','SUCCESS','abc',1,1,0,'20260722000000');" "$1" "$2" "$3"
}
msql d2 -e "$(evt_insert 1 1 EVT-DUP-001)" >/dev/null 2>&1
# 同三元组必须被拒
DUP_EVT=$(msql_err d2 "$(evt_insert 1 1 EVT-DUP-001)" | grep -c "Duplicate")
# 反向：仅 FACT_CHANNEL 不同必须允许（证明是三列复合键，而非只按 PROVIDER_EVENT_KEY 唯一）
DIFF_EVT=$(msql_err d2 "$(evt_insert 1 2 EVT-DUP-001)" | grep -c "Duplicate")
if [ "$RC" -eq 0 ] && [ "$C1$C2$C3$C4" = "1111" ] && [ "$I1" = "1" ] && [ "$I2" = "1" ] && [ "$T1" = "1" ] \
   && [ "$I3" = "3" ] && [ "$C5" = "1" ] && [ "$I4" = "1" ] && [ "$C6" = "1" ] && [ "$I5" = "1" ] \
   && [ "$DUP_PAY" = "1" ] && [ "$DUP_KEY" = "1" ] && [ "$DUP_EVT" = "1" ] && [ "$DIFF_EVT" = "0" ] && [ "$NO_SRC" = "1" ]; then
  ok "legacy 升级：4 列 + 2 唯一键 + 事件表(三列复合键功能性生效) + 双幂等键；重复支付单/幂等键/事件事实均被拒，且漏写 PAY_SOURCE 被数据库拒绝(fail-closed)"
else
  bad "legacy 升级异常 (rc=$RC cols=$C1$C2$C3$C4 uk=$I1/$I2 evt=$T1/$I3 wf=$C5/$I4 de=$C6/$I5 dupPay=$DUP_PAY dupKey=$DUP_KEY dupEvt=$DUP_EVT diffEvt=$DIFF_EVT noSrc=$NO_SRC out=$OUT)"
fi

# ---------- 场景 3：legacy 重复执行幂等 ----------
log "==> 场景3 legacy 重复执行幂等"
OUT="$(run_mig d2)"; RC=$?
if [ "$RC" -eq 0 ] && [ "$(idx_count d2 ws_payment uk_payment_order_no)" = "1" ] \
   && [ "$(idx_count d2 ws_wallet_flow uk_wallet_flow_biz_key)" = "1" ] \
   && [ "$(idx_count d2 ws_payment_event uk_payment_event_source_channel_key)" = "3" ]; then
  ok "重复执行零报错，唯一键不重复创建"
else
  bad "幂等异常 (rc=$RC out=$OUT)"
fi

# ---------- 场景 4：污染-重复支付单 ----------
log "==> 场景4 污染重复支付单：中止且指纹零变化"
create_legacy d4 || exit 1
pay d4 2001 'RO20260722DUP'
pay d4 2002 'RO20260722DUP'
FP_BEFORE="$(fingerprint d4)"; R_BEFORE="$(routines_count d4)"
OUT="$(run_mig d4)"; RC=$?
FP_AFTER="$(fingerprint d4)"; R_AFTER="$(routines_count d4)"
if [ "$RC" -ne 0 ] && echo "$OUT" | grep -q '迁移中止' && [ "$FP_BEFORE" = "$FP_AFTER" ] \
   && [ "$(idx_count d4 ws_payment uk_payment_order_no)" = "0" ] && [ "$(tbl_count d4 ws_payment_event)" = "0" ]; then
  ok "重复支付单被拒、指纹零变化、未建任何新结构"
else
  bad "重复支付单未按零副作用中止 (uk=$(idx_count d4 ws_payment uk_payment_order_no) fpEq=$([ "$FP_BEFORE" = "$FP_AFTER" ] && echo Y || echo N) out=$OUT)"
fi

# ---------- 场景 8（与 4 同库断言）：失败后不残留存储过程 ----------
log "==> 场景8 迁移失败后不残留存储过程"
if [ "$R_BEFORE" = "$R_AFTER" ] && [ "$R_AFTER" = "0" ]; then
  ok "routines 计数保持 $R_AFTER（本迁移完全不使用存储过程，任何退出路径均无残留）"
else
  bad "存储过程残留 (before=$R_BEFORE after=$R_AFTER)"
fi

# ---------- 场景 5：污染-幂等键冲突 ----------
log "==> 场景5 污染幂等键冲突：中止且指纹零变化"
create_legacy d5 || exit 1
msql d5 -e "ALTER TABLE ws_wallet_flow ADD COLUMN BIZ_IDEMPOTENCY_KEY varchar(64) NULL;"
msql d5 -e "INSERT INTO ws_wallet_flow(CREATE_BY,CREATE_TIME,UPDATE_BY,UPDATE_TIME,DATA_STATUS,CARD_ID,USER_ID,FLOW_TYPE,AMOUNT_AFTER,ML_AFTER,BIZ_IDEMPOTENCY_KEY) VALUES(1,'20260722000000',1,'20260722000000',0,1,1,1,0,0,'RECHARGE:RO1'),(1,'20260722000000',1,'20260722000000',0,1,1,1,0,0,'RECHARGE:RO1');"
FP_BEFORE="$(fingerprint d5)"
OUT="$(run_mig d5)"; RC=$?
FP_AFTER="$(fingerprint d5)"
if [ "$RC" -ne 0 ] && echo "$OUT" | grep -q '迁移中止' && [ "$FP_BEFORE" = "$FP_AFTER" ] \
   && [ "$(idx_count d5 ws_wallet_flow uk_wallet_flow_biz_key)" = "0" ]; then
  ok "重复业务幂等键被拒、指纹零变化、未建唯一键"
else
  bad "幂等键冲突未按零副作用中止 (out=$OUT)"
fi

# ---------- 场景 6：错误同名索引（非唯一） ----------
log "==> 场景6 非唯一同名索引：中止且指纹零变化"
create_legacy d6 || exit 1
msql d6 -e "CREATE INDEX uk_payment_order_no ON ws_payment(ORDER_NO);"
FP_BEFORE="$(fingerprint d6)"
OUT="$(run_mig d6)"; RC=$?
FP_AFTER="$(fingerprint d6)"
if [ "$RC" -ne 0 ] && echo "$OUT" | grep -q '结构不符' && [ "$FP_BEFORE" = "$FP_AFTER" ] && [ "$(tbl_count d6 ws_payment_event)" = "0" ]; then
  ok "非唯一同名索引被识别并中止、指纹零变化"
else
  bad "非唯一同名索引未按零副作用中止 (out=$OUT)"
fi

# ---------- 场景 7：同名前缀唯一索引（SUB_PART） ----------
log "==> 场景7 同名前缀唯一索引（ORDER_NO(10)）：必须识别为不合格并中止"
create_legacy d7 || exit 1
msql d7 -e "CREATE UNIQUE INDEX uk_payment_order_no ON ws_payment(ORDER_NO(10));"
SUBPART="$(msql -N d7 -e "SELECT IFNULL(SUB_PART,'NULL') FROM information_schema.statistics WHERE table_schema='d7' AND table_name='ws_payment' AND index_name='uk_payment_order_no';")"
FP_BEFORE="$(fingerprint d7)"
OUT="$(run_mig d7)"; RC=$?
FP_AFTER="$(fingerprint d7)"
if [ "$RC" -ne 0 ] && echo "$OUT" | grep -q '结构不符' && [ "$FP_BEFORE" = "$FP_AFTER" ] && [ "$(tbl_count d7 ws_payment_event)" = "0" ]; then
  ok "前缀唯一索引(SUB_PART=$SUBPART)被识别为不合格并中止、指纹零变化"
else
  bad "SUB_PART 前缀索引未被识别 (subPart=$SUBPART out=$OUT)"
fi

# ---------- 场景 9：ws_payment_event 已存在但结构残缺 → 必须中止 ----------
# 审计实测的 money-path 假绿：表已存在但缺唯一键时，旧版 2e 的 IF(@n > 0 ...) 不中止，
# 而 CREATE TABLE IF NOT EXISTS 又静默跳过 → 迁移 rc=0，收件箱却没有任何防重复入账的唯一键。
log "==> 场景9 事件表已存在但结构残缺（缺唯一键/缺列）：必须中止且指纹零变化"
create_legacy d9 || exit 1
msql d9 -e "CREATE TABLE ws_payment_event(ID bigint NOT NULL AUTO_INCREMENT PRIMARY KEY, PAY_SOURCE tinyint NOT NULL, FACT_CHANNEL tinyint NOT NULL, PROVIDER_EVENT_KEY varchar(100) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;"
FP_BEFORE="$(fingerprint d9)"
OUT="$(run_mig d9)"; RC=$?
FP_AFTER="$(fingerprint d9)"
if [ "$RC" -ne 0 ] && echo "$OUT" | grep -q '迁移中止' && [ -n "$FP_BEFORE" ] && [ "$FP_BEFORE" = "$FP_AFTER" ] \
   && [ "$(col_count d9 ws_payment PAY_SOURCE)" = "0" ] \
   && [ "$(idx_count d9 ws_payment_event uk_payment_event_source_channel_key)" = "0" ]; then
  ok "残缺事件表被识别并中止、指纹零变化、未对 ws_payment 做任何结构变更"
else
  bad "残缺事件表未被拦截 (rc=$RC paySrc=$(col_count d9 ws_payment PAY_SOURCE) evtUk=$(idx_count d9 ws_payment_event uk_payment_event_source_channel_key) fpEq=$([ "$FP_BEFORE" = "$FP_AFTER" ] && echo Y || echo N) out=$OUT)"
fi

log ""
log "==> 结果：$PASS 通过 / $FAIL 失败（共 10 项：静态 1 + 场景 9）"
[ "$FAIL" = "0" ]

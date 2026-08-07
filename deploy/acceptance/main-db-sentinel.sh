#!/usr/bin/env bash
# 主库只读哨兵（E2E-05 门禁 8）：验收前后各拍一次快照，两份必须逐字节一致。
#
# 只读保证：只执行 SELECT COUNT/SUM/MAX，连接用户虽是 root，但脚本内没有任何写语句；
# 快照落到指定文件，比对用 diff 完成。用法：
#   set -a; . .env; set +a
#   bash deploy/acceptance/main-db-sentinel.sh /tmp/sentinel-before.txt
#   ...（跑验收）...
#   bash deploy/acceptance/main-db-sentinel.sh /tmp/sentinel-after.txt
#   diff /tmp/sentinel-before.txt /tmp/sentinel-after.txt && echo 主库零变化
set -euo pipefail

OUT="${1:?用法：main-db-sentinel.sh <输出文件>}"
: "${DAKANG_DB_PASSWORD:?缺少 DAKANG_DB_PASSWORD（仓库根 .env）}"

# mysql 客户端不在 PATH 时回退官方安装路径（与开发机一致）
MYSQL_BIN="$(command -v mysql || echo /usr/local/mysql/bin/mysql)"
MYSQL=("${MYSQL_BIN}" -h 127.0.0.1 -P 3308 -uroot --default-character-set=utf8mb4 dakang -N -B)

# 覆盖 E2E-05 可能触碰的全部域 + 资金域（余额/水量总和是「主演示卡零变化」的直接证据）
MYSQL_PWD="${DAKANG_DB_PASSWORD}" "${MYSQL[@]}" > "${OUT}" <<'SQL'
SELECT 'ws_device', COUNT(*), COALESCE(MAX(UPDATE_TIME),'-') FROM ws_device;
SELECT 'ws_alarm', COUNT(*), COALESCE(MAX(ID),0) FROM ws_alarm;
SELECT 'ws_work_order', COUNT(*), COALESCE(MAX(ID),0) FROM ws_work_order;
SELECT 'ws_command', COUNT(*), COALESCE(MAX(ID),0) FROM ws_command;
SELECT 'ws_command_batch_exists', COUNT(*), 0 FROM information_schema.TABLES WHERE TABLE_SCHEMA='dakang' AND TABLE_NAME='ws_command_batch';
SELECT 'ws_device_telemetry', COUNT(*), COALESCE(MAX(ID),0) FROM ws_device_telemetry;
SELECT 'ws_device_msg', COUNT(*), COALESCE(MAX(ID),0) FROM ws_device_msg;
SELECT 'ws_domain_event', COUNT(*), COALESCE(MAX(ID),0) FROM ws_domain_event;
SELECT 'ws_delivery_media', COUNT(*), COALESCE(MAX(ID),0) FROM ws_delivery_media;
SELECT 'ws_order', COUNT(*), COALESCE(MAX(ID),0) FROM ws_order;
SELECT 'ws_card_cnt', COUNT(*), 0 FROM ws_card;
SELECT 'ws_card_balance', COALESCE(SUM(BALANCE_AMOUNT),0), COALESCE(SUM(BALANCE_ML),0) FROM ws_card;
SELECT 'ws_wallet_flow', COUNT(*), COALESCE(MAX(ID),0) FROM ws_wallet_flow;
SELECT 'ws_user', COUNT(*), COALESCE(MAX(ID),0) FROM ws_user;
SELECT 'api_employee', COUNT(*), COALESCE(MAX(ID),0) FROM api_employee;
SQL

echo "主库哨兵快照已写入 ${OUT}（$(wc -l < "${OUT}") 行）"

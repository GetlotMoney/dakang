#!/usr/bin/env bash
# B23 审计导出接真冒烟（隔离环境 13340）
set -uo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
set -a; . ./.env; set +a
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
SCRATCH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE=http://localhost:13340/dakangApi
HUTOOL=~/.m2/repository/cn/hutool/hutool-all/5.8.26/hutool-all-5.8.26.jar
enc() { "$JAVA_HOME/bin/java" -cp "$HUTOOL" "$SCRATCH/RsaEnc.java" "$1"; }
PASS=0; FAIL=0
chk() { if echo "$2" | grep -q "$3"; then PASS=$((PASS+1)); echo "PASS: $1"; else FAIL=$((FAIL+1)); echo "FAIL: $1 —— $(echo "$2" | head -c 250)"; fi; }
chk_not() { if echo "$2" | grep -q "$3"; then FAIL=$((FAIL+1)); echo "FAIL: $1 —— $(echo "$2" | head -c 250)"; else PASS=$((PASS+1)); echo "PASS: $1"; fi; }
q() { docker exec dakang-acc-mysql mysql --default-character-set=utf8mb4 -h127.0.0.1 -uroot -p"$DAKANG_ACC_DB_PASSWORD" dakang -N -e "$1" 2>/dev/null; }

T=$(curl -s -X POST $BASE/api/auth/loginEmployee -H 'Content-Type: application/json' \
  -d "{\"loginName\":\"admin\",\"loginPwd\":\"$(enc 123456)\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["tokenValue"])')

echo "== A1 列表接真（来自数据库，非内存）=="
R=$(curl -s -X POST $BASE/api/auditExport/pageData -H 'Content-Type: application/json' -H "dakang-token: $T" -d '{"current":1,"size":50}')
chk "A1a 列表返回成功" "$R" '"code":0'
chk "A1b 含种子任务号" "$R" 'AUD-EXP-20260714-001'
chk "A1c 下发状态名称" "$R" '"taskStatusName"'
chk "A1d 无伪造已生成" "$R" '"taskStatus":1\|"taskStatus":3'

echo "== A2 提交申请落库（刷新不丢）=="
BEFORE=$(q "SELECT COUNT(*) FROM ws_audit_export_task;")
R=$(curl -s -X POST $BASE/api/auditExport/apply -H 'Content-Type: application/json' -H "dakang-token: $T" \
  -d '{"exportScope":["操作日志","领域事件"],"startTime":"20260801000000","endTime":"20260805235959","businessKeyword":"WO20260801","applyReason":"B23 冒烟：核对审计链路"}')
chk "A2a 申请成功" "$R" '"code":0'
chk "A2b 状态为待生成(1)" "$R" '"taskStatus":1'
chk "A2c 申请人来自会话" "$R" '"applyByName":"超级管理员"'
chk "A2d 文件摘要为空（对象存储未接入）" "$R" '"fileDigest":null'
AFTER=$(q "SELECT COUNT(*) FROM ws_audit_export_task;")
if [ "$AFTER" -eq "$((BEFORE+1))" ]; then PASS=$((PASS+1)); echo "PASS: A2e 确实落库（$BEFORE→$AFTER）"; else FAIL=$((FAIL+1)); echo "FAIL: A2e 未落库（$BEFORE→$AFTER）"; fi

echo "== A3 服务端强制脱敏规则与筛选快照 =="
SNAP=$(q "SELECT CONCAT(MASKING_RULE,'|',FILTER_SUMMARY) FROM ws_audit_export_task ORDER BY ID DESC LIMIT 1;")
chk "A3a 脱敏规则服务端固化" "$SNAP" '手机号中间四位脱敏'
chk "A3b 筛选条件冻结成快照" "$SNAP" '20260801000000 至 20260805235959；业务对象 WO20260801'

echo "== A4 非法范围被白名单拒绝 =="
R=$(curl -s -X POST $BASE/api/auditExport/apply -H 'Content-Type: application/json' -H "dakang-token: $T" \
  -d '{"exportScope":["全部数据"],"startTime":"20260801000000","endTime":"20260805235959","applyReason":"越界尝试"}')
chk "A4 非法范围拒绝" "$R" '不支持的导出范围'

echo "== A5 时间校验 =="
R=$(curl -s -X POST $BASE/api/auditExport/apply -H 'Content-Type: application/json' -H "dakang-token: $T" \
  -d '{"exportScope":["操作日志"],"startTime":"2026-08-01","endTime":"20260805235959","applyReason":"格式错误"}')
chk "A5a 格式非法拒绝" "$R" 'yyyyMMddHHmmss'
R=$(curl -s -X POST $BASE/api/auditExport/apply -H 'Content-Type: application/json' -H "dakang-token: $T" \
  -d '{"exportScope":["操作日志"],"startTime":"20260805235959","endTime":"20260801000000","applyReason":"区间倒置"}')
chk "A5b 区间倒置拒绝" "$R" '起始时间必须早于截止时间'

echo "== A6 重试仅限失败态 =="
FAILED_ID=$(q "SELECT ID FROM ws_audit_export_task WHERE TASK_STATUS=3 LIMIT 1;")
PENDING_ID=$(q "SELECT ID FROM ws_audit_export_task WHERE TASK_STATUS=1 LIMIT 1;")
R=$(curl -s -X POST $BASE/api/auditExport/retry -H 'Content-Type: application/json' -H "dakang-token: $T" -d "{\"id\":$PENDING_ID}")
chk "A6a 待生成任务重试被拒" "$R" '仅失败的任务可以重试'
R=$(curl -s -X POST $BASE/api/auditExport/retry -H 'Content-Type: application/json' -H "dakang-token: $T" -d "{\"id\":$FAILED_ID}")
chk "A6b 失败任务重试成功" "$R" '"code":0'
chk "A6c 回到待生成" "$R" '"taskStatus":1'
CLEARED=$(q "SELECT IFNULL(FAILURE_REASON,'NULL') FROM ws_audit_export_task WHERE ID=$FAILED_ID;")
chk "A6d 失败原因已清空" "$CLEARED" 'NULL'
# @RepeatSubmit 窗口 5 秒且比 CAS 更外层：窗口内重发只会得到 625，测不到 CAS。
# 等过窗口再发，命中的才是「前态已不是失败」这条业务拒绝。
sleep 6
R=$(curl -s -X POST $BASE/api/auditExport/retry -H 'Content-Type: application/json' -H "dakang-token: $T" -d "{\"id\":$FAILED_ID}")
chk "A6e 重复重试被前态CAS拒绝" "$R" '仅失败的任务可以重试'

echo "== A7 平台不得产生「已生成」（对象存储未接入）=="
FORGED=$(q "SELECT COUNT(*) FROM ws_audit_export_task WHERE TASK_STATUS=4 OR FILE_DIGEST IS NOT NULL;")
if [ "$FORGED" -eq 0 ]; then PASS=$((PASS+1)); echo "PASS: A7 无伪造终态与文件摘要"; else FAIL=$((FAIL+1)); echo "FAIL: A7 出现伪造终态 $FORGED 条"; fi

echo "== A8 申请动作留痕进操作日志（与审计同源）=="
sleep 1
LOG=$(q "SELECT CONCAT(LOG_URL,'|',LEFT(LOG_REQUEST_PARAM,200)) FROM api_log_operation WHERE LOG_URL LIKE '%auditExport/apply%' ORDER BY ID DESC LIMIT 1;")
chk "A8a 申请已落操作日志" "$LOG" 'auditExport/apply'
chk_not "A8b 日志不含响应明细（沿用 R-201 脱敏）" "$LOG" 'initialPwd'


# ============ 审查整改回归 ============
echo "== R1 @Size 生效（此前 Default 组被整组跳过 → 500）=="
LONG=$(python3 -c "print('原因'*300)")
R=$(curl -s -X POST $BASE/api/auditExport/apply -H 'Content-Type: application/json' -H "dakang-token: $T" \
  -d "{\"exportScope\":[\"操作日志\"],\"startTime\":\"20260801000000\",\"endTime\":\"20260805235959\",\"applyReason\":\"$LONG\"}")
chk "R1 超长申请原因返回校验提示而非500" "$R" '申请原因不能超过200字'

echo "== R2 手机号双路径脱敏（申请单曾比它要导出的文件更不脱敏）=="
curl -s -X POST $BASE/api/auditExport/apply -H 'Content-Type: application/json' -H "dakang-token: $T" \
  -d '{"exportScope":["操作日志"],"startTime":"20260801000000","endTime":"20260805235959","operatorKeyword":"13900001111","businessKeyword":"张女士","applyReason":"隐私回归"}' >/dev/null
sleep 1
SNAP=$(q "SELECT FILTER_SUMMARY FROM ws_audit_export_task ORDER BY ID DESC LIMIT 1;")
chk "R2a 库内快照已脱敏" "$SNAP" '139\*\*\*\*1111'
chk_not "R2b 库内无明文手机号" "$SNAP" '13900001111'
LOGP=$(q "SELECT LOG_REQUEST_PARAM FROM api_log_operation WHERE LOG_URL LIKE '%auditExport/apply%' ORDER BY ID DESC LIMIT 1;")
chk_not "R2c 操作日志无明文手机号" "$LOGP" '13900001111'

echo "== R3 字典 1382 不再翻倍（此前 2/10 行致字典接口 500）=="
DT=$(q "SELECT COUNT(*) FROM api_dict_type WHERE DICT_TYPE='1382';")
DD=$(q "SELECT COUNT(*) FROM api_dict_data WHERE DICT_TYPE='1382';")
if [ "$DT" = "1" ] && [ "$DD" = "5" ]; then PASS=$((PASS+1)); echo "PASS: R3a 字典行数 1/5"; else FAIL=$((FAIL+1)); echo "FAIL: R3a 字典行数 $DT/$DD"; fi
R=$(curl -s -X POST $BASE/api/dict/listByType -H 'Content-Type: application/json' -d '["1382"]')
chk "R3b 字典接口正常" "$R" '"dictName":"审计导出任务状态"'

echo "== R4 时间戳不被误判为手机号（脱敏边界）=="
R=$(curl -s -X POST $BASE/api/auditExport/apply -H 'Content-Type: application/json' -H "dakang-token: $T" \
  -d '{"exportScope":["操作日志"],"startTime":"20260801000000","endTime":"20260805235959","applyReason":"边界核查"}')
chk "R4 14位时间戳原样保留" "$R" '20260801000000 至 20260805235959'

echo ""
echo "结果: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]

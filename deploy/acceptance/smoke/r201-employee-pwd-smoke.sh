#!/usr/bin/env bash
# R-201 隔离环境冒烟（acc 后端 13340；只动 dakang-acc-* 容器，物理到不了主库）
set -uo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
set -a; . ./.env; set +a
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
SCRATCH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE=http://localhost:13340/dakangApi
HUTOOL=~/.m2/repository/cn/hutool/hutool-all/5.8.26/hutool-all-5.8.26.jar
enc() { "$JAVA_HOME/bin/java" -cp "$HUTOOL" "$SCRATCH/RsaEnc.java" "$1"; }
PASS=0; FAIL=0
chk() { # chk <名称> <实际> <期望包含>
  if echo "$2" | grep -q "$3"; then PASS=$((PASS+1)); echo "PASS: $1";
  else FAIL=$((FAIL+1)); echo "FAIL: $1 —— 实际: $(echo "$2" | head -c 300)"; fi
}
chk_not() { # chk_not <名称> <实际> <不得包含>
  if echo "$2" | grep -q "$3"; then FAIL=$((FAIL+1)); echo "FAIL: $1 —— 实际: $(echo "$2" | head -c 300)";
  else PASS=$((PASS+1)); echo "PASS: $1"; fi
}

ADMIN_CIPHER=$(enc "123456")

echo "== S1 admin 登录（库内 BCrypt）=="
R=$(curl -s -X POST $BASE/api/auth/loginEmployee -H 'Content-Type: application/json' \
  -d "{\"loginName\":\"admin\",\"loginPwd\":\"$(echo $ADMIN_CIPHER)\"}")
chk "S1a admin 登录成功" "$R" '"code":0'
chk "S1b admin 无需强改" "$R" '"pwdChangeRequired":false'
T1=$(echo "$R" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["tokenValue"])')

echo "== S2 admin 建号，回执一次性初始密码 =="
TS=$(date +%H%M%S)
R=$(curl -s -X POST $BASE/api/employee/saveData -H 'Content-Type: application/json' -H "dakang-token: $T1" \
  -d "{\"loginName\":\"smoke$TS\",\"employeeName\":\"冒烟员工\",\"employeeGender\":1,\"employeePhone\":\"137$(date +%d)$TS\" ,\"deptId\":1}")
chk "S2a 建号成功" "$R" '"code":0'
chk "S2b 响应含 initialPwd" "$R" '"initialPwd":"'
INIT_PWD=$(echo "$R" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["initialPwd"])')
echo "   初始密码长度: ${#INIT_PWD}"

echo "== S3 新员工用初始密码登录 → 强改标记 =="
NEW_CIPHER=$(enc "$INIT_PWD")
R=$(curl -s -X POST $BASE/api/auth/loginEmployee -H 'Content-Type: application/json' \
  -d "{\"loginName\":\"smoke$TS\",\"loginPwd\":\"$NEW_CIPHER\"}")
chk "S3a 初始密码可登录" "$R" '"code":0'
chk "S3b 携带强改标记" "$R" '"pwdChangeRequired":true'
T2=$(echo "$R" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["tokenValue"])')

echo "== S4 强改期间业务接口被服务端拒绝 =="
R=$(curl -s -X POST $BASE/api/employee/getPage -H 'Content-Type: application/json' -H "dakang-token: $T2" \
  -d '{"current":1,"size":10}')
chk "S4a 业务接口被拒" "$R" '请先修改初始密码'
R=$(curl -s -X POST $BASE/order/order/page -H 'Content-Type: application/json' -H "dakang-token: $T2" \
  -d '{"current":1,"size":10}')
chk "S4b 订单接口同拒" "$R" '请先修改初始密码'

echo "== S5 弱密码被强度闸拒绝 =="
R=$(curl -s -X POST $BASE/api/employee/updatePassword -H 'Content-Type: application/json' -H "dakang-token: $T2" \
  -d "{\"loginPwd\":\"$NEW_CIPHER\",\"newLoginPwd\":\"$(enc abc123)\"}")
chk "S5 弱密码拒绝" "$R" '8~32'

echo "== S6 正常改密成功，标记清除 =="
R=$(curl -s -X POST $BASE/api/employee/updatePassword -H 'Content-Type: application/json' -H "dakang-token: $T2" \
  -d "{\"loginPwd\":\"$NEW_CIPHER\",\"newLoginPwd\":\"$(enc Xy12345678)\"}")
chk "S6a 改密成功" "$R" '"code":0'
R=$(curl -s -X POST $BASE/api/employee/getPage -H 'Content-Type: application/json' -H "dakang-token: $T2" \
  -d '{"current":1,"size":10}')
chk_not "S6b 旧 token 已失效（不再返回成功）" "$R" '"code":0'

echo "== S7 新密码可登录、初始密码作废 =="
R=$(curl -s -X POST $BASE/api/auth/loginEmployee -H 'Content-Type: application/json' \
  -d "{\"loginName\":\"smoke$TS\",\"loginPwd\":\"$(enc Xy12345678)\"}")
chk "S7a 新密码登录成功" "$R" '"code":0'
chk "S7b 强改标记已清" "$R" '"pwdChangeRequired":false'
R=$(curl -s -X POST $BASE/api/auth/loginEmployee -H 'Content-Type: application/json' \
  -d "{\"loginName\":\"smoke$TS\",\"loginPwd\":\"$NEW_CIPHER\"}")
chk "S7c 初始密码已作废" "$R" '账号或密码错误'

echo "== S8 改密身份锚定会话：admin 传他人 id 也只改自己（原密码校验对准 admin 本人）=="
R=$(curl -s -X POST $BASE/api/employee/updatePassword -H 'Content-Type: application/json' -H "dakang-token: $T1" \
  -d "{\"id\":9001,\"loginPwd\":\"$(enc Xy12345678)\",\"newLoginPwd\":\"$(enc Zz12345678)\"}")
chk "S8 他人旧密码不匹配本人 → 拒绝" "$R" '原密码输入错误'

echo "== S9 acc-ops（种子 BCrypt）登录 =="
R=$(curl -s -X POST $BASE/api/auth/loginEmployee -H 'Content-Type: application/json' \
  -d "{\"loginName\":\"acc-ops\",\"loginPwd\":\"$ADMIN_CIPHER\"}")
chk "S9 acc-ops 登录成功" "$R" '"code":0'

# ==================== 审查整改回归（R1/R2/R4） ====================
echo "== S10 建号回执与口令密文均不得落操作日志表 =="
TS2=$(date +%H%M%S)
R=$(curl -s -X POST $BASE/api/employee/saveData -H 'Content-Type: application/json' -H "dakang-token: $T1" \
  -d "{\"loginName\":\"logchk$TS2\",\"employeeName\":\"日志核查\",\"employeeGender\":1,\"employeePhone\":\"138$(date +%d)$TS2\",\"deptId\":1}")
LOG_PWD=$(echo "$R" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["initialPwd"])')
sleep 1
LOGROW=$(docker exec dakang-acc-mysql mysql -h127.0.0.1 -uroot -p"$DAKANG_ACC_DB_PASSWORD" dakang -N \
  -e "SELECT CONCAT(LOG_REQUEST_PARAM,'||',LOG_RESPONSE_PARAM) FROM api_log_operation WHERE LOG_URL LIKE '%saveData%' ORDER BY ID DESC LIMIT 1;" 2>/dev/null)
chk_not "S10a 初始密码明文未落操作日志" "$LOGROW" "$LOG_PWD"
chk "S10b 响应体已整体省略" "$LOGROW" 'omitted'

echo "== S11 改密请求体的口令密文不得落操作日志（可重放）=="
NEW_CIPHER2=$(enc "$LOG_PWD")
curl -s -X POST $BASE/api/auth/loginEmployee -H 'Content-Type: application/json' \
  -d "{\"loginName\":\"logchk$TS2\",\"loginPwd\":\"$NEW_CIPHER2\"}" > /tmp/r201_l2.json
T3=$(python3 -c 'import json;print(json.load(open("/tmp/r201_l2.json"))["data"]["tokenValue"])')
curl -s -X POST $BASE/api/employee/updatePassword -H 'Content-Type: application/json' -H "dakang-token: $T3" \
  -d "{\"loginPwd\":\"$NEW_CIPHER2\",\"newLoginPwd\":\"$(enc Qw87654321)\"}" > /dev/null
sleep 1
LOGROW2=$(docker exec dakang-acc-mysql mysql -h127.0.0.1 -uroot -p"$DAKANG_ACC_DB_PASSWORD" dakang -N \
  -e "SELECT LOG_REQUEST_PARAM FROM api_log_operation WHERE LOG_URL LIKE '%updatePassword%' ORDER BY ID DESC LIMIT 1;" 2>/dev/null)
chk_not "S11a 口令密文未落操作日志" "$LOGROW2" "$(echo "$NEW_CIPHER2" | head -c 40)"
chk "S11b 口令字段已脱敏" "$LOGROW2" '\*\*\*'

echo "== S12 强改门用专用码 626（不计入 IP 异常封禁）=="
TS3=$(date +%H%M%S)
R=$(curl -s -X POST $BASE/api/employee/saveData -H 'Content-Type: application/json' -H "dakang-token: $T1" \
  -d "{\"loginName\":\"gate$TS3\",\"employeeName\":\"门码核查\",\"employeeGender\":1,\"employeePhone\":\"139$(date +%d)$TS3\",\"deptId\":1}")
GP=$(echo "$R" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["initialPwd"])')
R=$(curl -s -X POST $BASE/api/auth/loginEmployee -H 'Content-Type: application/json' \
  -d "{\"loginName\":\"gate$TS3\",\"loginPwd\":\"$(enc "$GP")\"}")
T4=$(echo "$R" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["tokenValue"])')
R=$(curl -s -X POST $BASE/order/order/page -H 'Content-Type: application/json' -H "dakang-token: $T4" -d '{"current":1,"size":10}')
chk "S12 强改门返回专用码 626" "$R" '"code":626'

echo "== S13 新密码不得与原密码相同（强改门唯一绕过路径）=="
R=$(curl -s -X POST $BASE/api/employee/updatePassword -H 'Content-Type: application/json' -H "dakang-token: $T4" \
  -d "{\"loginPwd\":\"$(enc "$GP")\",\"newLoginPwd\":\"$(enc "$GP")\"}")
chk "S13 原样重设被拒" "$R" '新密码不能与原密码相同'

echo "== S14 改密按账号全端登出（另一会话同时失效）=="
R=$(curl -s -X POST $BASE/api/auth/loginEmployee -H 'Content-Type: application/json' \
  -d "{\"loginName\":\"gate$TS3\",\"loginPwd\":\"$(enc "$GP")\"}")
T5=$(echo "$R" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["tokenValue"])')
curl -s -X POST $BASE/api/employee/updatePassword -H 'Content-Type: application/json' -H "dakang-token: $T4" \
  -d "{\"loginPwd\":\"$(enc "$GP")\",\"newLoginPwd\":\"$(enc Zx19283746)\"}" > /dev/null
R=$(curl -s -X POST $BASE/api/employee/updatePassword -H 'Content-Type: application/json' -H "dakang-token: $T5" \
  -d "{\"loginPwd\":\"$(enc Zx19283746)\",\"newLoginPwd\":\"$(enc Mn56473829)\"}")
chk_not "S14 另一条会话已随改密失效" "$R" '"code":0'

echo ""
echo "结果: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]

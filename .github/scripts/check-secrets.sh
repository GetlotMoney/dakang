#!/usr/bin/env bash
# 凭据入库检查（R-202）。
#
# 全部口令只应存在于不入库的仓库根 .env（模板见 .env.example，其中变量值一律留空）。
# 本脚本只检查 **git 已跟踪** 的文件——工作区里的 .env、构建产物和本机配置不在其列。
#
# 刻意不做的事：不对全仓做泛化的口令关键字扫描。client/.env.demo 的演示口令、
# init/acc-seed 里的 BCrypt 哈希都是有意入库并已在文档登记的，泛化扫描只会产生
# 天天要豁免的噪音，最终没人再看这道门。
set -uo pipefail

fail=0

report() {
  echo "❌ $1" >&2
  fail=1
}

# ---------- 1. 真实凭据文件不得入库 ----------
# .env 是全部口令的唯一载体；*.local 是本机私有覆盖（含小程序真实 appid）
while IFS= read -r tracked; do
  case "$tracked" in
    .env)
      report "仓库根 .env 已被跟踪——全部口令的唯一载体绝不能入库（只允许 .env.example）"
      ;;
    *.local|*.local.*)
      report "本机私有配置已被跟踪：$tracked"
      ;;
    *id_rsa*|*id_ed25519*|*.pem|*.p12|*.jks|*.keystore)
      report "疑似密钥/证书文件已被跟踪：$tracked"
      ;;
  esac
done < <(git ls-files)

# ---------- 2. DAKANG_* 变量不得带值入库 ----------
# .env.example 里这些变量值必须为空；一旦某处写成 DAKANG_DB_PASSWORD=xxx 即为泄漏
leaked_vars=$(git grep -nIE '^[[:space:]]*(export[[:space:]]+)?DAKANG_[A-Z_]*(PASSWORD|SECRET|KEY|TOKEN)[[:space:]]*=[[:space:]]*[^[:space:]#]' \
  -- ':!*.md' ':!.github/scripts/check-secrets.sh' 2>/dev/null || true)
if [ -n "$leaked_vars" ]; then
  report "DAKANG_* 凭据变量带值入库（值必须留空，实际取值只放不入库的 .env）："
  echo "$leaked_vars" >&2
fi

# ---------- 3. 私钥块不得入库 ----------
leaked_keys=$(git grep -nI -- '-----BEGIN .*PRIVATE KEY-----' \
  -- ':!.github/scripts/check-secrets.sh' 2>/dev/null || true)
if [ -n "$leaked_keys" ]; then
  report "私钥块已入库："
  echo "$leaked_keys" >&2
fi

# ---------- 4. 数据库连接串不得内联口令 ----------
leaked_dsn=$(git grep -nIE 'jdbc:mysql://[^"'"'"' ]*[?&]password=[^&"'"'"' ]+' \
  -- ':!.github/scripts/check-secrets.sh' 2>/dev/null || true)
if [ -n "$leaked_dsn" ]; then
  report "JDBC 连接串内联了口令："
  echo "$leaked_dsn" >&2
fi

if [ "$fail" -eq 0 ]; then
  echo "✅ 凭据检查通过：无凭据文件、带值变量、私钥块或内联口令入库"
fi
exit "$fail"

#!/usr/bin/env bash
# 六维达康 PC Demo 构建与容器部署
# 用法：./build-all.sh [server|client|all]（默认 all）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEPLOY="$ROOT/deploy"
TARGET="${1:-all}"

# 已删除的鉴权入口类：干净构建后绝不应再出现在部署 JAR 中（复审 P0-1）。
# 若命中，说明未 clean 的陈旧 target/classes 被重新打包，属于严重安全回归，必须立即失败。
BANNED_SERVER_CLASSES=(
  "MiniDevAuthController" "MiniDevLoginBo" "MiniDevLoginVo"
  "WechatXcxController" "IWechatXcxService" "WechatXcxServiceImpl"
)
# 新的正式登录入口必须存在（否则运行时 /mini/auth 不会被注册）。
REQUIRED_SERVER_CLASSES=("MiniAuthController")

# 子串匹配必须「无管道」：曾用 printf | grep -q，命中时 grep 先退出使 printf 收 SIGPIPE(141)，
# 叠加 set -o pipefail 后整条管道返回 141，if 判为假 —— 恰在禁令类真的存在时静默漏报。
# 用 case 做纯 Shell 子串匹配，不产生管道，无 SIGPIPE 与 pipefail 干扰。
_listing_has() { case "$_LISTING" in *"$1"*) return 0;; *) return 1;; esac; }

# 对已载入 $_LISTING 的条目清单判定禁令/必需类；命中禁令或缺必需类返回 1。
# 独立成函数以便用真实/植入清单直接复验本判定逻辑（不必构造 JAR）。
_scan_listing() {
  local hit=0 c
  for c in "${BANNED_SERVER_CLASSES[@]}"; do
    if _listing_has "/${c}.class"; then
      echo "错误：部署 JAR 仍含已删除鉴权类 ${c}.class（疑似未 clean 的陈旧编译产物），拒绝发布。" >&2
      hit=1
    fi
  done
  for c in "${REQUIRED_SERVER_CLASSES[@]}"; do
    if ! _listing_has "/${c}.class"; then
      echo "错误：部署 JAR 缺少正式登录入口 ${c}.class，拒绝发布。" >&2
      hit=1
    fi
  done
  return "$hit"
}

scan_server_jar() {
  local jar="$1"
  echo "==> [server] 扫描部署 JAR 禁令类：$jar"
  _LISTING="$(unzip -l "$jar" 2>/dev/null)" || { echo "错误：无法读取 JAR $jar" >&2; exit 1; }
  _scan_listing || exit 1
  echo "==> [server] JAR 禁令扫描通过（无旧鉴权类、含 /mini/auth 入口）"
}

build_server() {
  # macOS 的默认 Java 版本可能不符合项目要求；构建过程显式使用 Java 17。
  # 运行时解析 JAVA_HOME，避免依赖交互式 Shell 配置。
  if [[ "$(uname -s)" == "Darwin" ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17)"
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
  fi

  java_major="$(java -version 2>&1 | awk -F '[\".]' '/version/ { print ($2 == 1 ? $3 : $2); exit }')"
  if [[ "$java_major" != "17" ]]; then
    echo "错误：后端构建需要 Java 17，当前为 Java ${java_major:-未知}。请先设置 JAVA_HOME。" >&2
    exit 1
  fi

  # 干净构建：clean 清除陈旧 target/classes，杜绝已删除的类被重新打入 JAR（复审 P0-1 根因）。
  # 同时让旧鉴权入口回归测试进入部署门禁（-Dtest 只跑该类，不引入 Docker 依赖的集成测试）。
  echo "==> [server] mvn clean package（含旧鉴权入口回归测试）"
  (cd "$ROOT/server" && mvn -q clean package -Dtest=MiniAuthServiceTest -DfailIfNoSpecifiedTests=false)
  # 打包后立即扫描构建产物；命中禁令类或缺失新入口即失败，绝不发布。
  scan_server_jar "$ROOT/server/target/dakang-server.jar"
  mkdir -p "$DEPLOY/dist/server"
  jar_target="$DEPLOY/dist/server/dakang-server.jar"
  # 缺少构建产物时执行 Docker Compose，文件型挂载源可能被创建为同名目录。
  # 仅自动移除可确认无内容的构建目录；非空目录需要人工核验。
  if [[ -d "$jar_target" ]]; then
    if find "$jar_target" -mindepth 1 -print -quit | grep -q .; then
      echo "错误：$jar_target 应为 JAR 文件，但当前是非空目录；请人工检查后再构建。" >&2
      exit 1
    fi
    rmdir "$jar_target"
  fi
  # 原位更新 JAR，保持 bind mount 源路径稳定。
  rsync -a "$ROOT/server/target/dakang-server.jar" "$jar_target"
  # 再次扫描落地到 deploy/dist 的副本，确保挂载进容器的正是干净产物。
  scan_server_jar "$jar_target"
  echo "==> [server] recreate container（同步 compose 环境变量）"
  docker compose -f "$DEPLOY/docker-compose.yml" up -d --force-recreate backend
}

build_client() {
  if [[ ! -d "$ROOT/client/node_modules" ]]; then
    echo "错误：前端依赖尚未安装。请先执行：cd client && pnpm install --frozen-lockfile" >&2
    exit 1
  fi
  echo "==> [client] pnpm build:demo"
  # 本地 8081 明确使用 Demo 构建：只预填便捷账号，滑块与真实后端登录校验仍然保留。
  # 普通 pnpm build 继续走 production mode，不携带 Demo 登录信息。
  (cd "$ROOT/client" && pnpm build:demo)
  mkdir -p "$DEPLOY/dist/client"
  rsync -a --delete "$ROOT/client/dist/" "$DEPLOY/dist/client/"
  echo "==> [client] create/recreate web"
  # compose up 同时支持首次创建和后续重建 Web 容器。
  docker compose -f "$DEPLOY/docker-compose.yml" up -d --force-recreate web
}

case "$TARGET" in
  server) build_server ;;
  client) build_client ;;
  all)    build_server; build_client ;;
  *) echo "用法: $0 [server|client|all]"; exit 1 ;;
esac

echo "==> 完成。PC http://localhost:8081/  后端 http://localhost:13330/dakangApi/doc.html"

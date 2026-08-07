#!/usr/bin/env bash
# 生成不包含 Git 元数据、依赖缓存和构建产物的一期源码交付包。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-$(dirname "$ROOT")/dakang-deliverables}"
STAMP="$(date +%Y%m%d-%H%M%S)"
ARCHIVE="$OUT_DIR/dakang-phase1-source-$STAMP.zip"

python3 "$ROOT/tools/check-demo-baseline.py"

mkdir -p "$OUT_DIR"
STAGE="$(mktemp -d "$OUT_DIR/.dakang-package.XXXXXX")"
PACKAGE_OK=0
cleanup() {
  rm -rf "$STAGE"
  if [[ "$PACKAGE_OK" -ne 1 ]]; then
    rm -f "$ARCHIVE"
  fi
}
trap cleanup EXIT
mkdir -p "$STAGE/dakang"

rsync -a --delete --prune-empty-dirs \
  --exclude='.git/' \
  --exclude='.gitattributes' \
  --exclude='.husky/' \
  --exclude='.DS_Store' \
  --exclude='.idea/' \
  --exclude='.vscode/' \
  --exclude='.cursor/' \
  --exclude='.claude/' \
  --exclude='.playwright-mcp/' \
  --exclude='.trellis/.developer/' \
  --exclude='.trellis/.runtime/' \
  --exclude='project.private.config.json' \
  --exclude='miniapp/project.config.json' \
  --exclude='miniapp/src/manifest.json' \
  --exclude='miniapp/src/pages.json' \
  --exclude='miniapp/src/types/' \
  --exclude='/*.png' \
  --exclude='/*.PNG' \
  --exclude='/*.jpg' \
  --exclude='/*.JPG' \
  --exclude='/*.jpeg' \
  --exclude='/*.JPEG' \
  --exclude='client/.agents/skills/element-plus-vue3/' \
  --exclude='client/.agents/skills/frontend-design/' \
  --exclude='client/.agents/skills/tailwindcss/' \
  --exclude='client/.agents/skills/vue/' \
  --exclude='client/.agents/skills/art-design-pro/references/' \
  --exclude='**/__pycache__/' \
  --exclude='*.pyc' \
  --exclude='**/node_modules/' \
  --exclude='client/dist/' \
  --exclude='server/target/' \
  --exclude='deploy/dist/' \
  --exclude='deploy/backups/' \
  --exclude='deploy/backup/' \
  --exclude='deploy/logs/' \
  --exclude='*.bak' \
  --exclude='*.sql.gz' \
  --exclude='miniapp/dist/' \
  --exclude='*.log' \
  --exclude='/.env' \
  --exclude='/deploy/.env' \
  --exclude='/deploy/acceptance/.env' \
  --exclude='.env.local' \
  --exclude='.env.*.local' \
  --exclude='/data/' \
  --exclude='/deploy/data/' \
  --exclude='/deploy/acceptance/data/' \
  --exclude='/tools/device-sim/data/' \
  --exclude='**/data/delivery-media/' \
  "$ROOT/" "$STAGE/dakang/"

required_files=(
  'README.md'
  'AGENTS.md'
  'CLAUDE.md'
  '.gitignore'
  'client/.gitignore'
  'miniapp/.gitignore'
  'docs/development-workflow.md'
  'docs/demo-module-status.md'
  'docs/runtime-entrypoints.md'
  'docs/mqtt-topics.md'
  'docs/requirements/README.md'
  'docs/requirements/decisions.md'
  'docs/requirements/requirements-pool.csv'
  'docs/requirements/demo-business-chain-matrix.md'
  'docs/contracts/L2-recharge-contract-v2.md'
  'docs/contracts/H3-water-exception-contract.md'
  'docs/acceptance/card-lifecycle/README.md'
  'tools/check-demo-baseline.py'
  'tools/package-source.sh'
  'client/.agents/skills/art-design-pro/SKILL.md'
  'miniapp/AGENTS.md'
  'miniapp/README.md'
  'miniapp/.agents/skills/wot-ui/SKILL.md'
  'miniapp/.agents/skills/wot-ui/references/quick-use.md'
  'server/.agents/skills/spring-boot3/SKILL.md'
  'server/.agents/skills/mysql8/SKILL.md'
  'server/src/main/java/com/jbk/tool/data/PageDataVo.java'
)

for required_file in "${required_files[@]}"; do
  if [[ ! -f "$STAGE/dakang/$required_file" ]]; then
    echo "错误：交付包缺少开发基线文件 $required_file。" >&2
    exit 1
  fi
done

python3 "$STAGE/dakang/tools/check-demo-baseline.py"

if find "$STAGE/dakang" -name .git -print -quit | grep -q .; then
  echo "错误：交付目录中仍存在 Git 元数据。" >&2
  exit 1
fi

if find "$STAGE/dakang" -name .cursor -print -quit | grep -q .; then
  echo "错误：交付目录中仍存在 .cursor 工具配置。" >&2
  exit 1
fi

if find "$STAGE/dakang" -name .claude -print -quit | grep -q .; then
  echo "错误：交付目录中仍存在 .claude 工具配置。" >&2
  exit 1
fi

if find "$STAGE/dakang" -name project.private.config.json -print -quit | grep -q .; then
  echo "错误：交付目录中仍存在微信开发者工具私有配置。" >&2
  exit 1
fi

if find "$STAGE/dakang" -maxdepth 1 -type f \( -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' \) -print -quit | grep -q .; then
  echo "错误：交付目录根部仍存在散落图片。" >&2
  exit 1
fi

# 用 grep 而非 rg：rg 在本仓运行环境里是 Claude Code 注入的 shell 函数、不是二进制，
# 子进程中不存在。原写法 `if rg ...; then 报错` 在 rg 缺失时退出码 127（非零）→ 不进 then
# → **污染检查静默通过**，是 fail-open。改为显式判状态码，缺工具即报错。
set +e
pollution_hits="$(grep -rEin \
  'new_user|newuser|quanzhan|课程管理|教练管理|学员管理|/course|worktab|work-tab|fastenter|fast-enter|\.cursor/rules|\.cursor/mcp|perl: warning' \
  "$STAGE/dakang/client" "$STAGE/dakang/miniapp" "$STAGE/dakang/server" "$STAGE/dakang/deploy" 2>&1)"
pollution_status=$?
set -e
if [[ $pollution_status -eq 0 ]]; then
  printf '%s\n' "$pollution_hits" >&2
  echo "错误：交付源码仍包含旧领域、旧导航或工具配置污染。" >&2
  exit 1
elif [[ $pollution_status -ne 1 ]]; then
  printf '%s\n' "$pollution_hits" >&2
  echo "错误：无法完成交付源码污染检查。" >&2
  exit 1
fi

if command -v zip >/dev/null 2>&1; then
  (cd "$STAGE" && COPYFILE_DISABLE=1 zip -qr "$ARCHIVE" dakang)
elif command -v ditto >/dev/null 2>&1; then
  COPYFILE_DISABLE=1 ditto -c -k --keepParent "$STAGE/dakang" "$ARCHIVE"
else
  echo "错误：系统缺少 zip/ditto，无法生成交付包。" >&2
  exit 1
fi

ENTRY_LIST="$STAGE/archive-entries.txt"
unzip -Z1 "$ARCHIVE" > "$ENTRY_LIST"

if grep -Eq '(^|/)__MACOSX/|(^|/)\.DS_Store$|(^|/)\.git(/|$)|(^|/)\.(gitattributes|husky)(/|$)|(^|/)\.(cursor|claude|playwright-mcp)(/|$)|(^|/)project\.private\.config\.json$|(^|/)node_modules/|(^|/)target/|(^|/)dist/|(^|/)deploy/(backups?|logs)/|^dakang/data/|(^|/)__pycache__/|\.pyc$|\.bak$|\.sql\.gz$' "$ENTRY_LIST"; then
  echo "错误：压缩包包含 Git 元数据、IDE/浏览器工具配置、私有配置、数据库备份、运行日志、依赖缓存、Python 缓存或构建产物。" >&2
  exit 1
fi

# 凭据与运行期媒体：按**条目名**拒绝。
# client/.env 与 miniapp/env/.env* 是有意入库的公开档（只含公钥与 Mock 开关），予以放行；
# 其余任何 .env（尤其仓库根与 deploy/ 下的软链）都携带真实口令，绝不允许出包。
if grep -Eq '(^|/)\.env$' "$ENTRY_LIST" \
   && grep -E '(^|/)\.env$' "$ENTRY_LIST" | grep -qvE '^dakang/(client|miniapp/env)/\.env$'; then
  echo "错误：压缩包包含携带真实口令的 .env（仓库根或 deploy/ 软链）。" >&2
  grep -E '(^|/)\.env$' "$ENTRY_LIST" | grep -vE '^dakang/(client|miniapp/env)/\.env$' >&2
  exit 1
fi

if grep -Eq 'delivery-media/' "$ENTRY_LIST"; then
  echo "错误：压缩包包含配送受控媒体（真机签收照片含 EXIF，属隐私事故）。" >&2
  exit 1
fi

if grep -Eqi '^dakang/[^/]+\.(png|jpe?g)$' "$ENTRY_LIST"; then
  echo "错误：压缩包根目录包含散落图片。" >&2
  exit 1
fi

ARCHIVE_CHECK="$STAGE/archive-check"
mkdir -p "$ARCHIVE_CHECK"
unzip -qq "$ARCHIVE" -d "$ARCHIVE_CHECK"

# 对**解压后的真实内容**做凭据扫描。
# 为什么不能只靠 .github/scripts/check-secrets.sh：那份只查 git 已跟踪文件，
# 而交付包是 rsync 从工作树复制的——未跟踪的 .env、备份与运行期媒体对它完全不可见。
# 本仓已因此漏过两次（deploy/backup/ 490MB 含主库 dump；仓库根 .env 含全部口令）。
set +e
secret_hits="$(
  cd "$ARCHIVE_CHECK/dakang" &&
    grep -rEn --exclude='*.md' --exclude='check-secrets.sh' \
      -e 'DAKANG_[A-Z_]*(PASSWORD|SECRET|KEY|TOKEN)[[:space:]]*=[[:space:]]*[^[:space:]#<'"'"'"]' \
      -e '-----BEGIN [A-Z ]*PRIVATE KEY-----' \
      -e 'jdbc:mysql://[^"'"'"' ]*[?&]password=[^&"'"'"' ]+' \
      . 2>&1 | grep -vE ':[[:space:]]*(#|//|\*|--)'
)"
secret_status=$?
set -e
if [[ $secret_status -eq 0 ]]; then
  printf '%s\n' "$secret_hits" >&2
  echo "错误：压缩包内含真实凭据（带值的 DAKANG_* 变量、私钥块或内联口令）。" >&2
  exit 1
elif [[ $secret_status -ne 1 ]]; then
  printf '%s\n' "$secret_hits" >&2
  echo "错误：无法完成压缩包凭据扫描。" >&2
  exit 1
fi

users_dir='Users'
folders_dir='folders'
private_path_pattern="/(${users_dir}|home)/[^/[:space:]]+/|/var/${folders_dir}/[^[:space:]]+|[A-Za-z]:\\\\${users_dir}\\\\[^\\\\[:space:]]+\\\\"
set +e
private_path_matches="$(
  cd "$ARCHIVE_CHECK/dakang" &&
    grep -rEn "$private_path_pattern" . 2>&1
)"
private_path_status=$?
set -e
if [[ $private_path_status -eq 0 ]]; then
  printf '%s\n' "$private_path_matches" >&2
  echo "错误：压缩包包含机器私有绝对路径。" >&2
  exit 1
elif [[ $private_path_status -ne 1 ]]; then
  printf '%s\n' "$private_path_matches" >&2
  echo "错误：无法完成压缩包机器私有路径检查。" >&2
  exit 1
fi

echo "交付包：$ARCHIVE"
echo "大小：$(du -h "$ARCHIVE" | awk '{print $1}')"
echo "SHA-256：$(LC_ALL=C LANG=C shasum -a 256 "$ARCHIVE" | awk '{print $1}')"
echo "Markdown/MDC：$(unzip -Z1 "$ARCHIVE" | awk 'tolower($0) ~ /\.(md|mdc)$/ {count++} END {print count+0}')（含开发 Skill 规范）"
PACKAGE_OK=1

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
  --exclude='deploy/logs/' \
  --exclude='miniapp/dist/' \
  --exclude='/data/' \
  --exclude='*.log' \
  --exclude='.env.local' \
  --exclude='.env.*.local' \
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

if rg -n -i --glob '!docs/requirements/source/**' \
  'new_user|newuser|quanzhan|课程管理|教练管理|学员管理|/course|worktab|work-tab|fastenter|fast-enter|\.cursor/rules|\.cursor/mcp|perl: warning' \
  "$STAGE/dakang/client" "$STAGE/dakang/miniapp" "$STAGE/dakang/server" "$STAGE/dakang/deploy"; then
  echo "错误：交付源码仍包含旧领域、旧导航或工具配置污染。" >&2
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

if grep -Eq '(^|/)__MACOSX/|(^|/)\.DS_Store$|(^|/)\.git(/|$)|(^|/)\.(gitattributes|husky)(/|$)|(^|/)\.(cursor|claude|playwright-mcp)(/|$)|(^|/)project\.private\.config\.json$|(^|/)node_modules/|(^|/)target/|(^|/)dist/|(^|/)deploy/(backups|logs)/|^dakang/data/|(^|/)__pycache__/|\.pyc$' "$ENTRY_LIST"; then
  echo "错误：压缩包包含 Git 元数据、IDE/浏览器工具配置、私有配置、数据库备份、运行日志、依赖缓存、Python 缓存或构建产物。" >&2
  exit 1
fi

if grep -Eqi '^dakang/[^/]+\.(png|jpe?g)$' "$ENTRY_LIST"; then
  echo "错误：压缩包根目录包含散落图片。" >&2
  exit 1
fi

ARCHIVE_CHECK="$STAGE/archive-check"
mkdir -p "$ARCHIVE_CHECK"
unzip -qq "$ARCHIVE" -d "$ARCHIVE_CHECK"

users_dir='Users'
folders_dir='folders'
private_path_pattern="/(${users_dir}|home)/[^/[:space:]]+/|/var/${folders_dir}/[^[:space:]]+|[A-Za-z]:\\\\${users_dir}\\\\[^\\\\[:space:]]+\\\\"
set +e
private_path_matches="$(
  cd "$ARCHIVE_CHECK/dakang" &&
    rg -n -a --hidden --no-ignore \
      "$private_path_pattern" \
      . 2>&1
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

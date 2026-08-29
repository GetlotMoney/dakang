#!/usr/bin/env python3
"""小程序色值闸：页面与业务组件里不许再长出新的颜色字面量，也不许再出现 CSS 渐变。

背景：全端只有 `--wot-color-theme` 一条被接管，`success/warning/danger` 一直落在
wot-design-uni 1.14.0 自带的 #34d19d / #f0883a / #fa4350 上。页面自己写 CSS 想跟旁边
wd-tag 对上颜色，就只能手抄这三个值——于是散出几十处与品牌 token 不同源的字面量，
改主题时组件变了、页面没变。2026-08-08 把四条语义色统一收进 `style/index.scss` 的
`:root, page` 后，这道闸看住它不再长回来。

本闸有两条互相独立的判据：

判据一（色值字面量）：**色值字面量只能出现在定义 token 的地方**，业务代码引用
`var(--app-*)`。两类豁免，逐类写明原因，不做模糊放行：
  1. WHITELIST_FILES —— token 定义处本身，色值就该是字面量。
  2. LEDGER —— 改版尚未走到的页面里的存量，逐文件逐色值登记，**只减不增**。
     哪一批改到哪一页，就在那一批把对应条目删掉；数量只许降。

判据二（渐变禁令，2026-08-10 产品化视觉重构）：`miniapp/src` 下**任何**
linear / radial / conic（含 repeating- 与浏览器前缀）渐变一律失败。视觉层级由真实
产品图、实色功能块、留白与分隔线建立，不靠渐变冒充设计资产。此前这里为「品牌渐变端点」
开过一个 `linear-gradient()` 内放行色值的口子，那正是渐变得以铺开的入口，本轮一并拆除。
**该判据没有白名单、没有存量清单、也不豁免 WHITELIST_FILES**——token 定义处同样不许
定义渐变；留任何一条放行路径，它就会重新变成绕过口径的后门。

注释一律先剥离再判定。写在注释里的色值和渐变是说明，不是实现——本仓库已有先例：
UI 文案闸曾因未剥注释而击中自述注释，反过来逼人删注释。剥离逻辑不另写一份，
直接复用 check-ui-copy.py 的逐字符状态机（该文件改名会在这里 ImportError，是有意的）。

用法：
    python3 tools/check-miniapp-colors.py            # 检查，违规则退出码 1
    python3 tools/check-miniapp-colors.py --report   # 打印可直接粘贴的存量清单，恒退出码 0
    python3 tools/check-miniapp-colors.py --selftest # 自检：证明剥注释与渐变判定没跑偏
"""
from __future__ import annotations

import importlib.util
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCAN_ROOT = ROOT / "miniapp/src"
EXTS = {".vue", ".ts", ".scss", ".css"}

COLOR = re.compile(r"#[0-9a-fA-F]{3,8}\b|rgba?\([0-9\s,.]+\)")
# 渐变禁令：函数名带前缀（-webkit- / -moz- / -o-）和 repeating- 变体都算，
# 匹配到左括号即可——不要求括号配对，`linear-gradient(` 写出来就是渐变。
GRADIENT = re.compile(
    r"(?:-webkit-|-moz-|-o-|-ms-)?(?:repeating-)?(?:linear|radial|conic)-gradient\s*\(",
    re.I,
)

# 1. token 定义处：色值在这里就该是字面量（**只豁免判据一，渐变禁令照样管到这里**）
WHITELIST_FILES = {
    "miniapp/src/style/index.scss",  # --app-* / --wot-* 的唯一定义处
    "miniapp/src/uni.scss",          # uni 内置 SCSS 变量，编译期取值，读不到 CSS 变量
    "miniapp/src/tabbar/config.ts",  # 原生 tabBar 声明，JSON 结构无法引用 CSS 变量
}

# 2. 存量清单：{相对路径: {小写色值: 允许出现次数}}。只减不增。
#    用 `--report` 重新生成；手工调高任何一个数字都是在给闸开洞。
#
#    2026-08-10 清空：最后两条都在登录页——`rgba(46, 124, 246, 0.4)` 是纯 CSS 水滴的
#    涟漪描边（整块换成真实水站产品图后不存在了），`rgba(255, 59, 48, 0.12)` 是步骤条
#    错误态的外发光环（改用 --tint-danger）。业务代码现在零色值字面量，清单保持为空即可，
#    往里加任何一条都要先说明为什么这个色值无法用 token 表达。
LEDGER: dict[str, dict[str, int]] = {}


def _load_strip_comments():
    """复用 check-ui-copy.py 的注释剥离，避免两份状态机各自漂移。"""
    path = ROOT / "tools/check-ui-copy.py"
    spec = importlib.util.spec_from_file_location("check_ui_copy", path)
    if spec is None or spec.loader is None:
        raise ImportError(f"无法加载 {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.strip_comments


strip_comments = _load_strip_comments()


def _sources() -> list[tuple[str, str]]:
    """返回 [(相对路径, 已剥注释正文)]，覆盖 miniapp/src 下全部受管扩展名。"""
    out: list[tuple[str, str]] = []
    for p in sorted(SCAN_ROOT.rglob("*")):
        if p.suffix not in EXTS or not p.is_file():
            continue
        # 白名单与 CI 输出统一使用仓库路径的 `/`；Windows 的 `str(Path)` 会生成
        # 反斜杠，导致 token 定义文件无法命中白名单、把全部主题色误报为业务色值。
        rel = p.relative_to(ROOT).as_posix()
        body = strip_comments(p.read_text(encoding="utf-8", errors="ignore"), p.suffix == ".vue")
        out.append((rel, body))
    return out


def scan() -> dict[str, dict[str, int]]:
    """判据一：返回 {相对路径: {色值: 次数}}，已剥注释、已排除 token 定义文件。"""
    found: dict[str, dict[str, int]] = {}
    for rel, body in _sources():
        if rel in WHITELIST_FILES:
            continue
        for m in COLOR.finditer(body):
            value = m.group(0).lower()
            found.setdefault(rel, {})
            found[rel][value] = found[rel].get(value, 0) + 1
    return found


def scan_gradients() -> list[str]:
    """判据二：返回 `路径:行号  片段` 列表。无白名单、无存量清单、不豁免任何文件。"""
    hits: list[str] = []
    for rel, body in _sources():
        for m in GRADIENT.finditer(body):
            line = body.count("\n", 0, m.start()) + 1
            hits.append(f"{rel}:{line}  {m.group(0).strip()}…")
    return hits


def judge(found: dict[str, dict[str, int]]) -> tuple[list[str], list[str]]:
    """返回（超额条目, 可从清单删除的条目）。"""
    over: list[str] = []
    stale: list[str] = []
    for rel, counts in sorted(found.items()):
        allowed = LEDGER.get(rel, {})
        for value, n in sorted(counts.items()):
            cap = allowed.get(value, 0)
            if n > cap:
                over.append(f"{rel}  {value}  实测 {n} 次 > 清单允许 {cap} 次")
    for rel, allowed in sorted(LEDGER.items()):
        counts = found.get(rel, {})
        for value, cap in sorted(allowed.items()):
            n = counts.get(value, 0)
            if n < cap:
                stale.append(f"{rel}  {value}  已降到 {n} 次（清单仍写 {cap}），请同轮改小或删除")
    return over, stale


def report() -> int:
    found = scan()
    total = sum(sum(c.values()) for c in found.values())
    print("LEDGER: dict[str, dict[str, int]] = {")
    for rel, counts in sorted(found.items()):
        body = ", ".join(f'"{v}": {n}' for v, n in sorted(counts.items()))
        print(f'    "{rel}": {{{body}}},')
    print("}")
    print(f"\n共 {total} 处，分布在 {len(found)} 个文件", file=sys.stderr)
    return 0


def selftest() -> int:
    color_cases = [
        ("/* 注释里写 #fa4350 说明历史 */\n.a { color: var(--app-color-danger); }", 0,
         "块注释里的色值不得命中"),
        ("// 行注释 #34d19d\n.a { color: var(--app-color-success); }", 0,
         "行注释里的色值不得命中"),
        ("<!-- 模板注释 #f0883a -->\n<view />", 0, "HTML 注释里的色值不得命中"),
        (".a { color: #fa4350; }", 1, "真实色值必须命中"),
        (".a { color: #2e7cf6; }", 1, "品牌色拿去当普通色用同样违规"),
        (".a { background: linear-gradient(135deg, #2e7cf6 0%, #0ea5b7 100%); }", 2,
         "渐变里的色值不再被放行——旧品牌端点豁免已拆除"),
        (".a { color: rgba(0, 0, 0, 0.05); }", 1, "rgba 写法同样命中"),
    ]
    gradient_cases = [
        ("// 注释里提 linear-gradient(...) 是说明历史\n.a { background: var(--app-bg-card); }", 0,
         "注释里的渐变字样不得命中"),
        ("<!-- 模板注释 radial-gradient( -->\n<view />", 0, "HTML 注释里的渐变不得命中"),
        ('<wd-skeleton animation="gradient" />', 0,
         "wd-skeleton 的 animation=\"gradient\" 是组件枚举值，不是 CSS 渐变"),
        (".a { background: linear-gradient(180deg, red, blue); }", 1, "linear-gradient 必须命中"),
        (".a { background: radial-gradient(circle, red, blue); }", 1, "radial-gradient 必须命中"),
        (".a { background: conic-gradient(red, blue); }", 1, "conic-gradient 必须命中"),
        (".a { background: repeating-linear-gradient(red, blue); }", 1, "repeating- 变体必须命中"),
        (".a { background: -webkit-linear-gradient(red, blue); }", 1, "浏览器前缀变体必须命中"),
        (".a { background: linear-gradient (red, blue); }", 1, "函数名与括号间留空格照样命中"),
    ]
    bad = 0
    for src, want, why in color_cases:
        body = strip_comments(src, True)
        hits = [m.group(0) for m in COLOR.finditer(body)]
        if len(hits) != want:
            print(f"  ✗ {why}：期望 {want} 命中，实得 {len(hits)} {hits}")
            bad += 1
        else:
            print(f"  ✓ {why}")
    for src, want, why in gradient_cases:
        body = strip_comments(src, True)
        hits = [m.group(0) for m in GRADIENT.finditer(body)]
        if len(hits) != want:
            print(f"  ✗ {why}：期望 {want} 命中，实得 {len(hits)} {hits}")
            bad += 1
        else:
            print(f"  ✓ {why}")
    print("自检通过" if not bad else f"自检失败：{bad} 项")
    return 1 if bad else 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    if "--report" in sys.argv:
        return report()

    over, stale = judge(scan())
    gradients = scan_gradients()
    if not over and not stale and not gradients:
        print("小程序色值检查通过：业务代码没有新增颜色字面量，存量清单与实际一致，且无 CSS 渐变。")
        return 0

    if gradients:
        print(f"渐变检查失败：{len(gradients)} 处 CSS 渐变。\n")
        print("产品化视觉重构后 miniapp/src 一律不使用 linear/radial/conic 渐变；")
        print("层级用真实产品图、实色功能块、留白与分隔线建立。本判据没有白名单。\n")
        for line in gradients:
            print(f"- {line}")
        if over or stale:
            print()

    if over:
        print(f"色值检查失败：{len(over)} 处超出存量清单。\n")
        print("颜色的唯一来源是 miniapp/src/style/index.scss 的 --app-* / --wot-* 变量；")
        print("业务代码写 var(--app-color-danger)，不写 #ff3b30。\n")
        for line in over:
            print(f"- {line}")
    if stale:
        if over:
            print()
        print(f"存量清单有 {len(stale)} 条已经过时（只减不增，降下来就要同轮改小）：\n")
        for line in stale:
            print(f"- {line}")
        print("\n重新生成：python3 tools/check-miniapp-colors.py --report")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

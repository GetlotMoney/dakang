#!/usr/bin/env python3
"""小程序色值闸：页面与业务组件里不许再长出新的颜色字面量。

背景：全端只有 `--wot-color-theme` 一条被接管，`success/warning/danger` 一直落在
wot-design-uni 1.14.0 自带的 #34d19d / #f0883a / #fa4350 上。页面自己写 CSS 想跟旁边
wd-tag 对上颜色，就只能手抄这三个值——于是散出几十处与品牌 token 不同源的字面量，
改主题时组件变了、页面没变。2026-08-08 把四条语义色统一收进 `style/index.scss` 的
`:root, page` 后，这道闸看住它不再长回来。

判据只有一条：**色值字面量只能出现在定义 token 的地方**。业务代码引用 `var(--app-*)`。

三类豁免，逐类写明原因，不做模糊放行：
  1. WHITELIST_FILES —— token 定义处本身，色值就该是字面量。
  2. 品牌渐变端点 —— 只允许写在 `linear-gradient(...)` 内；同样两个值拿去当普通
     色用仍然违规，否则白名单会变成绕过口径的后门。
  3. LEDGER —— 改版尚未走到的页面里的存量，逐文件逐色值登记，**只减不增**。
     哪一批改到哪一页，就在那一批把对应条目删掉；数量只许降。

注释一律先剥离再判定。写在注释里的色值是说明，不是实现——本仓库已有先例：
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
EXTS = {".vue", ".ts", ".scss"}

COLOR = re.compile(r"#[0-9a-fA-F]{3,8}\b|rgba?\([0-9\s,.]+\)")
GRADIENT = re.compile(r"linear-gradient\([^)]*\)", re.I)

# 1. token 定义处：色值在这里就该是字面量
WHITELIST_FILES = {
    "miniapp/src/style/index.scss",  # --app-* / --wot-* 的唯一定义处
    "miniapp/src/uni.scss",          # uni 内置 SCSS 变量，编译期取值，读不到 CSS 变量
    "miniapp/src/tabbar/config.ts",  # 原生 tabBar 声明，JSON 结构无法引用 CSS 变量
}

# 2. 唯一品牌渐变端点（B 活力蓝 → A 净蓝科技），仅限 linear-gradient() 内
BRAND_GRADIENT_STOPS = {"#2e7cf6", "#0ea5b7"}

# 3. 存量清单：{相对路径: {小写色值: 允许出现次数}}。只减不增。
#    用 `--report` 重新生成；手工调高任何一个数字都是在给闸开洞。
LEDGER: dict[str, dict[str, int]] = {
    "miniapp/src/components/evidence-picker.vue": {"#8a8f99": 1, "#c9ced6": 1, "#fff": 1, "rgba(0, 0, 0, 0.65)": 1},
    "miniapp/src/components/home-face-courier.vue": {"rgba(93, 135, 255, 0.1)": 1, "rgba(93, 135, 255, 0.28)": 1},
    "miniapp/src/components/sign-photo-slot.vue": {"#8a8f99": 1, "#c9ced6": 1, "#e6e8eb": 1, "#fff": 1},
    "miniapp/src/pages/courier/task/detail.vue": {"#8a8f99": 1, "#f0f1f3": 3},
    "miniapp/src/pages/courier/task/index.vue": {"#fff": 1},
    "miniapp/src/pages/entry/index.vue": {"#4d93ff": 1, "#5b9bff": 1, "#8f959e": 4, "#c9cdd4": 1, "#e5e6eb": 2, "#e8eaed": 1, "#f2f3f5": 3, "#ffffff": 7, "rgba(0, 0, 0, 0.06)": 1, "rgba(14, 165, 183, 0.06)": 1, "rgba(245, 247, 250, 0)": 1, "rgba(255, 255, 255, 0.42)": 1, "rgba(255, 59, 48, 0.12)": 1, "rgba(31, 35, 41, 0.04)": 1, "rgba(31, 35, 41, 0.07)": 1, "rgba(46, 124, 246, 0.12)": 1, "rgba(46, 124, 246, 0.16)": 1, "rgba(46, 124, 246, 0.24)": 1, "rgba(46, 124, 246, 0.28)": 1, "rgba(46, 124, 246, 0.3)": 1, "rgba(46, 124, 246, 0.4)": 1},
    "miniapp/src/pages/message/detail.vue": {"#fff": 1},
    "miniapp/src/pages/owner/device/detail.vue": {"#fff": 1, "rgba(0, 0, 0, 0.05)": 1, "rgba(100, 106, 115, 0.08)": 1},
    "miniapp/src/pages/owner/device/index.vue": {"#fff": 1},
    "miniapp/src/pages/owner/overview/index.vue": {"#fff": 1, "rgba(0, 0, 0, 0.05)": 1},
    "miniapp/src/pages/owner/service/index.vue": {"#f7f8fa": 1, "#fff": 1, "rgba(0, 0, 0, 0.6)": 1, "rgba(100, 106, 115, 0.4)": 1, "rgba(93, 135, 255, 0.08)": 1},
    "miniapp/src/pages/owner/transaction/index.vue": {"#fff": 2},
    "miniapp/src/pages/owner/wallet/index.vue": {"rgba(0, 0, 0, 0.05)": 1},
    "miniapp/src/pages/user/family/index.vue": {"rgba(240, 136, 58, 0.1)": 1},
    "miniapp/src/pages/user/home/index.vue": {"#fff": 1, "rgba(93, 135, 255, 0.1)": 1},
    "miniapp/src/pages/user/order/detail.vue": {"#b9bec7": 1, "#d5d9e0": 1, "rgba(100, 106, 115, 0.06)": 1, "rgba(93, 135, 255, 0.06)": 1, "rgba(93, 135, 255, 0.45)": 1},
    "miniapp/src/pages/user/order/index.vue": {"#fff": 1},
    "miniapp/src/pages/user/profile/index.vue": {"#646a73": 1, "#92400e": 1, "#9aa0a6": 3, "#a16207": 1, "#b45309": 1, "#d97706": 2, "#f59e0b": 1, "#ffffff": 2, "rgba(0, 0, 0, 0.05)": 1, "rgba(154, 160, 166, 0.08)": 1, "rgba(154, 160, 166, 0.12)": 1, "rgba(245, 158, 11, 0.08)": 1, "rgba(245, 158, 11, 0.22)": 1, "rgba(93, 135, 255, 0.12)": 1, "rgba(93, 135, 255, 0.18)": 1},
    "miniapp/src/pages/user/water/confirm.vue": {"#8c8c8c": 1},
}


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


def scan() -> dict[str, dict[str, int]]:
    """返回 {相对路径: {色值: 次数}}，已剥注释、已排除豁免文件与合法渐变端点。"""
    found: dict[str, dict[str, int]] = {}
    for p in sorted(SCAN_ROOT.rglob("*")):
        if p.suffix not in EXTS or not p.is_file():
            continue
        rel = str(p.relative_to(ROOT))
        if rel in WHITELIST_FILES:
            continue
        body = strip_comments(p.read_text(encoding="utf-8", errors="ignore"), p.suffix == ".vue")
        gradient_spans = [m.span() for m in GRADIENT.finditer(body)]
        for m in COLOR.finditer(body):
            value = m.group(0).lower()
            in_gradient = any(a <= m.start() < b for a, b in gradient_spans)
            if in_gradient and value in BRAND_GRADIENT_STOPS:
                continue
            found.setdefault(rel, {})
            found[rel][value] = found[rel].get(value, 0) + 1
    return found


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
    cases = [
        ("/* 注释里写 #fa4350 说明历史 */\n.a { color: var(--app-color-danger); }", 0,
         "块注释里的色值不得命中"),
        ("// 行注释 #34d19d\n.a { color: var(--app-color-success); }", 0,
         "行注释里的色值不得命中"),
        ("<!-- 模板注释 #f0883a -->\n<view />", 0, "HTML 注释里的色值不得命中"),
        (".a { color: #fa4350; }", 1, "真实色值必须命中"),
        (".a { background: linear-gradient(135deg, #2e7cf6 0%, #0ea5b7 100%); }", 0,
         "品牌渐变端点在 gradient 内放行"),
        (".a { color: #2e7cf6; }", 1, "同样的品牌色拿去当普通色用仍然违规"),
        (".a { background: linear-gradient(135deg, #ff0000, #00ff00); }", 2,
         "非品牌端点的渐变照样命中"),
        (".a { color: rgba(0, 0, 0, 0.05); }", 1, "rgba 写法同样命中"),
    ]
    bad = 0
    for src, want, why in cases:
        body = strip_comments(src, True)
        spans = [m.span() for m in GRADIENT.finditer(body)]
        hits = [
            m.group(0) for m in COLOR.finditer(body)
            if not (any(a <= m.start() < b for a, b in spans)
                    and m.group(0).lower() in BRAND_GRADIENT_STOPS)
        ]
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
    if not over and not stale:
        print("小程序色值检查通过：业务代码没有新增颜色字面量，存量清单与实际一致。")
        return 0

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

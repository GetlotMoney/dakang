#!/usr/bin/env python3
"""UI 文案闸：不许把「写给 agent 看的解释」塞进用户界面。

背景：一期开发里大量本该写在代码注释的内容被写进了界面——系统行为讲解、实现边界
说明、口语化旁白、内部术语（mock / 原型 / 契约 / 口径 / 受控媒体 / 状态码流转）。
用户读到的是一份系统说明书，而不是产品。2026-08-06 做过一次全量清扫（清扫前本闸
报 100+ 处），这道闸看住它不再长回来。见 AGENTS.md「读者分工」铁律 4。

**只管渲染给用户看的字符串，绝不碰代码注释**——注释里写解释是对的，那正是它该待的
地方。本闸最容易犯的错就是把跨行注释的后续行当成模板文本，进而逼人删注释；故注释
状态是逐字符跟踪的，不靠"行首像不像注释"这种启发式。测试文件同样豁免：中文用例名
是写给开发看的。

用法：
    python3 tools/check-ui-copy.py            # 检查，违规则退出码 1
    python3 tools/check-ui-copy.py --report   # 只列现状，恒退出码 0（校准用）
    python3 tools/check-ui-copy.py --selftest # 自检：证明注释识别没跑偏
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCAN_ROOTS = [ROOT / "client/src", ROOT / "miniapp/src"]
EXTS = {".vue", ".ts"}

CJK = re.compile(r"[一-鿿]")

# ---------------------------------------------------------------------------
# 判据一：实现术语泄漏。用户不需要知道这些词，看到只会困惑，或以为系统在骗他。
# ---------------------------------------------------------------------------
BANNED_TERMS = [
    "mock", "Mock", "MOCK",
    # 「快照」「模拟支付」「待接入」刻意不禁：它们是合法的字段标签与能力状态词
    # （「交易快照」是菜单名、「微信支付（模拟）」是用户必须知道的事实）。
    # 一度禁过，14 处命中里绝大多数是短标签——闸误伤会逼人改菜单名，得不偿失。
    "原型", "契约", "口径",
    "联调", "Pay-Sim", "pay-sim", "Refund-Sim", "设备模拟器",
    "受控媒体", "元数据",
    "内部技术验证", "不作虚假", "以页面提示为准",
    "端点", "服务端", "后端",
    "字典编号", "逻辑删除", "白名单",
    # 接入进度播报：用户不关心我们做到哪一步，也据此做不了任何决定
    "未接真", "已接真", "接真", "尚未接入", "正式服务条款",
    # 让用户去做他做不到的事
    "请使用正式登录构建", "构建产物",
]

# 内部标识符泄漏：需求/决策/页面编号、环境变量名、常量名。
# 单独用正则是因为这类是「形状」而不是固定词表，穷举不了。
BANNED_PATTERNS = [
    (re.compile(r"（\s*[A-Z]\d{2,3}\s*）|\(\s*[A-Z]\d{2,3}\s*\)"), "内部编号"),
    (re.compile(r"\b(REQ|D|E2E|B|S|U|O|C)-?\d{2,3}\b"), "内部编号"),
    (re.compile(r"\b[A-Z][A-Z0-9]*_[A-Z0-9_]{2,}\b"), "环境变量/常量名"),
    (re.compile(r"\b(POST|GET|PUT|DELETE)\b\s*/"), "接口方法与路径"),
    (re.compile(r"\bws_[a-z_]+\b|\bapi_[a-z_]+\b"), "数据库表名"),
]

# ---------------------------------------------------------------------------
# 判据二：口语化旁白。产品 UI 不跟用户唠嗑。
# 只收「几乎不可能出现在正经产品文案里」的说法，避免误伤正常措辞。
# ---------------------------------------------------------------------------
BANNED_COLLOQ = [
    "会真的", "真的会", "是正常的", "不是你的", "填了也",
    "这些操作", "这里的操作", "咱", "其实",
]

# ---------------------------------------------------------------------------
# 判据三：长度。超过这个字数的界面文案，基本都在讲系统而不是帮用户做决定。
# ---------------------------------------------------------------------------
MAX_CJK = 40

# 豁免：确有必要且已压到最短的长文案，逐条登记并写明为什么留。只减不增。
ALLOWLIST: dict[str, str] = {}


# ---------------------------------------------------------------------------
# 注释剥离：把源码里的注释整段替换成等长空白，保留行列结构。
# 逐字符状态机而非行级启发式——跨行注释的后续行长得和模板文本一模一样。
# ---------------------------------------------------------------------------
def strip_comments(src: str, is_vue: bool) -> str:
    out = list(src)
    n = len(src)
    i = 0
    # 状态：0 普通  1 行注释  2 块注释  3 HTML 注释  4 单引号  5 双引号  6 反引号
    state = 0
    # .vue 文件：<template> 外的部分按 JS 处理；粗略以是否在 <script> 内判断，
    # 但 HTML 注释在两处都可能出现，故两套注释语法都识别。
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if state == 0:
            if c == "/" and nxt == "/":
                state = 1
                out[i] = out[i + 1] = " "
                i += 2
                continue
            if c == "/" and nxt == "*":
                state = 2
                out[i] = out[i + 1] = " "
                i += 2
                continue
            if src.startswith("<!--", i):
                state = 3
                for k in range(i, min(i + 4, n)):
                    out[k] = " "
                i += 4
                continue
            if c == "'":
                state = 4
            elif c == '"':
                state = 5
            elif c == "`":
                state = 6
        elif state == 1:
            if c == "\n":
                state = 0
            else:
                out[i] = " "
        elif state == 2:
            if c == "*" and nxt == "/":
                out[i] = out[i + 1] = " "
                state = 0
                i += 2
                continue
            if c != "\n":
                out[i] = " "
        elif state == 3:
            if src.startswith("-->", i):
                for k in range(i, min(i + 3, n)):
                    out[k] = " "
                state = 0
                i += 3
                continue
            if c != "\n":
                out[i] = " "
        elif state in (4, 5, 6):
            quote = {4: "'", 5: '"', 6: "`"}[state]
            if c == "\\":
                i += 2
                continue
            if c == quote:
                state = 0
        i += 1
    return "".join(out)


# 引号内文本
QUOTED = re.compile(r"'([^']*)'|\"([^\"]*)\"|`([^`]*)`")
# 标签之间的裸文本（模板文本节点）：> 与 < 之间，或整行只有文本
BETWEEN_TAGS = re.compile(r">([^<>{}]+)<")
BARE_LINE = re.compile(r"^\s*([^<>'\"`{}=;|&?+\[\]]*[一-鿿][^<>'\"`{}=;|&?+\[\]]*)\s*$")


# 嵌套引号 = 这是个表达式（Vue 绑定 :title="a ? '甲' : '乙'"、模板插值等），
# 外层引号里的标识符不是用户看得到的字，只有里面的字面量才是。
# 不做这一步会把 `isRechargeMock ? '微信支付' : '模拟支付'` 整串当文案，
# 因标识符里有 Mock 而误报——闸误报比漏报更伤，会逼人去改变量名。
NESTED_QUOTED = re.compile(r"'([^']*)'|\"([^\"]*)\"")


def unwrap_expression(s: str) -> list[str]:
    if "'" not in s and '"' not in s:
        return [s]
    inner = [
        (m.group(1) or m.group(2) or "").strip()
        for m in NESTED_QUOTED.finditer(s)
    ]
    return [t for t in inner if t] or []


# Vue 插值 {{ expr }}：表达式本身不是文案，但它两侧的字面文字是。
# 不处理会让「金额 {{ x }}（订单口径）」这种最常见的模板行整行提取不到——
# 界面上用户明明看得见「订单口径」四个字，闸却报干净。
INTERPOLATION = re.compile(r"\{\{.*?\}\}")
# 模板字符串插值 ${expr}：同理，SERVICE_TYPE_LABELS[x] 这类常量名在界面上
# 呈现的是它的取值而非名字本身，不剥掉会把常量名当成泄漏误报。
TEMPLATE_SUBST = re.compile(r"\$\{[^{}]*\}")


def strip_interpolation(line: str) -> str:
    return TEMPLATE_SUBST.sub(" ", INTERPOLATION.sub(" ", line))


def extract_visible(text_no_comments: str) -> list[tuple[int, str]]:
    """返回 (行号, 候选用户可见文本)。"""
    found: list[tuple[int, str]] = []
    for raw_lineno, raw_line in enumerate(text_no_comments.splitlines(), 1):
        if not CJK.search(raw_line):
            continue
        lineno = raw_lineno
        # 先把插值里的表达式抠掉再判，剩下的才是模板上的死文字
        line = strip_interpolation(raw_line)
        if not CJK.search(line):
            # 整行中文全在插值表达式里（如 {{ a ? '甲' : '乙' }}），
            # 回到原行按字符串字面量抽取
            line = raw_line
        got = False
        for m in QUOTED.finditer(line):
            raw = (m.group(1) or m.group(2) or m.group(3) or "").strip()
            for s in unwrap_expression(raw):
                if CJK.search(s):
                    found.append((lineno, s))
                    got = True
        for m in BETWEEN_TAGS.finditer(line):
            s = m.group(1).strip()
            if CJK.search(s):
                found.append((lineno, s))
                got = True
        if not got:
            m = BARE_LINE.match(line)
            if m and CJK.search(m.group(1)):
                found.append((lineno, m.group(1).strip()))
    return found


def is_ui_file(p: pathlib.Path) -> bool:
    s = str(p)
    if ".test." in s or ".spec." in s or "/e2e/" in s:
        return False
    if "node_modules" in s or "/dist/" in s:
        return False
    return p.suffix in EXTS and p.is_file()


def judge(text: str) -> list[str]:
    if text in ALLOWLIST:
        return []
    reasons = [f"实现术语「{t}」" for t in BANNED_TERMS if t in text]
    reasons += [f"口语「{t}」" for t in BANNED_COLLOQ if t in text]
    for pat, why in BANNED_PATTERNS:
        m = pat.search(text)
        if m:
            reasons.append(f"{why}「{m.group(0)}」")
    n = len(CJK.findall(text))
    if n > MAX_CJK:
        reasons.append(f"过长（{n} 字 > {MAX_CJK}）")
    return reasons


def scan() -> list[dict]:
    findings: list[dict] = []
    for base in SCAN_ROOTS:
        if not base.is_dir():
            continue
        for p in sorted(base.rglob("*")):
            if not is_ui_file(p):
                continue
            raw = p.read_text(encoding="utf-8", errors="ignore")
            body = strip_comments(raw, p.suffix == ".vue")
            for lineno, text in extract_visible(body):
                reasons = judge(text)
                if reasons:
                    findings.append(
                        {
                            "file": str(p.relative_to(ROOT)),
                            "line": lineno,
                            "text": text,
                            "reasons": reasons,
                        }
                    )
    return findings


def selftest() -> int:
    """证明注释识别没跑偏——闸最危险的失效是把注释判成 UI 并逼人删注释。"""
    cases = [
        ("// 这是行注释，里面写 mock 和契约都合法", 0, "行注释不得命中"),
        ("/* 块注释首行 mock\n   块注释续行 契约 口径 */", 0, "块注释含续行不得命中"),
        ("<!-- HTML 注释首行\n  续行写着 原型快照 也不算 -->", 0, "HTML 注释含续行不得命中"),
        ("const a = '这是渲染给用户的 mock 文案'", 1, "字符串内实现术语必须命中"),
        ("<view>这段文案会真的改变订单</view>", 1, "模板文本口语必须命中"),
        ("const ok = '保存成功'", 0, "正常短文案不得命中"),
        ("// 注释\nconst b = '界面上的契约二字'", 1, "注释之后的真文案仍要命中"),
    ]
    bad = 0
    for src, want, why in cases:
        hits = [t for _, t in extract_visible(strip_comments(src, True)) if judge(t)]
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

    findings = scan()

    if "--report" in sys.argv:
        print(json.dumps(findings, ensure_ascii=False, indent=1))
        print(f"\n共 {len(findings)} 条", file=sys.stderr)
        return 0

    if not findings:
        print("UI 文案检查通过：界面上没有写给 agent 看的解释性文字。")
        return 0

    print(f"UI 文案检查失败：{len(findings)} 处界面文案违规。\n")
    print("界面是给用户做决定用的，不是系统说明书。实现细节、边界说明、设计取舍")
    print("请写进代码注释——那里不会与实现漂移，也不会被用户读到。\n")
    for f in findings:
        print(f"- {f['file']}:{f['line']}  {'；'.join(f['reasons'])}")
        print(f"    {f['text'][:100]}")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())

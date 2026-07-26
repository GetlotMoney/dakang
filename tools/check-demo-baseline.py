#!/usr/bin/env python3
"""校验需求集合、业务链映射和权威文档口径，防止交接结论再次漂移。"""

from __future__ import annotations

import argparse
import csv
import re
import sys
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
POOL = ROOT / "docs/requirements/requirements-pool.csv"
MATRIX = ROOT / "docs/requirements/demo-business-chain-matrix.md"
ROOT_README = ROOT / "README.md"
L2_V2_CONTRACT = ROOT / "docs/contracts/L2-recharge-contract-v2.md"
H3_CONTRACT = ROOT / "docs/contracts/H3-water-exception-contract.md"

GOVERNANCE_IDS = {
    "REQ-001",
    "REQ-002",
    "REQ-081",
    "REQ-082",
    "REQ-083",
    "REQ-084",
    "REQ-085",
}
NON_COUNTED_ASSOCIATIONS = {"REQ-061"}

AUTHORITATIVE_DOCS = [
    ROOT / "AGENTS.md",
    ROOT / "miniapp/AGENTS.md",
    ROOT / "CLAUDE.md",
    ROOT / "README.md",
    ROOT / "docs/development-workflow.md",
    ROOT / "docs/demo-module-status.md",
    ROOT / "docs/requirements/README.md",
    ROOT / "docs/requirements/decisions.md",
]

OBSOLETE_REPORTS = [
    ROOT / "docs/contracts/L2-recharge-and-H3-contract.md",
    ROOT / "docs/contract-review-T4.md",
    ROOT / "docs/demo-foundation-audit-2026-07-14.md",
    ROOT / "docs/demo-navigation-acceptance.md",
    ROOT / "docs/demo-redundancy-audit.md",
    ROOT / "docs/storyline-review-T2.md",
    ROOT / "docs/t2-screenshots",
    ROOT / "docs/README.md",
    ROOT / "docs/development-artifacts.md",
    ROOT / "docs/issue-ledger.md",
    ROOT / "docs/miniapp-demo-blueprint.md",
    ROOT / "docs/requirements/requirements-catalog.md",
    ROOT / "docs/acceptance/card-lifecycle/latest.md",
]

HANDOFF_FILES = {
    "README.md": ROOT / "README.md",
    "AGENTS.md": ROOT / "AGENTS.md",
    "miniapp/AGENTS.md": ROOT / "miniapp/AGENTS.md",
    "build-all.sh": ROOT / "deploy/build-all.sh",
}

MINIAPP_SKILL = ROOT / "miniapp/.agents/skills/wot-ui/SKILL.md"
MINIAPP_SKILL_REQUIRED_REFERENCES = {
    "introduction.md",
    "quick-use.md",
    "common-problems.md",
    "custom-theme.md",
    "config-provider.md",
}


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def display_path(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--matrix",
        type=Path,
        default=MATRIX,
        help="只读校验指定的业务链矩阵副本；默认使用仓库内正式矩阵",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    errors: list[str] = []

    matrix_path = args.matrix.expanduser().resolve()
    if not matrix_path.is_file():
        print(f"一期基线检查失败：\n- 业务链矩阵不是可读文件：{matrix_path}", file=sys.stderr)
        return 1
    try:
        matrix_text = matrix_path.read_text(encoding="utf-8")
    except OSError as error:
        print(f"一期基线检查失败：\n- 无法读取业务链矩阵 {matrix_path}：{error}", file=sys.stderr)
        return 1

    with POOL.open(encoding="utf-8-sig", newline="") as file:
        requirements = list(csv.DictReader(file))

    all_ids = {row["需求ID"] for row in requirements}
    yes_ids = {row["需求ID"] for row in requirements if row["是否MVP必需"] == "是"}
    skeleton_ids = {row["需求ID"] for row in requirements if row["是否MVP必需"] == "骨架"}
    mvp_ids = yes_ids | skeleton_ids
    business_ids = mvp_ids - GOVERNANCE_IDS

    expected_counts = {
        "需求池总数": (len(requirements), 110),
        "唯一需求 ID": (len(all_ids), 110),
        "MVP 必需": (len(yes_ids), 23),
        "骨架": (len(skeleton_ids), 32),
        "MVP/骨架合计": (len(mvp_ids), 55),
        "产品业务需求": (len(business_ids), 48),
        "项目/工程治理需求": (len(GOVERNANCE_IDS & mvp_ids), 7),
    }
    for label, (actual, expected) in expected_counts.items():
        if actual != expected:
            fail(errors, f"{label}应为 {expected}，实际为 {actual}")

    if not GOVERNANCE_IDS <= mvp_ids:
        fail(errors, f"治理需求集合含非 MVP/骨架 ID：{sorted(GOVERNANCE_IDS - mvp_ids)}")

    chain_lines = [line for line in matrix_text.splitlines() if re.match(r"^\|\s*B[^|]*\|", line)]
    if len(chain_lines) != 24:
        fail(errors, f"B 系列需求能力链应为 24 条，实际为 {len(chain_lines)} 条")

    expected_b_ids = {f"B{index:02d}" for index in range(1, 25)}
    b_ids: list[str] = []
    mapped_ids: list[str] = []
    whole_statuses: list[str] = []
    allowed_pc_statuses = {"完整候选", "部分", "契约"}
    for line in chain_lines:
        columns = [column.strip() for column in line.strip().strip("|").split("|")]
        if len(columns) != 7:
            fail(errors, f"B 系列需求能力链表列数不是 7：{line}")
            continue
        b_id = columns[0]
        b_ids.append(b_id)
        if not re.fullmatch(r"B\d{2}", b_id):
            fail(errors, f"B 系列需求能力链编号格式错误：{b_id}")
        for index, label in ((1, "名称"), (2, "产品需求"), (3, "当前证据"), (6, "必要缺口")):
            if not columns[index]:
                fail(errors, f"{b_id or '未知 B 链'} 的{label}不能为空")
        req_ids = set(re.findall(r"REQ-\d{3}", columns[2])) - NON_COUNTED_ASSOCIATIONS
        mapped_ids.extend(sorted(req_ids))
        if columns[4] not in allowed_pc_statuses:
            fail(errors, f"{columns[0]} 使用了未定义的 PC 状态：{columns[4]}")
        whole_statuses.append(columns[5])

    duplicate_b_ids = sorted(b_id for b_id, count in Counter(b_ids).items() if count > 1)
    if duplicate_b_ids:
        fail(errors, f"B 系列需求能力链编号重复：{duplicate_b_ids}")
    actual_b_ids = set(b_ids)
    if actual_b_ids != expected_b_ids:
        fail(
            errors,
            f"B 系列需求能力链编号应恰为 B01-B24；缺少 {sorted(expected_b_ids - actual_b_ids)}；"
            f"多出 {sorted(actual_b_ids - expected_b_ids)}",
        )

    mapped_set = set(mapped_ids)
    if mapped_set != business_ids:
        fail(errors, f"业务链缺少需求：{sorted(business_ids - mapped_set)}")
        fail(errors, f"业务链多出需求：{sorted(mapped_set - business_ids)}")
    duplicate_ids = sorted(req_id for req_id, count in Counter(mapped_ids).items() if count > 1)
    if duplicate_ids:
        fail(errors, f"产品需求被重复映射：{duplicate_ids}")

    expected_statuses = Counter({"闭环候选": 6, "部分": 15, "契约": 3})
    actual_statuses = Counter(whole_statuses)
    if actual_statuses != expected_statuses:
        fail(errors, f"整链状态应为 {dict(expected_statuses)}，实际为 {dict(actual_statuses)}")

    # 结论段与表格双重校验（2026-07-20 收口轮复审）：结论文字里的三类计数必须与表格实际分布一致。
    summary_specs = [("闭环候选", r"整链实现闭环候选：(\d+) 条"), ("部分", r"整链实现部分证据：(\d+) 条"), ("契约", r"整链实现仅契约：(\d+) 条")]
    for status_name, pattern in summary_specs:
        match = re.search(pattern, matrix_text)
        if not match:
            fail(errors, f"业务矩阵结论段缺少「{status_name}」计数行")
        elif int(match.group(1)) != actual_statuses.get(status_name, 0):
            fail(errors, f"结论段「{status_name}」为 {match.group(1)} 条，与表格实际 {actual_statuses.get(status_name, 0)} 条不一致")

    # 宏观 E2E 旅程与 B01～B24 是两套正交编号。E2E 可复用需求，也可引用商业一期需求，
    # 因此只校验自身结构、编号、需求合法性和三维状态，绝不并入 mapped_ids 或 B 链统计。
    expected_e2e_header = "| 编号 | 宏观业务旅程 | 核心需求 ID | 当前证据 | 内部技术闭环 | 外部能力闭环 | 用户验收 | 仍缺的必要环节 |"
    if expected_e2e_header not in matrix_text:
        fail(errors, "业务矩阵缺少固定的宏观 E2E 表头")

    e2e_lines = [line for line in matrix_text.splitlines() if re.match(r"^\|\s*E2E[^|]*\|", line)]
    expected_e2e_ids = {f"E2E-{index:02d}" for index in range(1, 9)}
    e2e_ids: list[str] = []
    allowed_internal_statuses = {"待开工", "开发中", "已完成（模拟环境）"}
    allowed_external_statuses = {"未接入", "部分接入", "已接入"}
    allowed_acceptance_statuses = {"未开始", "待验收", "已验收（模拟范围）", "已验收"}
    for line in e2e_lines:
        columns = [column.strip() for column in line.strip().strip("|").split("|")]
        if len(columns) != 8:
            fail(errors, f"宏观 E2E 表列数不是 8：{line}")
            continue
        e2e_id = columns[0]
        if not re.fullmatch(r"E2E-\d{2}", e2e_id):
            fail(errors, f"宏观 E2E 编号格式错误：{e2e_id}")
        e2e_ids.append(e2e_id)
        for index, label in ((1, "名称"), (2, "核心需求"), (3, "当前证据"), (7, "必要缺口")):
            if not columns[index]:
                fail(errors, f"{e2e_id or '未知 E2E'} 的{label}不能为空")
        if re.search(r"REQ-\d{3}\s*[～~]\s*REQ-\d{3}", columns[2]):
            fail(errors, f"{e2e_id} 的核心需求必须逐项显式列出，禁止范围简写")
        row_req_ids = re.findall(r"REQ-\d{3}", columns[2])
        duplicate_row_req_ids = sorted(
            req_id for req_id, count in Counter(row_req_ids).items() if count > 1
        )
        if duplicate_row_req_ids:
            fail(errors, f"{e2e_id} 的核心需求存在重复项：{duplicate_row_req_ids}")
        unknown_req_ids = set(row_req_ids) - all_ids
        if unknown_req_ids:
            fail(errors, f"{e2e_id} 引用了不存在的需求：{sorted(unknown_req_ids)}")
        if columns[4] not in allowed_internal_statuses:
            fail(errors, f"{e2e_id} 使用了未定义的内部技术闭环状态：{columns[4]}")
        if columns[5] not in allowed_external_statuses:
            fail(errors, f"{e2e_id} 使用了未定义的外部能力闭环状态：{columns[5]}")
        if columns[6] not in allowed_acceptance_statuses:
            fail(errors, f"{e2e_id} 使用了未定义的用户验收状态：{columns[6]}")

    duplicate_e2e_ids = sorted(e2e_id for e2e_id, count in Counter(e2e_ids).items() if count > 1)
    if duplicate_e2e_ids:
        fail(errors, f"宏观 E2E 编号重复：{duplicate_e2e_ids}")
    if set(e2e_ids) != expected_e2e_ids:
        fail(errors, f"宏观 E2E 编号应恰为 {sorted(expected_e2e_ids)}，实际为 {sorted(set(e2e_ids))}")

    governance_section = matrix_text.split("## 4. 7 条项目与工程治理需求", 1)
    if len(governance_section) != 2:
        fail(errors, "业务矩阵缺少工程治理章节")
    else:
        section = governance_section[1].split("## 5.", 1)[0]
        documented_governance = set(re.findall(r"REQ-\d{3}", section))
        if documented_governance != GOVERNANCE_IDS:
            fail(errors, f"治理需求表不一致：{sorted(documented_governance)}")

    legacy_patterns = {
        "旧单维计数 15+4+5": r"15\+4\+5",
        "旧单维计数 19+5": r"19\+5",
        "旧单维计数 10+8+5": r"10\+8\+5",
        "旧单维计数 9+10+5": r"9\+10\+5",
        "错误的 47 条业务需求": r"47\s*条(?:MVP/骨架)?业务需求",
        "误导性 PC 已闭环": r"PC\s*已闭环",
    }
    for document in [*AUTHORITATIVE_DOCS, matrix_path]:
        content = matrix_text if document == matrix_path else document.read_text(encoding="utf-8")
        for label, pattern in legacy_patterns.items():
            if re.search(pattern, content):
                fail(errors, f"{display_path(document)} 仍含{label}")

    root_readme = ROOT_README.read_text(encoding="utf-8")
    contract_pointers = {
        "L2 v2": ("docs/contracts/L2-recharge-contract-v2.md", L2_V2_CONTRACT),
        "独立 H3": ("docs/contracts/H3-water-exception-contract.md", H3_CONTRACT),
    }
    for label, (target, expected_path) in contract_pointers.items():
        pointer_pattern = rf"(?:`{re.escape(target)}`|\]\({re.escape(target)}\))"
        if not re.search(pointer_pattern, root_readme):
            fail(errors, f"README.md 缺少{label}契约的正确指针：{target}")
        resolved_target = (ROOT_README.parent / target).resolve()
        if resolved_target != expected_path.resolve() or not resolved_target.is_file():
            fail(errors, f"README.md 的{label}契约指针目标无效：{target}")

    for path in OBSOLETE_REPORTS:
        if path.exists():
            fail(errors, f"过期审查资产仍存在：{path.relative_to(ROOT)}")
    card_run_dir = ROOT / "docs/acceptance/card-lifecycle/runs"
    for generated_report in card_run_dir.glob("*/report.md"):
        fail(errors, f"水卡验收批次仍含重复 Markdown 报告：{generated_report.relative_to(ROOT)}")

    handoff_text = {name: path.read_text(encoding="utf-8") for name, path in HANDOFF_FILES.items()}
    if "pnpm install --frozen-lockfile" not in handoff_text["README.md"]:
        fail(errors, "README.md 缺少无依赖源码包的前端首次安装步骤")
    if "pnpm install --frozen-lockfile" not in handoff_text["AGENTS.md"]:
        fail(errors, "AGENTS.md 缺少无依赖源码包的首次启动约束")
    if "up -d --force-recreate web" not in handoff_text["build-all.sh"]:
        fail(errors, "build-all.sh 未使用可创建首个 Web 容器的 compose up")
    if re.search(r"docker compose[^\n]*restart web", handoff_text["build-all.sh"]):
        fail(errors, "build-all.sh 仍使用无法支持首次启动的 restart web")

    if not MINIAPP_SKILL.is_file():
        fail(errors, "小程序缺少 wot-ui Skill")
    else:
        skill_references = MINIAPP_SKILL.parent / "references"
        missing_references = sorted(
            name for name in MINIAPP_SKILL_REQUIRED_REFERENCES if not (skill_references / name).is_file()
        )
        if missing_references:
            fail(errors, f"wot-ui Skill 缺少基础参考文档：{missing_references}")
    if (ROOT / "client/.agents/skills/wot-ui").exists():
        fail(errors, "wot-ui Skill 仍位于 PC client 目录")
    if "miniapp/.agents/skills/wot-ui/SKILL.md" not in handoff_text["AGENTS.md"]:
        fail(errors, "AGENTS.md 未登记小程序 wot-ui Skill")
    if "miniapp/.agents/skills/wot-ui/SKILL.md" not in handoff_text["miniapp/AGENTS.md"]:
        fail(errors, "miniapp/AGENTS.md 未登记 wot-ui Skill")

    if errors:
        print("一期基线检查失败：", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print("一期基线检查通过：110 总需求；55 MVP/骨架；48 产品业务 + 7 工程治理；24 条 B 链 = 6 闭环候选 + 15 部分 + 3 契约；8 条宏观业务链校验通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

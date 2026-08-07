#!/usr/bin/env python3
"""后端测试门禁：Skipped 必须为 0，且用例总数不得塌陷。

为什么不能只看 mvn 的退出码：真库用例带 ``@Testcontainers(disabledWithoutDocker=true)``，
Docker 不可用时整批被静默跳过，而 Maven 照样 BUILD SUCCESS。2026-08-04 本机实际发生过一次，
433 条被跳过、门禁全绿——那次是靠人工核对发现的，人工核对不可复制，故固化成脚本。

总数下限同样必要：只断言 skipped=0 挡不住「surefire 因配置错误只跑了 3 个类」这种塌陷。
下限取当前基线的九成，既能挡住数量级塌陷，又不会因正常增删用例天天要改。
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# 2026-08-05 R-201 轮实测 1293（历史 1261 → 1293）。
# 用例数只增不减是常态；确有合理删减时同步下调本值并在提交信息说明。
MIN_TESTS = 1160


def main() -> int:
    if len(sys.argv) != 2:
        print("用法：check-surefire.py <surefire-reports 目录>", file=sys.stderr)
        return 2

    reports_dir = Path(sys.argv[1])
    if not reports_dir.is_dir():
        print(f"错误：找不到测试报告目录 {reports_dir}——测试可能根本没跑起来", file=sys.stderr)
        return 1

    xml_files = sorted(reports_dir.glob("TEST-*.xml"))
    if not xml_files:
        print(f"错误：{reports_dir} 下没有任何 TEST-*.xml", file=sys.stderr)
        return 1

    total = failures = errors = skipped = 0
    skipped_classes = []

    for xml_file in xml_files:
        try:
            root = ET.parse(xml_file).getroot()
        except ET.ParseError as exc:
            print(f"错误：无法解析 {xml_file.name}：{exc}", file=sys.stderr)
            return 1
        cls_tests = int(root.get("tests", 0))
        cls_skipped = int(root.get("skipped", 0))
        total += cls_tests
        failures += int(root.get("failures", 0))
        errors += int(root.get("errors", 0))
        skipped += cls_skipped
        if cls_skipped:
            skipped_classes.append(f"{root.get('name', xml_file.stem)}（{cls_skipped}/{cls_tests}）")

    print(f"测试统计：总数 {total}，失败 {failures}，错误 {errors}，跳过 {skipped}")

    ok = True
    if failures or errors:
        print(f"门禁失败：存在失败 {failures} / 错误 {errors}", file=sys.stderr)
        ok = False
    if skipped:
        print(
            "门禁失败：Skipped 必须为 0。真库用例被整批跳过时 Maven 仍报 BUILD SUCCESS，"
            "这正是本检查存在的原因。被跳过的类：\n  " + "\n  ".join(skipped_classes),
            file=sys.stderr,
        )
        ok = False
    if total < MIN_TESTS:
        print(
            f"门禁失败：用例总数 {total} 低于下限 {MIN_TESTS}，测试范围疑似塌陷。"
            "若为有意删减，请同步下调脚本中的 MIN_TESTS 并在提交信息说明。",
            file=sys.stderr,
        )
        ok = False

    if ok:
        print(f"门禁通过：{total} 条用例全部执行且全部通过，无跳过。")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())

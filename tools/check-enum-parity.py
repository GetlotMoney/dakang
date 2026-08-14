#!/usr/bin/env python3
"""后端枚举 ↔ 三端常量值域一致性门禁。

为什么需要它
------------
2026-08-11 的一个 P0：后端 ``ShipmentStatus`` 是 1..8（``NEED_MANUAL=8``），
而 PC 与小程序的常量都写成了 1..5,9——既漏了真实可达的 8，又收了一个后端不存在的 9。
后果不是少显示一行：小程序的 ``normalizeShipment`` 对未知值 fail-closed 抛错，
订单详情整块降级，「确认收货」入口随之消失；而平台的已签收恒由用户确认落定，
那张订单再也走不完。PC 侧则把「异常待人工」渲染成绿色 success，
按颜色扫读台账的人不会点进去，转人工的包裹没人处置。

值错的那一刻没有任何东西会响：后端测试只看后端、前端测试只看前端，
两边各自自洽。**唯一能挡住它的是把两边放在一起比对**，也就是这个门禁。

覆盖范围
--------
只覆盖「后端枚举下发给端上、端上按值域做白名单判定」的那几组。
纯后端内部枚举不在此列——端上根本看不到它们，比对没有意义。

用法
----
    python3 tools/check-enum-parity.py        # 不一致时退出码 1
"""
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MALL_ENUM = REPO / "server/src/main/java/com/jbk/tool/consts/mall/MallEnum.java"

# (后端枚举名, PC 文件, PC const 对象名, 小程序文件, 小程序数组常量名)
# 任一侧没有对应常量时填 None——不是所有值域两端都需要。
FULFILL_PC = "client/src/api/mall-fulfillment.ts"
FULFILL_MINI = "miniapp/src/api/mall-fulfillment.ts"
TRADE_PC = "client/src/api/mall.ts"
TRADE_MINI = "miniapp/src/api/mall-trade.ts"
AFTERSALE_PC = "client/src/api/mall-aftersale.ts"
AFTERSALE_MINI = "miniapp/src/api/mall-aftersale.ts"

COVERED = [
    ("FulfillStatus", FULFILL_PC, "MALL_FULFILL_STATUS", FULFILL_MINI, "FULFILL_STATUS_VALUES"),
    ("FulfillMode", FULFILL_PC, "MALL_FULFILL_MODE", FULFILL_MINI, "FULFILL_MODE_VALUES"),
    ("ShipmentStatus", FULFILL_PC, "MALL_SHIPMENT_STATUS", FULFILL_MINI, "SHIPMENT_STATUS_VALUES"),
    ("ActorType", None, None, FULFILL_MINI, "ACTOR_VALUES"),
    ("OrderStatus", None, None, TRADE_MINI, "ORDER_STATUS_VALUES"),
    ("PayStatus", None, None, TRADE_MINI, "PAY_STATUS_VALUES"),
    ("AfterSaleStatus", AFTERSALE_PC, "MALL_AFTER_SALE_STATUS", AFTERSALE_MINI, "STATUS_VALUES"),
    ("AfterSaleType", None, None, AFTERSALE_MINI, "TYPE_VALUES"),
    ("InspectResult", AFTERSALE_PC, "MALL_INSPECT_RESULT", None, None),
]


def strip_block_comments(text):
    """先剥注释再解析。

    注释里常有「1待拣货 2待打包 …」这类自述，直接正则会把它们当成枚举项，
    于是门禁匹配到的是文档而不是代码——本仓在别的门禁上踩过这个坑。
    """
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def backend_domain(enum_name):
    src = strip_block_comments(MALL_ENUM.read_text(encoding="utf-8"))
    m = re.search(r"\benum\s+%s\s*\{(.*?)\n\s*\}" % enum_name, src, re.S)
    if m is None:
        sys.exit("后端找不到枚举 %s（门禁与代码已漂移，请先对齐）" % enum_name)
    body = m.group(1)
    # 只取到第一个分号为止：分号之后是字段与方法，里面的数字不是枚举值
    body = body.split(";", 1)[0]
    values = [int(v) for v in re.findall(r"^\s*[A-Z][A-Z0-9_]*\((-?\d+)\s*,", body, re.M)]
    if not values:
        sys.exit("后端枚举 %s 解析出 0 个值，判据失效（拒绝放行）" % enum_name)
    return sorted(values)


def pc_domain(rel_path, const_name):
    path = REPO / rel_path
    if not path.exists():
        return None
    src = strip_block_comments(path.read_text(encoding="utf-8"))
    m = re.search(r"export const %s = \{(.*?)\}\s*as const" % const_name, src, re.S)
    if m is None:
        return None
    values = [int(v) for v in re.findall(r":\s*(-?\d+)", m.group(1))]
    return sorted(values) if values else None


def mini_domain(rel_path, const_name):
    path = REPO / rel_path
    if not path.exists():
        return None
    src = strip_block_comments(path.read_text(encoding="utf-8"))
    m = re.search(r"const %s\s*:[^=]*=\s*\[([^\]]*)\]" % const_name, src)
    if m is None:
        return None
    values = [int(v) for v in re.findall(r"-?\d+", m.group(1))]
    return sorted(values) if values else None


def main():
    problems = []
    checked = 0
    for enum_name, pc_file, pc_const, mini_file, mini_const in COVERED:
        want = backend_domain(enum_name)
        for label, const_name, got in (
                ("PC", pc_const, pc_domain(pc_file, pc_const) if pc_const else None),
                ("小程序", mini_const,
                 mini_domain(mini_file, mini_const) if mini_const else None)):
            if const_name is None:
                continue
            if got is None:
                problems.append(
                    "%s 的 %s 未能解析出值域（常量被改名或改形态？）" % (label, const_name))
                continue
            checked += 1
            if got != want:
                missing = [v for v in want if v not in got]
                extra = [v for v in got if v not in want]
                detail = []
                if missing:
                    detail.append("漏了后端真实可达的 %s" % missing)
                if extra:
                    detail.append("多了后端不存在的 %s" % extra)
                problems.append(
                    "%s 的 %s = %s，与后端 MallEnum.%s = %s 不一致：%s"
                    % (label, const_name, got, enum_name, want, "；".join(detail)))

    if checked == 0:
        sys.exit("枚举一致性门禁未执行任何比对，判据失效（拒绝放行）")

    if problems:
        print("枚举值域一致性检查未通过：\n")
        for item in problems:
            print("  - %s" % item)
        print()
        print("端上按值域做白名单判定，漏一个真实可达的值会让整块数据 fail-closed 抛错，")
        print("多一个不存在的值则让本该报错的畸形数据被静默接受。请改端上常量对齐后端枚举。")
        return 1

    print("枚举值域一致性通过：%d 组常量与后端 MallEnum 逐值同源。" % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""旧 AppID 账号的只读影响扫描与重绑预检（WX-ECO S1 第四节）。

背景
----
小程序换过 AppID。openid 是**按 AppID 隔离**的：同一个人用同一个微信登录新
AppID，拿到的 openid 与旧 AppID 完全不同，因此在系统里会命中「查不到」而走建号，
得到一个全新账号——旧账号连同它的水卡、余额、订单、分润归属一起变成孤儿。

用户想认领旧账号时，唯一的自助路径是「用旧手机号补绑」。但游客态（D-426）下
新账号已经先建好了，补绑时旧号码归属旧账号，命中 `PHONE_OWNED_BY_OTHER`
fail-closed 分支，得到一句「请联系客服处理」。这正是本脚本要量化的问题面。

本脚本做什么
------------
只读盘点 + 重绑预检。**绝不执行任何写操作**，也不生成可直接执行的 UPDATE：
账号合并涉及卡、余额、订单与分润归属，不是一条 SQL 能承担的动作，必须逐个人工裁决。

    python3 tools/wechat-appid-migration-scan.py --scan
    python3 tools/wechat-appid-migration-scan.py --precheck --phone 13900001111

为什么可重复运行
----------------
两个子命令都只跑 SELECT。重复运行结果只随库里数据变化，脚本自身无副作用、无状态，
因此可以在处置过程中反复执行来观察面在缩小。

为什么不自动判定「哪些是旧 AppID 建的」
--------------------------------------
库里**没有**记录账号是在哪个 AppID 下建立的——`ws_user` 只有 `WECHAT_XCX_OPENID`
一列，不带来源标记。因此无法凭数据直接分辨新旧，只能按可观测特征分类：

  A 类：有 openid、无手机号  → 只能靠微信登录进入；换 AppID 后本人再登录会建新号，
                               旧号从此无人可达（连客服都无法验证来电者是不是本人）
  B 类：有手机号、无 openid  → 认领路径存在（补绑），但会撞 PHONE_OWNED_BY_OTHER
  C 类：两者都有             → 正常账号；若其 openid 属于旧 AppID，本人重新登录会建新号，
                               但旧号仍可被客服凭手机号识别
  D 类：两者都无             → 脏数据，需人工核查来源

这个分类是**可观测事实**，不是猜测。真正的「哪些属于旧 AppID」需要在 S1 后续
补一列来源标记才能回答——本脚本把这条缺口显式报出来，而不是假装能算出来。
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTAINER = "dakang-mysql"
DEFAULT_DB = "dakang"


def run_sql(sql: str, container: str, db: str, password: str) -> list[list[str]]:
    """在容器内跑一条只读 SQL，返回制表符分隔的行。

    只允许 SELECT：本脚本的全部价值建立在「跑它绝对安全」之上，
    因此在这里硬拦，而不是靠调用方自觉。
    """
    stripped = sql.strip().lstrip("(").lstrip()
    if not stripped.upper().startswith("SELECT"):
        raise SystemExit(f"拒绝执行非 SELECT 语句：{sql[:60]}")
    proc = subprocess.run(
        ["docker", "exec", container, "mysql", "--default-character-set=utf8mb4",
         "-uroot", f"-p{password}", "-N", "-e", sql, db],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        stderr = "\n".join(
            line for line in proc.stderr.splitlines() if "Warning" not in line)
        raise SystemExit(f"SQL 执行失败：{stderr}")
    return [line.split("\t") for line in proc.stdout.splitlines() if line]


def cmd_scan(args: argparse.Namespace) -> int:
    """四类账号计数 + 处置面量化。"""
    rows = run_sql(
        """
        SELECT
          SUM(CASE WHEN WECHAT_XCX_OPENID IS NOT NULL AND WECHAT_XCX_OPENID <> ''
                    AND (USER_PHONE IS NULL OR USER_PHONE = '') THEN 1 ELSE 0 END),
          SUM(CASE WHEN (WECHAT_XCX_OPENID IS NULL OR WECHAT_XCX_OPENID = '')
                    AND USER_PHONE IS NOT NULL AND USER_PHONE <> '' THEN 1 ELSE 0 END),
          SUM(CASE WHEN WECHAT_XCX_OPENID IS NOT NULL AND WECHAT_XCX_OPENID <> ''
                    AND USER_PHONE IS NOT NULL AND USER_PHONE <> '' THEN 1 ELSE 0 END),
          SUM(CASE WHEN (WECHAT_XCX_OPENID IS NULL OR WECHAT_XCX_OPENID = '')
                    AND (USER_PHONE IS NULL OR USER_PHONE = '') THEN 1 ELSE 0 END),
          COUNT(*)
        FROM ws_user
        """,
        args.container, args.db, args.password)
    a, b, c, d, total = [int(x) if x != "NULL" else 0 for x in rows[0]]

    print("=== 账号身份构成（只读） ===")
    print(f"  A 有openid无手机号  {a:>6}   换 AppID 后本人再登录会建新号，旧号无人可达")
    print(f"  B 有手机号无openid  {b:>6}   可自助补绑认领，但会撞 PHONE_OWNED_BY_OTHER")
    print(f"  C 两者都有          {c:>6}   客服可凭手机号识别")
    print(f"  D 两者都无          {d:>6}   脏数据，需人工核查来源")
    print(f"  合计                {total:>6}")

    print()
    print("=== 有业务资产的账号（合并代价的量化） ===")
    for label, sql in (
        ("持卡", "SELECT COUNT(DISTINCT USER_ID) FROM ws_card"),
        ("有订单", "SELECT COUNT(DISTINCT USER_ID) FROM ws_order"),
        ("有钱包流水", "SELECT COUNT(DISTINCT USER_ID) FROM ws_wallet_flow"),
        ("有分润账户", "SELECT COUNT(DISTINCT USER_ID) FROM ws_income_account"),
    ):
        n = int(run_sql(sql, args.container, args.db, args.password)[0][0] or 0)
        print(f"  {label:<12} {n:>6}")

    print()
    print("=== 待处置的身份冲突台账 ===")
    # 台账表可能尚未迁移到该环境（WX-ECO 本轮禁止主库迁移）。
    # 这种情况要如实说"没这张表"，不能崩掉——上面几段盘点本身仍然成立，
    # 而且"表还没建"恰恰是运营需要知道的事实：此刻发生的冲突一条都没被记下来。
    exists = run_sql(
        "SELECT COUNT(*) FROM information_schema.tables "
        f"WHERE table_schema = '{args.db}' AND table_name = 'ws_identity_conflict'",
        args.container, args.db, args.password)
    if exists[0][0] == "0":
        print("  ！本环境尚未建 ws_identity_conflict（迁移未执行）。")
        print("    后果：此刻发生的身份冲突不会留下任何台账——recorder 会把插入失败")
        print("    吞成一行 error 日志，业务照常拒绝，运营侧毫无感知。")
    else:
        conflict_rows = run_sql(
            "SELECT CONFLICT_TYPE, COUNT(*), SUM(OCCUR_COUNT) FROM ws_identity_conflict "
            "WHERE HANDLE_STATUS = 1 GROUP BY CONFLICT_TYPE",
            args.container, args.db, args.password)
        if not conflict_rows:
            print("  （无待处理冲突）")
        for row in conflict_rows:
            print(f"  {row[0]:<28} 条数 {row[1]:>4}   累计发生 {row[2]:>5}")

    print()
    print("=== 本脚本回答不了的问题（缺口，不是结论） ===")
    print("  · 上面哪些账号属于旧 AppID：ws_user 没有来源标记列，数据上无法分辨。")
    print("    要精确回答，需在 S1 后续补一列建号来源；在那之前只能按上面四类分流处置。")
    print("  · 因此本脚本不产出任何重绑 SQL：合并涉及卡/余额/订单/分润归属，逐个人工裁决。")
    return 0


def cmd_precheck(args: argparse.Namespace) -> int:
    """单个手机号的重绑预检：只回答「现在是什么状态、能不能自助」。"""
    if not args.phone:
        raise SystemExit("--precheck 需要 --phone")
    rows = run_sql(
        "SELECT ID, USER_NAME, "
        "(WECHAT_XCX_OPENID IS NOT NULL AND WECHAT_XCX_OPENID <> ''), "
        "DATA_STATUS, DISABLED_FLAG, USER_STATUS "
        f"FROM ws_user WHERE USER_PHONE = '{args.phone}'",
        args.container, args.db, args.password)

    print(f"=== 重绑预检：{args.phone[:3]}****{args.phone[7:]} ===")
    if not rows:
        print("  该号码在库中不存在 → 新用户走正常注册/补绑，无需人工介入。")
        return 0
    if len(rows) > 1:
        print(f"  ！身份数据污染：同一号码查出 {len(rows)} 行。登录链会 fail-closed，")
        print("    必须先人工清理重复行，任何自助路径都走不通。")
        return 1

    uid, name, has_openid, data_status, disabled, status = rows[0]
    print(f"  命中账号 ID={uid} 名称={name}")
    print(f"  已绑微信={'是' if has_openid == '1' else '否'} "
          f"DATA_STATUS={data_status} DISABLED_FLAG={disabled} USER_STATUS={status}")
    if data_status != "0" or disabled != "1" or status != "1":
        print("  → 账号不可用（已删/禁用/非正常）：登录与补绑都会被 assertUsable 拒绝，")
        print("    需先由运营恢复账号状态，否则用户做什么都进不来。")
        return 1
    if has_openid == "1":
        print("  → 该账号已绑微信。用户若换了微信，自助路径不存在：")
        print("    新微信登录会建新号，用旧号码补绑会撞 PHONE_OWNED_BY_OTHER。")
        print("    需人工裁决（换绑还是合并），本脚本不产出执行语句。")
        return 1
    print("  → 该账号无 openid：用户在小程序内「我的 → 绑定手机号」即可自助认领，")
    print("    无需人工介入。")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="旧 AppID 账号只读影响扫描与重绑预检")
    parser.add_argument("--scan", action="store_true", help="全库影响面盘点")
    parser.add_argument("--precheck", action="store_true", help="单号码重绑预检")
    parser.add_argument("--phone", help="配合 --precheck 使用")
    parser.add_argument("--container", default=DEFAULT_CONTAINER)
    parser.add_argument("--db", default=DEFAULT_DB)
    parser.add_argument("--password", default=os.environ.get("DAKANG_DB_PASSWORD", ""))
    args = parser.parse_args()

    if not args.password:
        raise SystemExit("缺少库口令：设置 DAKANG_DB_PASSWORD 或传 --password")
    if args.scan == args.precheck:
        raise SystemExit("请恰好指定 --scan 或 --precheck 之一")
    return cmd_scan(args) if args.scan else cmd_precheck(args)


if __name__ == "__main__":
    sys.exit(main())

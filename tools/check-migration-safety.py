#!/usr/bin/env python3
"""迁移脚本安全门禁。

迁移是本仓唯一会**直接改主库**的东西，而它没有回滚。AGENTS.md 里写着
「迁移文件只允许非破坏语句」，但在 2026-08-11 之前没有任何东西强制这条规矩——
`2026-07-31-settlement-e2e08-a.sql` 用 `INSERT IGNORE` 灌字典，一路活到第 35 个迁移。

那条为什么是雷：`api_dict_type` / `api_dict_data` 除主键外**没有唯一键**，
`INSERT IGNORE` 无键可撞，等于普通 INSERT。而 init 与迁移会灌同一批字典，
于是「init 首启 + 逐个跑迁移」（验收 rebuild、灾备重建、新部署都走这条）会各插两遍。
`api_dict_type` 主表一旦重复，`ApiDictTypeServiceImpl.getByType` 的 `selectJoinOne`
直接 `TooManyResults` 返回 500，且它带 `@Cached` 不缓存异常，每次调用都 500。
2026-08-11 在一次性容器实测复现：1376~1381 六个类型各重复一次。

老库不受影响（它 init 时还没有这批字典），所以问题只在新建的库上出现——
这正是它能潜伏这么久的原因：主库一直是好的。

用法
----
    python3 tools/check-migration-safety.py     # 违规时退出码 1
"""
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MIGRATIONS = REPO / "deploy/mysql/migrations"

# 除主键外无唯一键的表：INSERT IGNORE 在它们身上不构成幂等，
# 除非同一语句自带 NOT EXISTS 兜底（存量迁移的合规惯用法）
NO_UNIQUE_KEY_TABLES = ("api_dict_type", "api_dict_data", "api_rbac_role_menu")


def strip_sql_comments(sql):
    """先剥注释再判。

    迁移文件的注释里满是「禁止 DROP」「不要用 INSERT IGNORE」这类自述，
    直接扫原文会把说明当成违规——本仓在别的门禁上踩过这个坑。
    """
    sql = re.sub(r"/\*.*?\*/", "", sql, flags=re.S)
    return re.sub(r"--[^\n]*", "", sql)


def check_one(path):
    raw = path.read_text(encoding="utf-8")
    sql = strip_sql_comments(raw)
    problems = []

    if re.search(r"\bDROP\s+(TABLE|DATABASE|SCHEMA)\b", sql, re.I):
        problems.append("含 DROP TABLE/DATABASE：迁移不可回滚，删表即数据永久丢失")
    if re.search(r"\bTRUNCATE\b", sql, re.I):
        problems.append("含 TRUNCATE：同上，且不写 binlog 行事件，事后无法逐行追溯")
    if re.search(r"\bDROP\s+COLUMN\b", sql, re.I):
        problems.append("含 DROP COLUMN：旧版本服务仍在读这一列时会直接报错")

    for m in re.finditer(r"\bDELETE\s+FROM\s+`?(\w+)`?([^;]*);", sql, re.I | re.S):
        if not re.search(r"\bWHERE\b", m.group(2), re.I):
            problems.append("对 %s 的 DELETE 没有 WHERE：会清空整表" % m.group(1))

    for m in re.finditer(r"\bUPDATE\s+`?(\w+)`?\s+SET([^;]*);", sql, re.I | re.S):
        if not re.search(r"\bWHERE\b", m.group(2), re.I):
            problems.append("对 %s 的 UPDATE 没有 WHERE：会改写整表" % m.group(1))

    # 非确定性函数：同一个迁移在两个环境跑出不同数据，事后无法比对
    bad_fn = re.search(r"\b(NOW|CURDATE|CURTIME|SYSDATE|RAND|UUID)\s*\(", sql, re.I)
    if bad_fn:
        problems.append("含非确定性函数 %s()：两个环境跑出的数据不同，无法做一致性核对"
                        % bad_fn.group(1).upper())

    for table in NO_UNIQUE_KEY_TABLES:
        for m in re.finditer(r"INSERT\s+IGNORE\s+INTO\s+`?%s`?[^;]*;" % table,
                             sql, re.I | re.S):
            if not re.search(r"NOT\s+EXISTS", m.group(0), re.I):
                problems.append(
                    "对 %s 用了裸 INSERT IGNORE：该表除主键外无唯一键，IGNORE 无键可撞等于"
                    "普通 INSERT；「init + 全部迁移」会给同一数据各插两遍。请改 "
                    "INSERT ... SELECT ... WHERE NOT EXISTS" % table)

    return problems


def main():
    if not MIGRATIONS.is_dir():
        sys.exit("找不到迁移目录 %s" % MIGRATIONS)
    files = sorted(MIGRATIONS.glob("*.sql"))
    if not files:
        sys.exit("迁移目录为空，判据失效（拒绝放行）")

    failed = {}
    for path in files:
        problems = check_one(path)
        if problems:
            failed[path.name] = problems

    if failed:
        print("迁移安全检查未通过：\n")
        for name, problems in failed.items():
            print("  %s" % name)
            for item in problems:
                print("      - %s" % item)
        print()
        print("迁移是唯一会直接改主库且无回滚的东西。以上语句要么不可逆，")
        print("要么在「init + 全部迁移」这条标准流程下产生错误数据。")
        return 1

    print("迁移安全检查通过：%d 个迁移文件均无破坏性语句与无效幂等。" % len(files))
    return 0


if __name__ == "__main__":
    sys.exit(main())

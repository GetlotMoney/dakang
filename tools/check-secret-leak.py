#!/usr/bin/env python3
"""凭据泄漏门禁：`.env` 里的真值不得出现在任何被 git 追踪的文件里。

AGENTS.md 写着「全部口令只存在于不入库的仓库根 `.env`；任何文档、SQL 注释、配置文件里
都不得再出现口令明文——公开仓库同步会把它们一并带走」。这条此前只靠人记着。

判据取的是**实际值**而不是形态
------------------------------
按正则猜「像密钥的字符串」既漏报又误报：32 位 hex 可能是校验和，而一个弱口令
可能长得像普通单词。直接拿 `.env` 里的真值去被追踪文件里找，命中即泄漏，零猜测。
代价是只能发现「已经在 .env 里登记过的」凭据——没登记的本来也不该存在。

不打印命中的值
--------------
报告只给文件名、行号与变量名。把值打出来等于在 CI 日志里再泄一次。

用法
----
    python3 tools/check-secret-leak.py      # 泄漏时退出码 1
"""
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ENV = REPO / ".env"

# 太短或过于通用的值会把 `true` / `3306` / `root` 这类配到处都是的词判成泄漏。
MIN_LEN = 8
# 这些值即便够长也不算凭据：它们本来就该出现在入库文件里。
ALLOWED_LITERALS = {
    "localhost", "127.0.0.1", "0.0.0.0", "Asia/Shanghai", "utf8mb4",
}

# 变量名含这些片段的按定义就是公开物，不构成泄漏。
# RSA 公钥必须随前端发布——前端用它加密登录口令，后端持私钥解；
# 把它判成泄漏会让门禁天天报一条永远修不掉的假警，而假警多了真警就没人看了。
# 私钥（PRIVATE）不在此列，仍按凭据严查。
PUBLIC_BY_DESIGN = ("PUBLIC",)

# 变量名**全等**这些的按定义就是公开物。与上面的片段匹配分开，因为这里的名字
# 可能与真凭据只差几个字母，用片段/前缀匹配会把凭据一起放行。
#
# 【2026-08-12 一度把 WECHAT_XCX_APPID 放进来，已按用户裁决撤回】
# 当时的理由是「AppID 编译进每份小程序产物，用户在微信客户端里就能看到」。
# 用户裁定 AppID 按密钥管理——本仓的口径就是这一条，不再按「技术上是否可推导」自行判断：
# 门禁的取值范围是运营决定的，不是实现者决定的。
# 撤回后 yml / env 模板 / 端上常量注释里都不得再出现 AppID 字面量，
# 需要说明配置时只写变量名。
# 老板测试档用稳定业务键定位初始化水站。它与 `ws_station.STATION_CODE` 同源，
# 会进入 SQL、表单和模拟器配置，不具备认证能力；只按完整变量名放行，避免把其他
# 含 CODE 的验证码、票据或密钥一并放过。
PUBLIC_BY_EXACT_NAME = frozenset({"DELIVERY_DEMOSIM_STATION_CODE"})


def env_secrets():
    """读 .env，返回 [(变量名, 值)]，只保留够长且不是纯数字的。"""
    if not ENV.exists():
        return None
    pairs = []
    for line in ENV.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, raw = line.partition("=")
        key = key.strip()
        value = raw.strip().strip("'").strip('"')
        if len(value) < MIN_LEN or value.isdigit() or value in ALLOWED_LITERALS:
            continue
        if any(token in key.upper() for token in PUBLIC_BY_DESIGN):
            continue
        if key.upper() in PUBLIC_BY_EXACT_NAME:
            continue
        pairs.append((key, value))
    return pairs


def self_check():
    """判据自检：放行名单不得放行任何真凭据。

    这条比它看起来重要。放行名单是本门禁唯一的减法，一旦有人往里加一个
    看起来像公开物的名字（`WECHAT_XCX_APPSECRET` 只比 `WECHAT_XCX_APPID`
    多三个字母），门禁会继续输出「检查通过」而实际什么都不再看。
    """
    # WECHAT_XCX_APPID 在列：2026-08-12 有人（本 agent）以「它编译进产物、用户可见」
    # 为由把它加进放行名单，被用户否决。判据的取值范围由运营定，不由实现者按
    # 「技术上能不能推导出来」自行判断——把它写进自检，下次同样的理由会当场炸。
    forbidden = {"WECHAT_XCX_APPID", "WECHAT_XCX_APPSECRET", "APIV3_KEY",
                 "MERCHANT_PRIVATE_KEY", "DAKANG_DB_PASSWORD"}
    leaked = forbidden & {name.upper() for name in PUBLIC_BY_EXACT_NAME}
    if leaked:
        raise SystemExit(f"门禁自检失败：放行名单里出现了真凭据 {sorted(leaked)}")
    for name in forbidden:
        if any(token in name for token in PUBLIC_BY_DESIGN):
            raise SystemExit(f"门禁自检失败：片段规则会放行真凭据 {name}")


def candidate_files():
    """被追踪的 + 未被忽略的未追踪文件。

    只扫 `git ls-files` 会漏掉尚未 add 的新文件——而本仓工作树里长期有大量新文件
    （整个商城模块都还没提交）。凭据写进一个新文件时门禁看不见，等到 `git add`
    那一刻就已经入库了，而那时没有任何东西会再检查一次。
    被 .gitignore 忽略的（.env 自己、构建产物）不在此列，它们本来就不会入库。
    """
    listed = subprocess.run(["git", "ls-files"], cwd=REPO,
                            capture_output=True, text=True, check=True)
    untracked = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard"], cwd=REPO,
        capture_output=True, text=True, check=True)
    seen, files = set(), []
    for out in (listed.stdout, untracked.stdout):
        for line in out.splitlines():
            if line and line not in seen:
                seen.add(line)
                files.append(line)
    return files


def main():
    self_check()
    secrets = env_secrets()
    if secrets is None:
        # 没有 .env 的环境（CI 首次检出）无从比对，如实跳过而不是假装通过
        print("跳过：仓库根没有 .env，无可比对的真值。")
        return 0
    if not secrets:
        print("跳过：.env 里没有够长的可比对值。")
        return 0

    leaks = []
    for rel in candidate_files():
        path = REPO / rel
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for key, value in secrets:
            if value in text:
                line_no = next(
                    (i for i, ln in enumerate(text.splitlines(), 1) if value in ln), 0)
                leaks.append((rel, line_no, key))

    if leaks:
        print("凭据泄漏检查未通过：.env 的真值出现在会入库的文件里\n")
        for rel, line_no, key in leaks:
            # 只报位置与变量名，绝不打印值本身
            print("  - %s:%d 含 %s 的真值" % (rel, line_no, key))
        print()
        print("这些文件会随公开仓库同步一并带走（未追踪的新文件一经 git add 同样如此）。")
        print("请把该值移回 .env，入库文件里只保留占位或环境变量引用；")
        print("已泄漏的凭据必须视为作废并轮换。")
        return 1

    print("凭据泄漏检查通过：.env 的 %d 个值均未出现在任何会入库的文件中。" % len(secrets))
    return 0


if __name__ == "__main__":
    sys.exit(main())

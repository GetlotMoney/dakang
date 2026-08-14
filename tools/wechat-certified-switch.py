#!/usr/bin/env python3
"""微信认证门的一次性切换（小程序主体完成微信认证后执行）。

背景
----
未认证时微信在**组件层**禁用 ``open-type="getPhoneNumber"``：点击不弹窗、回调根本不触发，
连错误分支都跑不到。因此登录链当时改成「仅微信身份建号、手机号后续自助补绑」，
并在两处各留了一个开关。认证完成后这两处必须**同时**翻，只翻一处会造出更差的状态：

- 只翻小程序：按钮出现了，但服务端仍走「无手机号建号」，用户授权完手机号也不会被绑上；
- 只翻服务端：注册链要求手机号，而按钮根本没渲染出来，新用户直接注册不了。

主体完成认证后两者**解耦**：组件开关管「能不能弹手机号授权」，建号开关管「新用户要不要
立刻绑号」，是两件事。游客态要的正是「组件可用 + 允许无号建号」这个此前不存在的组合。
本脚本据此提供三种合法目标态，并挡住唯一那种非法组合（组件不可用却要求注册即绑号——
新用户既拿不到 phoneCode 又不许无号建号，注册链断死且不报错）。

为什么小程序侧是源码常量而不是环境变量
--------------------------------------
2026-08-01 产物污染事故：多个残留 watch 编译器各持启动时刻的 env 快照竞写同一 dist，
环境变量注入的值在产物里不可判定；而「注入失败/取到旧值」与「关闭态」表现完全一致，
故障会潜伏到要开放绑定那天才爆。源码常量没有注入环节，产物值恒等于源码值。
代价是**翻完必须重新构建小程序产物**，脚本会在结尾提示。

用法
----
    python3 tools/wechat-certified-switch.py --status
    python3 tools/wechat-certified-switch.py --guest   # 游客态：进门即浏览，绑号推迟到消费时
    python3 tools/wechat-certified-switch.py --strict  # 注册即绑号：新用户必须先绑手机号
    python3 tools/wechat-certified-switch.py --off     # 回滚到未认证态

幂等：重复切到同一目标态不报错、不重复改动。

绑号闸（MiniPhoneGate）与本开关是配套的：游客态放行的是「进门与浏览」，
真正动钱与落履约归属的动作仍由闸在 Service 层拦住。开关翻到 guest 而闸没建，
等于让无手机号账号直接消费。
"""
import argparse
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ENV = REPO / ".env"
RUNTIME = REPO / "miniapp/src/api/runtime.ts"
# 两个 profile 都必须从同一个环境变量取值。dev.yml 曾把它硬编码成 true，而 prod 读 .env，
# 于是默认 profile（dev）与 docker/验收（prod）取值相反，翻开关只改得动一半。
PROFILES = [REPO / "server/src/main/resources/application-dev.yml",
            REPO / "server/src/main/resources/application-prod.yml"]
PROFILE_EXPECT = "enabled: ${MINI_PHONELESS_REGISTER_ENABLED:false}"

# 服务端：仅微信身份建号。认证后应为 false（恢复注册即绑号）。
ENV_KEY = "MINI_PHONELESS_REGISTER_ENABLED"
# 小程序：手机号组件是否渲染。认证后应为 true。
TS_CONST = "WX_PHONE_COMPONENT_AVAILABLE"

TS_PATTERN = re.compile(
    r"^(export const %s = )(true|false)$" % TS_CONST, re.M)


def read_env_flag(text):
    m = re.search(r"^%s=(.*)$" % ENV_KEY, text, re.M)
    return None if m is None else m.group(1).strip()


def read_ts_flag(text):
    m = TS_PATTERN.search(text)
    return None if m is None else m.group(2)


def require_profiles_read_env():
    """两个 profile 都必须从环境变量取值，否则这个脚本只能改动一半。

    硬编码的那一半不会报错、不会告警，只会让同一份代码在 dev 与 prod 下表现相反——
    而 spring.profiles.active 默认就是 dev，所以本机跑起来的恰恰是没被翻到的那一侧。
    """
    for path in PROFILES:
        if not path.exists():
            sys.exit("缺少 %s" % path)
        if PROFILE_EXPECT not in path.read_text(encoding="utf-8"):
            sys.exit("%s 未从环境变量读取 phoneless-register，开关会在此处分叉。\n"
                     "  期望存在这一行：%s" % (path.name, PROFILE_EXPECT))


def load():
    if not ENV.exists():
        sys.exit("缺少 %s（凭据文件不入库，请先按 .env.example 建好）" % ENV)
    require_profiles_read_env()
    env_text = ENV.read_text(encoding="utf-8")
    ts_text = RUNTIME.read_text(encoding="utf-8")
    phoneless = read_env_flag(env_text)
    component = read_ts_flag(ts_text)
    if phoneless is None:
        sys.exit("在 .env 里找不到 %s" % ENV_KEY)
    if component is None:
        sys.exit("在 %s 里找不到 %s 常量声明" % (RUNTIME.name, TS_CONST))
    return env_text, ts_text, phoneless, component


def describe(phoneless, component):
    """三种合法组合，外加一种非法组合。

    组件开关（能不能弹手机号授权）与建号开关（新用户要不要立刻绑号）是**两件事**，
    此前把它们绑成「同向翻转」是因为未认证期间组件不可用、注册链只能靠无号建号兜住。
    主体完成认证后两者解耦：游客态要的正是「组件可用 + 允许无号建号」这个组合。

    非法的只有一种：组件不可用却又要求注册即绑号——新用户既拿不到 phoneCode
    又不许无号建号，整条注册链断死，而且不会有任何报错。
    """
    if component == "false" and phoneless == "true":
        return "未认证态（组件被平台禁用，仅微信身份建号、手机号自助补绑）", "uncertified"
    if component == "true" and phoneless == "false":
        return "注册即绑号（认证完成，新用户必须先绑手机号才建号）", "strict"
    if component == "true" and phoneless == "true":
        return "游客态（认证完成，进门即可浏览，绑号推迟到消费时）", "guest"
    return ("**非法组合**——组件不可用却要求注册即绑号：新用户拿不到 phoneCode 又不许无号建号，"
            "整条注册链断死且不报错", "illegal")


def cmd_status():
    _, _, phoneless, component = load()
    state, kind = describe(phoneless, component)
    print("当前状态：%s" % state)
    print("  %-34s = %s   （.env；true=允许无手机号建号）" % (ENV_KEY, phoneless))
    print("  %-34s = %s   （runtime.ts；true=渲染手机号授权按钮）" % (TS_CONST, component))
    return 1 if kind == "illegal" else 0


# 三种目标态 → (phoneless, component)
TARGETS = {
    "uncertified": ("true", "false"),
    "strict": ("false", "true"),
    "guest": ("true", "true"),
}


def apply(target):
    env_text, ts_text, phoneless, component = load()
    _, kind = describe(phoneless, component)
    if kind == target:
        print("已是目标状态，无需改动。")
        return 0
    new_phoneless, new_component = TARGETS[target]

    new_env = re.sub(r"^%s=.*$" % ENV_KEY, "%s=%s" % (ENV_KEY, new_phoneless),
                     env_text, count=1, flags=re.M)
    new_ts = TS_PATTERN.sub(r"\g<1>" + new_component, ts_text, count=1)
    if new_env == env_text and new_ts == ts_text:
        sys.exit("两处均未匹配到可改内容，已中止（不做部分改动）")

    # 先都算好再一起落盘：任一侧写失败都不会留下「只翻了一半」的状态
    ENV.write_text(new_env, encoding="utf-8")
    RUNTIME.write_text(new_ts, encoding="utf-8")

    # 自检：回读而不是相信刚才写的内容
    _, _, got_phoneless, got_component = load()
    if got_phoneless != new_phoneless or got_component != new_component:
        sys.exit("落盘后回读不一致，请人工核查：%s=%s / %s=%s"
                 % (ENV_KEY, got_phoneless, TS_CONST, got_component))

    print("已切换到：%s" % describe(got_phoneless, got_component)[0])
    print("  %s = %s" % (ENV_KEY, got_phoneless))
    print("  %s = %s" % (TS_CONST, got_component))
    print()
    print("还需两步才真正生效：")
    print("  1. 重新构建小程序产物——组件开关是源码常量，旧产物里仍是旧值；")
    print("     构建前后请确认编译器进程归零（pgrep -f 'vite|uni' 应为空），")
    print("     否则残留 watch 编译器会竞写同一 dist（2026-08-01 事故）。")
    print("  2. 重启后端使 .env 生效（docker compose up -d 或重启验收后端）。")
    return 0


def main():
    parser = argparse.ArgumentParser(description="微信认证门一次性切换")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--status", action="store_true", help="只查看当前状态")
    group.add_argument("--guest", action="store_true",
                       help="游客态：进门即可浏览，绑号推迟到消费时（当前产品口径）")
    group.add_argument("--strict", action="store_true", help="注册即绑号：新用户必须先绑手机号")
    group.add_argument("--off", action="store_true", help="回滚到未认证态")
    args = parser.parse_args()
    if args.status:
        return cmd_status()
    if args.guest:
        return apply("guest")
    if args.strict:
        return apply("strict")
    return apply("uncertified")


if __name__ == "__main__":
    sys.exit(main())

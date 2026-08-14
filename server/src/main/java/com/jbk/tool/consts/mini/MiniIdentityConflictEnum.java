package com.jbk.tool.consts.mini;

/**
 * 账号身份冲突的类型与场景（WX-ECO S1）。字符串码而非字典（沿用 {@code SplitV2Enum} 先例）；接页面需下拉时再补字典，只加不改。
 *
 * @author dakang
 * @since 2026-08-12
 */
public interface MiniIdentityConflictEnum {

    /**
     * 冲突类型。四类都必须 fail-closed，<b>禁止自动合并账号</b>——
     * 两个账号各自带着卡、订单、余额与分润归属，合并不是登录链路能承担的动作。
     */
    enum Type {
        /** 该微信已绑定另一个手机号：openid 命中账号 A，但请求携带的号码不是 A 的号。 */
        OPENID_BOUND_OTHER_PHONE,
        /** 该手机号已绑定另一个微信：号码命中账号 B，而 B 的 openid 不是当前这个。 */
        PHONE_BOUND_OTHER_WECHAT,
        /** 该手机号已被其他账号使用：自助补绑时号码归属他人（存量老账号认领即撞这条）。 */
        PHONE_OWNED_BY_OTHER,
        /** 并发抢绑落败：读到无主、写入前被另一请求抢先，影响行数 0 或撞唯一键。 */
        CONCURRENT_BIND_LOST,
        /** 身份数据污染：同一 openid 或同一手机号查出多于一行，说明库里已有脏数据。 */
        IDENTITY_DUPLICATED,
    }

    /** 冲突发生的场景，决定运营该找谁核实。 */
    enum Scene {
        /** 登录绑定链（票据路径）：发起方此刻还没有账号。 */
        LOGIN_BIND,
        /** 已登录用户自助补绑：发起方就是当前会话账号。 */
        SELF_BIND,
    }

    /** 处理状态。与建表注释 HANDLE_STATUS 同源。 */
    enum HandleStatus {
        PENDING(1),
        HANDLED(2),
        IGNORED(3);

        private final int value;

        HandleStatus(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    /**
     * 票据路径下发起方尚未建号时的哨兵值。不用 NULL/空串：ACTOR_USER_ID 参与幂等键拼接，值不恒定则幂等失效，用 0 恒定。
     */
    long ACTOR_ABSENT = 0L;
}

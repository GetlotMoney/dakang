package com.jbk.tool.consts.message;

/**
 * 站内消息域枚举（值对齐 02-ws-business.sql 字典 1312/1313）。
 * <p>一期只做站内渠道；微信订阅消息不在范围（E2E-03 包A / A6）。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
public interface MessageEnum {

    /** 消息领域 (dictType=1312) */
    enum MsgDomain {
        WATER(1, "取水"),
        CARD(2, "卡券"),
        DELIVERY(3, "配送"),
        OWNER(4, "机主"),
        SYSTEM(5, "系统");

        private final int value;
        private final String desc;

        MsgDomain(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }
    }

    /** 消息渠道：一期固定站内 */
    enum MsgChannel {
        IN_APP(1, "站内");

        private final int value;
        private final String desc;

        MsgChannel(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }
    }

    /** 发送状态 (dictType=1313)：站内消息与业务动作同事务落库，落库即送达=4 */
    enum SendStatus {
        PENDING(1, "待发送"),
        SENDING(2, "发送中"),
        FAILED(3, "发送失败"),
        DELIVERED(4, "已送达");

        private final int value;
        private final String desc;

        SendStatus(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }
    }
}

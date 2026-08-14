package com.jbk.tool.consts.mini;

/**
 * 微信订阅通知的事件类型与出站状态（WX-ECO S2）。事件类型用自描述字符串码；
 * 处理状态复用字典 1408（与商城物流 outbox 同一套五态），不铸第三套口径。
 *
 * @author dakang
 * @since 2026-08-12
 */
public interface WechatNotifyEnum {

    /**
     * 首批七类事件（任务书 S2）。取名按用户视角而非内部状态机名；事件类型进幂等键与运维报表。
     */
    enum EventType {
        /** 支付成功（充值/商城通用）。 */
        PAYMENT_SUCCEEDED,
        /** 充值到账：权益真正落到卡上才发，不是支付成功就发。 */
        RECHARGE_CREDITED,
        /** 配送员已接单。 */
        DELIVERY_ACCEPTED,
        /** 即将送达。 */
        DELIVERY_ARRIVING,
        /** 商城已发货。 */
        MALL_SHIPPED,
        /** 商城已送达。 */
        MALL_DELIVERED,
        /** 退款结果（成功或失败都发——用户最怕的是没有消息）。 */
        REFUND_SETTLED,
        /** 申诉处理结果。 */
        APPEAL_SETTLED,
        /** 自动补货失败：不通知的话用户会以为水在路上。 */
        AUTO_REFILL_FAILED,
    }

    /** 业务对象类型：与 BIZ_OBJECT_NO 一起构成幂等键的定位部分。 */
    enum BizObjectType {
        ORDER,
        DELIVERY_TASK,
        MALL_ORDER,
        AFTER_SALE,
        REFILL_RULE,
    }

    /**
     * 处理状态。数值与字典 1408 严格一致，与 {@code ws_mall_logistics_outbox} 共用；不新建枚举值也不换数值。
     */
    enum ProcessingStatus {
        PENDING(1),
        PROCESSING(2),
        PROCESSED(3),
        RETRY_WAIT(4),
        NEED_MANUAL(5);

        private final int value;

        ProcessingStatus(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    /**
     * 「已处理但没发出去」的原因。缺失时不发送、不伪造（任务书），且必须留痕，否则与真发成功无法区分。
     */
    enum SkipReason {
        /** 模板 ID 未配置：不发送、不报错、不影响主业务。 */
        TEMPLATE_UNCONFIGURED,
        /** 用户没有可用的订阅授权额度（订阅消息是一次授权一次下发）。 */
        NO_SUBSCRIPTION,
        /** 收件人账号已不可用（停用/注销）。 */
        RECEIVER_UNUSABLE,
    }

    /** 幂等键前缀。 */
    String NOTIFY_KEY_PREFIX = "WXN";
}

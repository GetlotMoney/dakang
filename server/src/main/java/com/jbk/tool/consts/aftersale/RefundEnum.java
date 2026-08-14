package com.jbk.tool.consts.aftersale;

import com.jbk.tool.exception.JbkException;

/**
 * 退款域枚举（E2E-04 包B）——退款来源/单状态/事实状态/处理状态的单一出处。
 * 与 {@link AfterSaleEnum} 分文件：售后=给用户补什么，退款=向支付机构要回多少，状态机与失败语义不同。
 *
 * @author dakang
 * @since 2026-07-29
 */
public interface RefundEnum {

    /** 退款来源(1373)：写入 {@code ws_refund.REFUND_SOURCE} 与 {@code ws_refund_event.REFUND_SOURCE}。 */
    enum Source {

        /** 微信支付退款。本期未接入，见 WechatRefundSourceAdapter。 */
        WECHAT(1, "微信"),
        /** Refund-Sim 模拟退款。展示层必须显示「Refund-Sim」而非「微信退款」（R0-8）。 */
        REFUND_SIM(2, "Refund-Sim");

        private final int value;
        private final String desc;

        Source(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        /** fail-closed：未登记来源直接拒绝，绝不回落到「按微信处理」。 */
        public static Source getByValue(Integer value) {
            if (value != null) {
                for (Source s : values()) {
                    if (s.value == value) {
                        return s;
                    }
                }
            }
            throw new JbkException("未登记的退款来源：" + value);
        }
    }

    /** 退款单状态(1343)：{@code ws_refund.REFUND_STATUS}。 */
    enum RefundStatus {

        /** 已受理，等待服务方事实。 */
        PROCESSING(1, "退款中"),
        /** 服务方确认退款成功。<b>终态</b>：不得被任何迟到的失败事实降级（R0-7）。 */
        SUCCESS(2, "退款成功"),
        /** 服务方明确失败/关闭。终态。 */
        FAILED(3, "退款失败"),
        /** 可重试的基础设施类失败，带 NEXT_RETRY_TIME。 */
        RETRY_WAIT(4, "待重试"),
        /** 账实不符或事实自相矛盾，停在这里等人工。 */
        RECONCILIATION_REQUIRED(5, "需人工对账");

        private final int value;
        private final String desc;

        RefundStatus(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static RefundStatus getByValue(Integer value) {
            if (value != null) {
                for (RefundStatus s : values()) {
                    if (s.value == value) {
                        return s;
                    }
                }
            }
            throw new JbkException("未登记的退款状态：" + value);
        }
    }

    /**
     * 退款事实渠道：{@code ws_refund_event.FACT_CHANNEL}。
     * 与 {@code ws_payment_event.FACT_CHANNEL} 同构：通知/查询/模拟三条来路必须可区分，
     * 否则「模拟产生的事实」与「真实通知」在幂等键里会互相顶掉。
     */
    interface FactChannel {
        /** 服务方主动通知。 */
        int NOTIFY = 1;
        /** 我方主动查单。 */
        int QUERY = 2;
        /** Refund-Sim 产生。 */
        int REFUND_SIM = 3;
    }

    /** 事实校验方式：{@code ws_refund_event.VERIFY_METHOD}。 */
    interface VerifyMethod {
        int WECHAT_SIGNATURE = 1;
        int WECHAT_QUERY = 2;
        int REFUND_SIM_HMAC = 3;
    }

    /**
     * 规范化退款事实状态：{@code ws_refund_event.REFUND_STATE}。
     * 字符串而非 tinyint：取值集合由支付机构决定，字符串可原样留痕后再判定，数字映射不上只能丢弃。
     */
    interface FactState {
        /** 退款成功。唯一能把退款单推到 SUCCESS 的事实状态。 */
        String SUCCESS = "SUCCESS";
        /** 退款处理中。 */
        String PROCESSING = "PROCESSING";
        /** 退款关闭/失败。 */
        String CLOSED = "CLOSED";
        /** 服务方明确报异常。 */
        String ABNORMAL = "ABNORMAL";
        /** 无法归类：一律转人工，绝不猜。 */
        String UNKNOWN = "UNKNOWN";
    }

    /**
     * 事实处理状态：{@code ws_refund_event.PROCESSING_STATUS}。取值与 {@code ws_payment_event} 逐一对齐，两套收件箱共用同一套 Worker 语义。
     */
    interface ProcessingStatus {
        int PENDING = 1;
        int PROCESSING = 2;
        int PROCESSED = 3;
        int RETRY_WAIT = 4;
        int RECONCILIATION_REQUIRED = 5;
    }
}

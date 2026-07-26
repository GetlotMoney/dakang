package com.jbk.tool.consts.trade;

/**
 * 交易域枚举（值对齐 ws_trade.sql 字典；DictType 1340/1341/1344/1346 已在 ApiEnum 注册，此处仅提供代码常量口径）。
 *
 * @author dakang
 * @since 2026-07-19
 */
public interface TradeEnum {

    /** 订单类型 (dictType=1340) */
    enum OrderType {
        WATER(1, "扫码取水"),
        CARD(2, "购卡充值"),
        DELIVERY(3, "水配送");

        private final int value;
        private final String desc;

        OrderType(int value, String desc) {
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

    /** 订单状态 (dictType=1341) */
    enum OrderStatus {
        UNPAID(1, "待支付"),
        PAID(2, "已支付"),
        DISPENSING(3, "出水中"),
        FINISHED(4, "已完成"),
        CANCELLED(5, "已取消"),
        ABNORMAL(6, "异常待补偿"),
        REFUNDED(7, "已退款"),
        PART_REFUNDED(8, "部分退款");

        private final int value;
        private final String desc;

        OrderStatus(int value, String desc) {
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

    /** 钱包流水类型 (dictType=1344) */
    enum FlowType {
        RECHARGE(1, "充值入账"),
        CONSUME(2, "取水扣减"),
        REFUND(3, "退款返还"),
        COMPENSATE(4, "补偿入账"),
        ADJUST(5, "后台调整"),
        EXPIRE_CLEAR(6, "过期清零"),
        // E2E-03：配送单余额扣减独立类型，与取水扣减分开对账口径；幂等键 DELIVERY:<orderNo>
        DELIVERY_CONSUME(7, "配送扣减");

        private final int value;
        private final String desc;

        FlowType(int value, String desc) {
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

    /** 支付方式 (dictType=1346) */
    enum PayWay {
        WECHAT(1, "微信支付"),
        CARD_BALANCE(2, "水卡余额"),
        CARD_ML(3, "水卡水量");

        private final int value;
        private final String desc;

        PayWay(int value, String desc) {
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

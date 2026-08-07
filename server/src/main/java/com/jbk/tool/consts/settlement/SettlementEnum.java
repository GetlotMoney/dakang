package com.jbk.tool.consts.settlement;

/**
 * 分账/收益域枚举（E2E-08 包B，值对齐字典 1376/1377/1345/1378）。
 *
 * @author dakang
 * @since 2026-07-31
 */
public interface SettlementEnum {

    /** 分账商品线 (dictType=1376) */
    enum ProductLine {
        WATER(1, "售水"),
        DELIVERY(2, "配送");

        private final int value;
        private final String desc;

        ProductLine(int value, String desc) {
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

    /** 分账收款方类型 (dictType=1377)；4/5/6 为甲方确认后启用的扩展位 */
    enum ReceiverType {
        OWNER(1, "机主"),
        COURIER(2, "配送员"),
        PLATFORM(3, "平台"),
        CHANNEL(4, "渠道(预留)"),
        REFERRER(5, "推荐人(预留)"),
        REGION(6, "区域服务商(预留)");

        private final int value;
        private final String desc;

        ReceiverType(int value, String desc) {
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

    /** 分账状态 (dictType=1345)：Pay-Sim 环境为账务计提语义，不动真实资金 */
    enum SplitStatus {
        PENDING(1, "待分账"),
        DONE(2, "已分账"),
        FAILED(3, "分账失败"),
        REVERSED(4, "已回退");

        private final int value;
        private final String desc;

        SplitStatus(int value, String desc) {
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

    /** 对账差异分类 (dictType=1379) */
    enum DiffType {
        ONE_SIDED(1, "单边账"),
        AMOUNT_MISMATCH(2, "金额不符"),
        STATE_MISMATCH(3, "状态不符"),
        LEDGER_BROKEN(4, "账本断裂");

        private final int value;
        private final String desc;

        DiffType(int value, String desc) {
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

    /** 收益流水类型 (dictType=1378) */
    enum IncomeFlowType {
        SPLIT_IN(1, "分润入账"),
        SPLIT_REVERSE(2, "分润回退(预留)"),
        WITHDRAW_FREEZE(3, "提现冻结"),
        WITHDRAW_DONE(4, "提现完成(预留)"),
        WITHDRAW_REJECT(5, "提现驳回解冻");

        private final int value;
        private final String desc;

        IncomeFlowType(int value, String desc) {
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

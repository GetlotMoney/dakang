package com.jbk.tool.consts.aftersale;

import com.jbk.tool.exception.JbkException;

/**
 * 售后域枚举（E2E-04 包A；字典 1370/1371/1372，对齐 2026-07-29-aftersale-e2e04-a.sql）。
 * 售后语义唯一出处，别处不得再写裸数字。所有解析入口 fail-closed：字典外脏值抛 {@link JbkException}，绝不静默降级。
 *
 * @author dakang
 * @since 2026-07-29
 */
public interface AfterSaleEnum {

    /**
     * 售后来源 (dictType=1370)。SOURCE_ID 语义随来源变化，见 {@link SourceType#sourceIdMeaning()}；
     * 陷阱：申诉必须取 appealId 而非 taskId，否则同任务的第二次合法申诉会被误判为重放。
     */
    enum SourceType {

        /** 待接单取消：用户/运营在配送员接单前撤单，原路返还已扣权益。SOURCE_ID = ws_order.ID */
        DELIVERY_CANCEL(1, "配送取消", "ws_order.ID"),

        /** 配送申诉裁决成立后的补偿。SOURCE_ID = ws_delivery_appeal.ID（不是 taskId） */
        DELIVERY_APPEAL(2, "配送申诉", "ws_delivery_appeal.ID"),

        /** 取水异常核账：设备侧实际出水与指令不符，只做核账确认，零资金写入。SOURCE_ID = ws_order.ID */
        WATER_ABNORMAL(3, "取水异常核账", "ws_order.ID"),

        /**
         * 充值/购卡退款（包D-5，REQ-061）：已入账的充值按权益批次折算后原路退款。SOURCE_ID = ws_order.ID。
         * 必须与 WATER_ABNORMAL 分开编号：uk_after_sale_source(SOURCE_TYPE, SOURCE_ID) 按二元组唯一，复用来源码会互相占键。
         */
        RECHARGE_REFUND(4, "充值退款", "ws_order.ID");

        private final int value;
        private final String desc;
        private final String sourceIdMeaning;

        SourceType(int value, String desc, String sourceIdMeaning) {
            this.value = value;
            this.desc = desc;
            this.sourceIdMeaning = sourceIdMeaning;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        /** SOURCE_ID 指向哪张表的主键；写入方与核验方共用这一句口径。 */
        public String sourceIdMeaning() {
            return sourceIdMeaning;
        }

        public static SourceType getByValue(Integer value) {
            if (value == null) {
                throw new JbkException("售后来源不能为空");
            }
            for (SourceType item : values()) {
                if (item.value == value) {
                    return item;
                }
            }
            throw new JbkException("售后来源不合法");
        }
    }

    /**
     * 售后动作类型 (dictType=1371)。1/2 是包A 内部权益返还；3 属包B、4 属包C，包A 只登记值不实现执行路径。
     */
    enum ActionType {

        /** 卡内退款：撤单/核账场景，把原扣款按原维度退回卡内 */
        CARD_REFUND(1, "卡内退款"),

        /** 卡内补偿：申诉成立场景，按批准数量补偿水品/配送费 */
        CARD_COMPENSATE(2, "卡内补偿"),

        /** 机构退款：走支付渠道原路退回（包B） */
        GATEWAY_REFUND(3, "机构退款"),

        /** 补送：生成补送子订单与任务，不动资金（包C） */
        RESEND(4, "补送");

        private final int value;
        private final String desc;

        ActionType(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static ActionType getByValue(Integer value) {
            if (value == null) {
                throw new JbkException("售后动作类型不能为空");
            }
            for (ActionType item : values()) {
                if (item.value == value) {
                    return item;
                }
            }
            throw new JbkException("售后动作类型不合法");
        }
    }

    /**
     * 售后执行状态 (dictType=1372)。合法迁移边唯一出处是 {@code com.jbk.serve.service.aftersale.AfterSaleTransitions}，本枚举只负责值与文案。
     */
    enum ActionStatus {

        /** 待执行：已入账为一笔待办，尚未认领 */
        PENDING(1, "待执行"),

        /** 执行中：已被 CAS 认领，资金写入正在同事务内推进 */
        PROCESSING(2, "执行中"),

        /** 已完成：终态，返还已落账且流水已生成 */
        SUCCESS(3, "已完成"),

        /** 可重试：基础设施类失败（锁等待/死锁）后的中间态，到点可再次认领 */
        RETRY_WAIT(4, "可重试"),

        /** 需人工对账：终态*，业务性失败或重试耗尽，钱未动但证据必须存活 */
        RECONCILIATION_REQUIRED(5, "需人工对账"),

        /** 已终止：终态，运营终止或人工对账收口（包B 实现写入路径） */
        TERMINATED(6, "已终止");

        private final int value;
        private final String desc;

        ActionStatus(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static ActionStatus getByValue(Integer value) {
            if (value == null) {
                throw new JbkException("售后执行状态不能为空");
            }
            for (ActionStatus item : values()) {
                if (item.value == value) {
                    return item;
                }
            }
            throw new JbkException("售后执行状态不合法");
        }
    }

    /**
     * 补偿策略码（ws_after_sale_action.STRATEGY_CODE，varchar(20)）。用字符串码因字典物理放不下语义码
     * （DICT_VALUE tinyint / DICT_LABEL varchar(10)），本枚举即字典，落库前必须过 {@link #getByCode(String)}。
     * 策略码只声明退哪几个维度，金额/水量由快照与数量边界算出。
     */
    enum StrategyCode {

        /** 只退水品：payWay=2 走 REFUND_PRODUCT_FEN，payWay=3 走 REFUND_PRODUCT_ML */
        PRODUCT_ONLY("PRODUCT_ONLY", "仅退水品"),

        /** 只退配送费：无论 payWay 恒走 REFUND_SERVICE_FEN（配送费始终以分计价） */
        SERVICE_FEE_ONLY("SERVICE_FEE_ONLY", "仅退配送费"),

        /** 水品 + 配送费同退：两个维度各自独立封顶，不合并成一个总额判定 */
        PRODUCT_AND_SERVICE("PRODUCT_AND_SERVICE", "水品与配送费同退"),

        /** 补送：不动资金，生成补送子订单（包C 实现） */
        RESEND("RESEND", "补送"),

        /** 驳回：申诉不成立，不产生任何返还动作 */
        REJECT("REJECT", "驳回");

        private final String code;
        private final String desc;

        StrategyCode(String code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public String getCode() {
            return code;
        }

        public String getDesc() {
            return desc;
        }

        /** fail-closed 解析：白名单外的码（含空串、大小写不符、历史脏值）一律拒绝，绝不当作 REJECT 兜底。 */
        public static StrategyCode getByCode(String code) {
            if (code == null || code.isBlank()) {
                throw new JbkException("补偿策略码不能为空");
            }
            for (StrategyCode item : values()) {
                if (item.code.equals(code)) {
                    return item;
                }
            }
            throw new JbkException("补偿策略码不合法");
        }
    }
}

package com.jbk.tool.consts.mall;

/**
 * 商城域枚举（E2E-09 S1）。契约：docs/contracts/E2E-09-mall-contract.md。
 *
 * @author dakang
 * @since 2026-08-08
 */
public interface MallEnum {

    /** 商城商品状态（字典 1388）。 */
    enum ProductStatus {
        DRAFT(1, "草稿"),
        PUBLISHED(2, "已上架"),
        UNPUBLISHED(3, "已下架");

        private final int value;
        private final String desc;

        ProductStatus(int value, String desc) {
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

    /** 商城 SKU 状态（字典 1389）。 */
    enum SkuStatus {
        ENABLED(1, "启用"),
        DISABLED(2, "停用");

        private final int value;
        private final String desc;

        SkuStatus(int value, String desc) {
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

    /** 前置仓状态（字典 1390）：停用仓不参与小程序可售聚合。 */
    enum WarehouseStatus {
        ENABLED(1, "启用"),
        DISABLED(2, "停用");

        private final int value;
        private final String desc;

        WarehouseStatus(int value, String desc) {
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

    /**
     * 商城库存流水类型（字典 1391）。1~4 为 PC 人工动作，5~8 为订单流转（S2 起启用）。
     * 人工端点只接受 {@link StockFlowType#isManual()} 为真的类型；5~11 只能由订单/售后链路内部写入，防绕过订单凭空造预占或实销。
     */
    enum StockFlowType {
        MANUAL_IN(1, "人工入库"),
        MANUAL_OUT(2, "人工出库"),
        COUNT_INCREASE(3, "盘点调增"),
        COUNT_DECREASE(4, "盘点调减"),
        ORDER_RESERVE(5, "下单预占"),
        ORDER_RELEASE(6, "预占释放"),
        ORDER_SELL(7, "支付实销"),
        RETURN_RESTOCK(8, "退货回库"),
        EXCHANGE_RESERVE(9, "换货预占"),
        EXCHANGE_OUT(10, "换货出库"),
        EXCHANGE_RELEASE(11, "换货释放");

        private final int value;
        private final String desc;

        StockFlowType(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static StockFlowType getByValue(Integer value) {
            for (StockFlowType type : values()) {
                if (value != null && value == type.value) {
                    return type;
                }
            }
            return null;
        }

        /** 人工动作（1~4）：只有这四种允许从 PC 人工端点发起。 */
        public boolean isManual() {
            return this == MANUAL_IN || this == MANUAL_OUT
                    || this == COUNT_INCREASE || this == COUNT_DECREASE;
        }

        /** 人工动作的增量方向：入库/盘点调增为正，出库/盘点调减为负（订单类型不适用）。 */
        public boolean isIncrease() {
            return this == MANUAL_IN || this == COUNT_INCREASE;
        }
    }

    /** 商城订单状态（字典 1393）。S2 只开放 1→2（支付事实处理成功）与 1→5（取消/权威关闭）。 */
    enum OrderStatus {
        PENDING_PAY(1, "待支付"),
        PAID(2, "已支付待履约"),
        FULFILLING(3, "履约中"),
        COMPLETED(4, "已完成"),
        CANCELLED(5, "已取消"),
        REFUNDED(6, "已全额退款");

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

        public static OrderStatus getByValue(Integer value) {
            for (OrderStatus status : values()) {
                if (value != null && value == status.value) {
                    return status;
                }
            }
            return null;
        }
    }

    /** 商城支付单状态（字典 1394）。 */
    enum PayStatus {
        PENDING(1, "待支付"),
        SUCCESS(2, "支付成功"),
        FAILED(3, "支付失败"),
        CLOSED(4, "已关闭");

        private final int value;
        private final String desc;

        PayStatus(int value, String desc) {
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

    /**
     * 商城支付事实处理状态（字典 1395）。NEED_RECONCILE(5) 是终点非中转：对不上停人工，绝不自行推进资金或库存。
     */
    enum PayFactStatus {
        PENDING(1, "待处理"),
        PROCESSING(2, "处理中"),
        PROCESSED(3, "已处理"),
        RETRY(4, "待重试"),
        NEED_RECONCILE(5, "需对账");

        private final int value;
        private final String desc;

        PayFactStatus(int value, String desc) {
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

    /** 支付来源：服务端适配器常量，不取自报文。 */
    enum PaySource {
        WECHAT(1, "微信支付"),
        PAY_SIM(2, "Pay-Sim");

        private final int value;
        private final String desc;

        PaySource(int value, String desc) {
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

    /** 支付事实渠道：三条命名空间不得复用同一事实键。 */
    enum FactChannel {
        NOTIFY(1, "支付通知"),
        QUERY(2, "主动查单"),
        PAY_SIM(3, "Pay-Sim");

        private final int value;
        private final String desc;

        FactChannel(int value, String desc) {
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

    /**
     * 规范化支付事实状态。只认这三种；其他一律留证转人工，不做语义猜测。
     */
    interface TradeState {
        String SUCCESS = "SUCCESS";
        String NOTPAY = "NOTPAY";
        String CLOSED = "CLOSED";

        /**
         * 白名单判据——唯一出处，事实准入与推进段共用。只认这三种，不按前缀/大小写/语义近似猜测；
         * 未知状态（如 REFUND、PARTIAL_REFUND）一律留证转人工，绝不标成已处理。
         */
        static boolean isKnown(String state) {
            return SUCCESS.equals(state) || NOTPAY.equals(state) || CLOSED.equals(state);
        }
    }

    /**
     * 商城售后状态 (dictType=1399)。顺序不可跳：1→2→3→(4|5)→6，任一环节可转 7驳回/8待人工；
     * 只有 1待审核 允许用户取消(9)。8待人工是终点：证据对不上停人工，绝不自行退钱或动库存。
     */
    enum AfterSaleStatus {
        PENDING_AUDIT(1, "待审核"),
        PENDING_RETURN(2, "待退货"),
        PENDING_INSPECT(3, "待质检"),
        REFUNDING(4, "退款处理中"),
        EXCHANGING(5, "换货补发中"),
        COMPLETED(6, "已完成"),
        REJECTED(7, "已驳回"),
        NEED_MANUAL(8, "待人工"),
        CANCELLED(9, "已取消");

        private final int value;
        private final String desc;

        AfterSaleStatus(int value, String desc) {
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

    /**
     * 商城售后类型 (dictType=1400)。本期只做这三种；跨SKU换货/补差价/仅退款不退货待甲方口径，不实现。
     */
    enum AfterSaleType {
        RETURN_REFUND(1, "退货退款"),
        EXCHANGE(2, "同SKU换货"),
        CANCEL_REFUND(3, "未拣货整单取消退款");

        private final int value;
        private final String desc;

        AfterSaleType(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        /** 白名单：未知值一律拒绝，绝不默认成第一项。 */
        public static boolean isKnown(Integer value) {
            for (AfterSaleType v : values()) {
                if (value != null && v.value == value) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 商城质检结论 (dictType=1401)。只有 1 才回库；2=钱退但货不可再卖，退款与库存必须分开记。
     */
    enum InspectResult {
        PASS_RESELLABLE(1, "通过可重新销售"),
        PASS_NOT_RESELLABLE(2, "通过不可重新销售"),
        REJECTED(3, "不通过");

        private final int value;
        private final String desc;

        InspectResult(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        /** 白名单：未知值一律拒绝，绝不默认成第一项。 */
        public static boolean isKnown(Integer value) {
            for (InspectResult v : values()) {
                if (value != null && v.value == value) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 商城退款单状态 (dictType=1402)。
     */
    enum RefundStatus {
        PENDING(1, "待退款"),
        SUCCESS(2, "退款成功"),
        FAILED(3, "退款失败"),
        CLOSED(4, "已关闭");

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
    }

    /**
     * 商城退款事实处理状态 (dictType=1403)：5 需对账是终点，停在这里等人工。
     */
    enum RefundFactStatus {
        PENDING(1, "待处理"),
        PROCESSING(2, "处理中"),
        PROCESSED(3, "已处理"),
        RETRY(4, "待重试"),
        NEED_RECONCILE(5, "需对账");

        private final int value;
        private final String desc;

        RefundFactStatus(int value, String desc) {
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

    /**
     * 退款事实渠道：三条命名空间不得复用同一事实键。
     */
    enum RefundFactChannel {
        NOTIFY(1, "退款通知"),
        QUERY(2, "主动查单"),
        REFUND_SIM(3, "Refund-Sim");

        private final int value;
        private final String desc;

        RefundFactChannel(int value, String desc) {
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

    /**
     * 规范化退款事实状态。只认这三种；其他一律留证转人工，不做语义猜测（同 {@link TradeState}）。
     */
    interface RefundState {
        String SUCCESS = "SUCCESS";
        String PROCESSING = "PROCESSING";
        String CLOSED = "CLOSED";

        static boolean isKnown(String state) {
            return SUCCESS.equals(state) || PROCESSING.equals(state) || CLOSED.equals(state);
        }
    }

    /**
     * 商城履约任务状态 (dictType=1396)——平台统一状态，渠道中立。顺序推进不可跳：1→2→3→4→5→6→7；
     * 前三态归前置仓，4~6 由承运方推进（自营=配送员动作，第三方=验签后的物流事件），7 恒由用户确认收货落定。
     * 刻意不与一期 {@code DeliveryEnum.TaskStatus} 共用值域，防售后按状态检索串链。
     */
    enum FulfillStatus {
        PENDING_PICK(1, "待拣货"),
        PENDING_PACK(2, "待打包"),
        PENDING_ASSIGN(3, "待安排发运"),
        PENDING_FETCH(4, "待承运方揽收"),
        DELIVERING(5, "运输中"),
        ARRIVED(6, "已送达待确认"),
        SIGNED(7, "已签收");

        private final int value;
        private final String desc;

        FulfillStatus(int value, String desc) {
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

    /** 履约轨迹操作方 (dictType=1397)。 */
    enum ActorType {
        SYSTEM(1, "系统"),
        WAREHOUSE(2, "前置仓"),
        COURIER(3, "配送员"),
        USER(4, "用户");

        private final int value;
        private final String desc;

        ActorType(int value, String desc) {
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

    /** 签收方式 (dictType=1398)。 */
    enum SignMethod {
        SELF(1, "本人签收"),
        PROXY(2, "他人代收");

        private final int value;
        private final String desc;

        SignMethod(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        /** 白名单：只认这两种，其他一律拒绝而不是默认成本人签收。 */
        public static boolean isKnown(Integer value) {
            return SELF.value == (value == null ? -1 : value)
                    || PROXY.value == (value == null ? -1 : value);
        }
    }

    /** 事实校验方式：Pay-Sim 走内部校验，微信侧留位。 */
    interface VerifyMethod {
        int WECHAT_SIGNATURE = 1;
        int WECHAT_QUERY = 2;
        int PAY_SIM_INTERNAL = 3;
    }

    /** 商城分类状态（字典 1392）：停用分类下的商品不得新上架。 */
    enum CategoryStatus {
        ENABLED(1, "启用"),
        DISABLED(2, "停用");

        private final int value;
        private final String desc;

        CategoryStatus(int value, String desc) {
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

    /**
     * 商城履约渠道 (dictType=1404)——L1 多渠道物流。0 是未确定而非默认自营。
     * 渠道由「分配自营配送员」或「创建第三方运单」CAS 0→1 / 0→2 冻结，并发只能一方成功，冻结后不可改。
     */
    enum FulfillMode {
        UNDECIDED(0, "未确定"),
        SELF_DELIVERY(1, "自营配送"),
        THIRD_PARTY(2, "第三方物流");

        private final int value;
        private final String desc;

        FulfillMode(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static boolean isKnown(Integer value) {
            if (value == null) {
                return false;
            }
            for (FulfillMode item : values()) {
                if (item.value == value) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 出库包裹方向 (dictType=1405)。退货(2)本轮只建模不实现（取退件规则未决策），先建模是为免日后改已上线唯一键。
     */
    enum ShipmentDirection {
        FORWARD(1, "正向发货"),
        RETURN(2, "退货"),
        EXCHANGE(3, "换货补发");

        private final int value;
        private final String desc;

        ShipmentDirection(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static boolean isKnown(Integer value) {
            if (value == null) {
                return false;
            }
            for (ShipmentDirection item : values()) {
                if (item.value == value) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 出库包裹状态 (dictType=1406)——渠道中立，自营与第三方共用一套（自营「已受理」=分配到配送员，第三方=取得运单号）。
     */
    enum ShipmentStatus {
        PENDING(1, "待发运"),
        ACCEPTED(2, "已受理"),
        PICKED_UP(3, "已揽收"),
        IN_TRANSIT(4, "运输中"),
        DELIVERED(5, "已送达"),
        SIGNED(6, "已签收"),
        CANCELLED(7, "已取消"),
        NEED_MANUAL(8, "异常待人工");

        private final int value;
        private final String desc;

        ShipmentStatus(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static boolean isKnown(Integer value) {
            if (value == null) {
                return false;
            }
            for (ShipmentStatus item : values()) {
                if (item.value == value) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 承运方物流事件状态 (dictType=1407)，字符串白名单。未知事件状态一律留证转人工（同 {@code TradeState}）。
     */
    interface LogisticsEventState {
        String CREATED = "CREATED";
        String PICKED_UP = "PICKED_UP";
        String IN_TRANSIT = "IN_TRANSIT";
        String DELIVERED = "DELIVERED";
        String SIGNED = "SIGNED";
        String EXCEPTION = "EXCEPTION";
        String CANCELLED = "CANCELLED";

        static boolean isKnown(String state) {
            return CREATED.equals(state) || PICKED_UP.equals(state) || IN_TRANSIT.equals(state)
                    || DELIVERED.equals(state) || SIGNED.equals(state)
                    || EXCEPTION.equals(state) || CANCELLED.equals(state);
        }
    }

    /**
     * 物流处理状态 (dictType=1408)：事件收件箱与 outbox 共用同一值域。
     */
    enum LogisticsProcessing {
        PENDING(1, "待处理"),
        PROCESSING(2, "处理中"),
        PROCESSED(3, "已处理"),
        RETRY(4, "待重试"),
        NEED_MANUAL(5, "需人工");

        private final int value;
        private final String desc;

        LogisticsProcessing(int value, String desc) {
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

    /**
     * 物流 outbox 动作类型 (dictType=1409)。外呼不进数据库事务：事务只写 outbox，Worker 负责调用。
     */
    enum LogisticsAction {
        CREATE_ORDER(1, "创建运单"),
        CANCEL_ORDER(2, "取消运单");

        private final int value;
        private final String desc;

        LogisticsAction(int value, String desc) {
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

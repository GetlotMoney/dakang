package com.jbk.serve.service.aftersale;

import com.jbk.serve.service.delivery.DeliveryRefundSnapshot;
import com.jbk.tool.consts.aftersale.AfterSaleEnum;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.exception.JbkException;

/**
 * 售后补偿策略的唯一出处（R0-2）：策略码 → 允许的返还维度 + 数量边界 + 金额/水量计算 + 申诉终态。
 *
 * <p>入参只有 strategyCode 一个真相源：终态由 {@link #deriveOutcome} 派生，调用方不另传 outcome。
 * 禁止 import {@code DeliveryPricing}（评审门 grep 卡死）：历史单价只从
 * {@link DeliveryRefundSnapshot} 取，用当前价目表重算历史单会与原扣款流水对不上。
 * R0-1 维度不折算：结果是三元 {@link Refund}（水品分/配送费分/水品毫升），不是混合总额。
 * 纯函数、无注入，全程 checked arithmetic。</p>
 */
public final class AfterSaleStrategy {

    /** 申诉原因码：数量不符。上界取「计划数量 − 实收数量」——少送了几桶就只能赔几桶。 */
    private static final String REASON_QUANTITY = "QUANTITY";
    /** 申诉原因码：水质问题。上界取实际签收数量——没签收的桶谈不上水质问题。 */
    private static final String REASON_QUALITY = "QUALITY";
    /** 申诉原因码：货品破损。上界同 QUALITY。 */
    private static final String REASON_DAMAGE = "DAMAGE";
    /** 申诉原因码：摆放问题。服务瑕疵而非货品瑕疵，不构成资金补偿理由。 */
    private static final String REASON_PLACEMENT = "PLACEMENT";
    /** 申诉原因码：其他。语义未定义，不允许自动资金补偿。 */
    private static final String REASON_OTHER = "OTHER";

    private AfterSaleStrategy() {
    }

    /**
     * 一次裁决产生的返还额度，按「水品 / 配送费」两条独立维度分列。
     * 三列必须分开落库：只记混合总额会让连续多次 SERVICE_FEE_ONLY 把水费额度挪去退配送费。
     *
     * @param productFen 水品权益返还金额（分），payWay=2 专用
     * @param serviceFen 配送费返还金额（分），两种支付方式通用
     * @param productMl  水品权益返还水量（毫升），payWay=3 专用
     */
    public record Refund(long productFen, long serviceFen, long productMl) {

        public static final Refund NONE = new Refund(0L, 0L, 0L);

        /**
         * 落 {@code REFUND_AMOUNT} 用的金额合计，只供卡余额 CAS 与流水使用；
         * 不参与封顶判定——封顶必须按两维分别判。
         */
        public long totalFen() {
            return Math.addExact(productFen, serviceFen);
        }

        /** 是否为零返还（RESEND/REJECT）。用于调用方判断要不要走资金链路。 */
        public boolean isZero() {
            return productFen == 0L && serviceFen == 0L && productMl == 0L;
        }
    }

    /** 策略码白名单的统一入口：裁决、PC 试算、执行三处不得各自 valueOf。 */
    public static AfterSaleEnum.StrategyCode requireStrategy(String strategyCode) {
        return AfterSaleEnum.StrategyCode.getByCode(strategyCode);
    }

    /**
     * 策略码 → 申诉终态：REJECT→3 驳回；RESEND→5 补送待执行；三个资金策略→2 待补偿。
     * 正向枚举而非 default 兜底：新增策略码会立刻暴露「没登记终态」而非静默落进既有分支。
     */
    public static DeliveryEnum.AppealStatus deriveOutcome(AfterSaleEnum.StrategyCode strategy) {
        requireNonNull(strategy);
        return switch (strategy) {
            case REJECT -> DeliveryEnum.AppealStatus.REJECTED;
            case RESEND -> DeliveryEnum.AppealStatus.RESEND_PENDING;
            case PRODUCT_ONLY, SERVICE_FEE_ONLY, PRODUCT_AND_SERVICE ->
                    DeliveryEnum.AppealStatus.COMPENSATE_PENDING;
        };
    }

    /** 该策略是否触发资金/水量返还。RESEND 走履约、REJECT 零写入，都不消耗额度。 */
    public static boolean refundsAssets(AfterSaleEnum.StrategyCode strategy) {
        requireNonNull(strategy);
        return strategy == AfterSaleEnum.StrategyCode.PRODUCT_ONLY
                || strategy == AfterSaleEnum.StrategyCode.SERVICE_FEE_ONLY
                || strategy == AfterSaleEnum.StrategyCode.PRODUCT_AND_SERVICE;
    }

    /**
     * R0-2 数量边界的唯一判定（必须在任何 CAS 之前调用，越界拒绝时本事务零写入）。
     * 不 clamp：截到上界等于替运营改裁决内容；计数 null 一律拒绝——RECEIVED_COUNT 缺失按 0
     * 会把 QUANTITY 上界放大成计划全额。
     *
     * @param strategy            已过白名单的策略码
     * @param appealReason        申诉原因码（{@link DeliveryEnum#APPEAL_REASON_CODES}）
     * @param approvedCount       运营批准的受影响桶数；REJECT 时忽略
     * @param receivedCount       申诉声明的实收桶数（{@code ws_delivery_appeal.RECEIVED_COUNT}）
     * @param deliveryCount       任务计划桶数（{@code ws_delivery_task.DELIVERY_COUNT}）
     * @param actualDeliveryCount 任务实际签收桶数（{@code ws_delivery_task.ACTUAL_DELIVERY_COUNT}）
     * @return 校验通过的批准数量；REJECT 恒为 0
     */
    public static int requireApprovedCount(AfterSaleEnum.StrategyCode strategy, String appealReason,
                                           Integer approvedCount, Integer receivedCount,
                                           Integer deliveryCount, Integer actualDeliveryCount) {
        requireNonNull(strategy);
        String reason = requireReason(appealReason);
        requireStrategyAllowedFor(strategy, reason);
        if (strategy == AfterSaleEnum.StrategyCode.REJECT) {
            // 驳回不产生执行动作，提前返回免得为驳回凑齐计数字段
            return 0;
        }
        int max = maxApprovedCount(reason, receivedCount, deliveryCount, actualDeliveryCount);
        if (max < 1) {
            throw new JbkException("按申诉原因" + reason + "可赔付数量为 0，不能补偿或补送");
        }
        if (approvedCount == null || approvedCount < 1 || approvedCount > max) {
            throw new JbkException("批准数量必须在 1 到 " + max + " 之间，实际 " + approvedCount);
        }
        return approvedCount;
    }

    /**
     * 各原因码的数量上界（PC 试算与裁决共用同一份，防止两侧上界漂移）。
     * QUALITY/DAMAGE 取申诉声明与签收登记两个独立数据源的较小值；两者自相矛盾时不做取舍，
     * 直接拒绝交人工核实。
     */
    public static int maxApprovedCount(String appealReason, Integer receivedCount,
                                       Integer deliveryCount, Integer actualDeliveryCount) {
        String reason = requireReason(appealReason);
        return switch (reason) {
            case REASON_QUANTITY -> {
                int planned = requireCount(deliveryCount, "任务计划配送数量");
                int received = requireCount(receivedCount, "申诉实收数量");
                if (received > planned) {
                    throw new JbkException("申诉实收数量与任务数据自相矛盾，需人工核实后修正");
                }
                yield planned - received;
            }
            case REASON_QUALITY, REASON_DAMAGE -> {
                int received = requireCount(receivedCount, "申诉实收数量");
                int actual = requireCount(actualDeliveryCount, "任务实际签收数量");
                if (received > actual) {
                    throw new JbkException("申诉实收数量与任务数据自相矛盾，需人工核实后修正");
                }
                yield Math.min(received, actual);
            }
            // PLACEMENT/OTHER 只走 RESEND（REJECT 已在上游提前返回），上界即实际签收数量
            case REASON_PLACEMENT, REASON_OTHER -> requireCount(actualDeliveryCount, "任务实际签收数量");
            default -> throw new JbkException("申诉原因码不支持：" + reason);
        };
    }

    /**
     * 原因码 × 策略码白名单：PLACEMENT 是服务瑕疵、无对应扣款事实可退，OTHER 语义未定义，
     * 两者均拒绝自动资金补偿，只留补送与驳回。
     */
    private static void requireStrategyAllowedFor(AfterSaleEnum.StrategyCode strategy, String reason) {
        if (!refundsAssets(strategy)) {
            // RESEND / REJECT 对所有原因码都合法
            return;
        }
        if (REASON_PLACEMENT.equals(reason) || REASON_OTHER.equals(reason)) {
            throw new JbkException("申诉原因" + reason + "不支持资金补偿，只能补送或驳回");
        }
    }

    /**
     * 整单满额返还（待接单取消专用）：直接取额度锚点的三个上限值，与 {@link AfterSaleQuota.Caps}
     * 共用同一份维度拆分（铁律⑤：不另写 payWay 分支）。结果仍须过
     * {@link AfterSaleQuota#requireWithinCap}——此前已有成功返还时，满额再退即超额。
     */
    public static Refund fullRefund(AfterSaleQuota.Caps caps) {
        if (caps == null) {
            throw new JbkException("额度上限缺失，无法计算整单返还");
        }
        // 保留配送费维：待接单取消服务未发生、原样全退；D-414「配送费不退」只限履约后裁决（见 compute）
        return new Refund(caps.capProductFen(), caps.capServiceFen(), caps.capProductMl());
    }

    /**
     * 策略码 × payWay → 三元返还额度。全部从订单快照派生，一次都不碰当前价目表。
     *
     * <p>矩阵（q = 批准数量，uP = 快照单桶水费，uF = 快照单桶配送费，uMl = 快照单桶水量）：
     * payWay=2：PRODUCT_ONLY → productFen = uP×q；SERVICE_FEE_ONLY → serviceFen = uF×q；
     * PRODUCT_AND_SERVICE → 两者都返。payWay=3：PRODUCT_ONLY → productMl = uMl×q
     * （<b>水量退水量</b>，R0-1 禁止折现）；SERVICE_FEE_ONLY → serviceFen = uF×q
     * （配送费在 payWay=3 下同样是从<b>余额</b>扣的，R0-1 禁止折成水量）；
     * PRODUCT_AND_SERVICE → 两者都返，是唯一的跨维返还场景。</p>
     *
     * <p>D-414 之后含配送费维的两个策略（SERVICE_FEE_ONLY / PRODUCT_AND_SERVICE）已在本方法入口直接拒绝，
     * 上述配送费列只对历史已登记动作与 {@link #fullRefund} 仍然有效。</p>
     */
    public static Refund compute(AfterSaleEnum.StrategyCode strategy, int approvedCount,
                                 DeliveryRefundSnapshot.Parsed snap) {
        requireNonNull(strategy);
        if (snap == null) {
            throw new JbkException("配送订单快照缺失，无法计算返还额度");
        }
        if (strategy == AfterSaleEnum.StrategyCode.REJECT) {
            // 驳回不建执行行；返回"零返还"合法对象会让驳回也留下一条售后动作，宁可炸
            throw new JbkException("驳回不产生售后执行动作，不应计算返还额度");
        }
        if (strategy == AfterSaleEnum.StrategyCode.RESEND) {
            return Refund.NONE;
        }
        // D-414（2026-08-06 甲方确认）：已履约售后配送费不退，只退水费；拦在新裁决入口而非
        // 静默清零 serviceFen。待接单取消走 fullRefund 不经此分支；历史已登记动作读额度列不重算，不受影响。
        if (strategy == AfterSaleEnum.StrategyCode.SERVICE_FEE_ONLY
                || strategy == AfterSaleEnum.StrategyCode.PRODUCT_AND_SERVICE) {
            throw new JbkException("配送费不予退还，请改选仅退水品或补送");
        }
        if (approvedCount < 1) {
            throw new JbkException("批准数量必须为正，实际 " + approvedCount);
        }
        // 交叉核对：上界来自 ws_delivery_task，再对一次 ws_order 快照——不一致说明有一侧被改写，
        // 按哪侧算都可能超额返还
        if (approvedCount > snap.deliveryCount()) {
            throw new JbkException("批准数量(" + approvedCount + ")超过订单快照配送数量("
                    + snap.deliveryCount() + ")，拒绝返还");
        }
        boolean refundProduct = strategy == AfterSaleEnum.StrategyCode.PRODUCT_ONLY
                || strategy == AfterSaleEnum.StrategyCode.PRODUCT_AND_SERVICE;
        boolean refundService = strategy == AfterSaleEnum.StrategyCode.SERVICE_FEE_ONLY
                || strategy == AfterSaleEnum.StrategyCode.PRODUCT_AND_SERVICE;
        try {
            long productFen = 0L;
            long productMl = 0L;
            if (refundProduct) {
                if (snap.payByMl()) {
                    productMl = Math.multiplyExact(snap.unitWaterMl(), (long) approvedCount);
                } else {
                    productFen = Math.multiplyExact(snap.unitWaterPriceFen(), (long) approvedCount);
                }
            }
            long serviceFen = refundService
                    ? Math.multiplyExact(snap.deliveryFeePerContainerFen(), (long) approvedCount)
                    : 0L;
            return new Refund(productFen, serviceFen, productMl);
        } catch (ArithmeticException overflow) {
            // 溢出即拒绝：回绕出来的小额返还比拒绝危险得多
            throw new JbkException("售后返还额度计算溢出，拒绝返还");
        }
    }

    private static String requireReason(String appealReason) {
        String reason = appealReason == null ? null : appealReason.trim();
        if (reason == null || !DeliveryEnum.APPEAL_REASON_CODES.contains(reason)) {
            throw new JbkException("申诉原因码不合法：" + appealReason);
        }
        return reason;
    }

    private static int requireCount(Integer count, String label) {
        if (count == null || count < 0) {
            throw new JbkException(label + "缺失或非法，无法核定赔付数量");
        }
        return count;
    }

    private static void requireNonNull(AfterSaleEnum.StrategyCode strategy) {
        if (strategy == null) {
            throw new JbkException("补偿策略码缺失");
        }
    }
}

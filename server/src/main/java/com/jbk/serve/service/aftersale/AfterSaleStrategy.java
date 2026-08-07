package com.jbk.serve.service.aftersale;

import com.jbk.serve.service.delivery.DeliveryRefundSnapshot;
import com.jbk.tool.consts.aftersale.AfterSaleEnum;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.exception.JbkException;

/**
 * 售后补偿策略的唯一出处（R0-2）：策略码 → 允许的返还维度 + 数量边界 + 金额/水量计算 + 申诉终态。
 *
 * <p><b>入参只有 strategyCode 一个真相源</b>：裁决终态（驳回/待补偿/补送待执行）由
 * {@link #deriveOutcome} 从策略码派生，不再由调用方另传 outcome——两个入参就会有两份真相，
 * 且必然出现「策略码说补偿、outcome 说驳回」这种自相矛盾却双双通过校验的裁决。</p>
 *
 * <p><b>禁止 import {@code DeliveryPricing}</b>（评审门 grep 卡死）：那是<b>当前</b>价目表，
 * 用它重算历史单会与原扣款流水对不上，而封顶基准正是原扣款流水。历史单价只从
 * {@link DeliveryRefundSnapshot} 取——下单事务内冻结的快照是历史价格的唯一真相。</p>
 *
 * <p><b>R0-1 维度不折算</b>：payWay=3 的水品权益是水量、配送费是余额，两者物理隔离；
 * 任何「水量折现金」或「配送费折水量」都会把一个维度的额度挪去填另一个维度的窟窿。
 * 因此计算结果是三元 {@link Refund}（水品分 / 配送费分 / 水品毫升），而不是一个混合总额。</p>
 *
 * <p>纯函数，无状态、无注入；金额与水量运算全程 checked arithmetic，溢出即拒绝而非回绕。</p>
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
     *
     * <p>三列必须分开落库（{@code REFUND_PRODUCT_FEN}/{@code REFUND_SERVICE_FEN}/{@code REFUND_PRODUCT_ML}）：
     * payWay=2 时水费与配送费<b>都从余额扣</b>，只记一个混合总额的话，连续多次 SERVICE_FEE_ONLY
     * 申诉会各自按总额判额度，把水费额度挪去退配送费——本单实际只扣过 N 分配送费却能退出 3N。</p>
     *
     * @param productFen 水品权益返还金额（分），payWay=2 专用
     * @param serviceFen 配送费返还金额（分），两种支付方式通用
     * @param productMl  水品权益返还水量（毫升），payWay=3 专用
     */
    public record Refund(long productFen, long serviceFen, long productMl) {

        public static final Refund NONE = new Refund(0L, 0L, 0L);

        /**
         * 落 {@code REFUND_AMOUNT} 用的金额合计。它只供卡余额 CAS 与流水使用，
         * <b>不参与封顶判定</b>——封顶必须按 productFen / serviceFen 两维分别判。
         */
        public long totalFen() {
            return Math.addExact(productFen, serviceFen);
        }

        /** 是否为零返还（RESEND/REJECT）。用于调用方判断要不要走资金链路。 */
        public boolean isZero() {
            return productFen == 0L && serviceFen == 0L && productMl == 0L;
        }
    }

    /**
     * 策略码白名单入口。白名单本身在 {@link AfterSaleEnum.StrategyCode}，此处只做统一入口，
     * 让所有调用方经过同一道门（裁决、PC 试算、执行三处不得各自 valueOf）。
     */
    public static AfterSaleEnum.StrategyCode requireStrategy(String strategyCode) {
        return AfterSaleEnum.StrategyCode.getByCode(strategyCode);
    }

    /**
     * 策略码 → 申诉终态。这是「裁决入参只有 strategyCode 一个真相源」的落地：
     * REJECT→3 不成立驳回；RESEND→5 补送待执行；三个资金策略→2 成立待补偿。
     *
     * <p>用正向枚举而非 {@code default} 兜底：将来新增第六个策略码时，这里会编译期/运行期
     * 立刻暴露「新码没登记终态」，而不是静默落进某个既有分支。</p>
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
     *
     * <p><b>为什么不 clamp</b>：把超界数量悄悄截到上界，等于替运营改了裁决内容，
     * 事后台账上看到的批准数量与运营实际点的不是一回事。越界一律拒绝并留独立证据。</p>
     *
     * <p><b>为什么 null 一律拒绝而不是 {@code defaultIfNull(x, 0)}</b>：
     * {@code RECEIVED_COUNT} 缺失时按 0 处理会让 QUANTITY 的上界变成「计划全额」——
     * 一条数据缺失就能把部分赔付放大成全单赔付。缺证据就不裁决。</p>
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
            // 驳回不产生任何执行动作，数量无意义；此处提前返回，避免为了驳回还得凑齐计数字段
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
     * 各原因码的数量上界（PC 试算与裁决共用同一份，避免前端展示的上界与服务端判定的上界漂移）。
     *
     * <p>「实收」有两个互相独立、可任意背离的数据源：申诉人声明的 {@code RECEIVED_COUNT}
     * 与配送员签收登记的 {@code ACTUAL_DELIVERY_COUNT}。QUALITY/DAMAGE 取两者较小值——
     * 只有「双方都承认送到了的桶」才谈得上质量或破损。两者自相矛盾（声明实收多于计划、
     * 或多于实际签收）时不做取舍，直接拒绝裁决交人工核实。</p>
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
     * 原因码 × 策略码白名单。
     *
     * <p>PLACEMENT（摆放问题）是服务瑕疵，货与配送都已履约，赔钱没有对应的扣款事实可退，
     * 只能补送或驳回；OTHER 语义未定义，包A 没有人工审批流，「进人工」在此的落地形式
     * 就是<b>拒绝一切自动资金补偿</b>，只保留补送与驳回两条不动钱的出口。</p>
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
     * 策略码 × payWay → 三元返还额度。全部从订单快照派生，一次都不碰当前价目表。
     *
     * <p>矩阵（q = 批准数量，uP = 快照单桶水费，uF = 快照单桶配送费，uMl = 快照单桶水量）：</p>
     * <ul>
     *   <li>payWay=2：PRODUCT_ONLY → productFen = uP×q；SERVICE_FEE_ONLY → serviceFen = uF×q；
     *       PRODUCT_AND_SERVICE → 两者都返。</li>
     *   <li>payWay=3：PRODUCT_ONLY → productMl = uMl×q（<b>水量退水量</b>，R0-1 禁止折现）；
     *       SERVICE_FEE_ONLY → serviceFen = uF×q（配送费在 payWay=3 下同样是从<b>余额</b>扣的，
     *       R0-1 禁止折成水量）；PRODUCT_AND_SERVICE → 两者都返，是唯一的跨维返还场景。</li>
     * </ul>
     */
    /**
     * 整单满额返还（待接单取消专用）：直接取额度锚点的三个上限值。
     *
     * <p>取消是「订单从未履约、原样退回」，返还额度恒等于封顶上限，因此与 {@link AfterSaleQuota.Caps}
     * <b>共用同一份维度拆分</b>，而不是在取消事务里另写一遍 payWay 分支 —— 那会让「怎么把一笔钱拆成
     * 水品/配送费/水量三列」出现第二份实现，两份从落地第一天起就可能漂移（铁律⑤）。</p>
     *
     * <p>调用方拿到的 Refund 仍要过 {@link AfterSaleQuota#requireWithinCap}：取消虽然理论上等于满额，
     * 但若该订单此前已产生过成功的售后返还（例如先申诉补偿、后又走到取消），已用额度非零，
     * 满额再退就是超额。让它照常走封顶判定，而不是因为「取消=满额」就跳过。</p>
     */
    public static Refund fullRefund(AfterSaleQuota.Caps caps) {
        if (caps == null) {
            throw new JbkException("额度上限缺失，无法计算整单返还");
        }
        // 刻意保留配送费维：本方法只服务「待接单取消」——配送员尚未接单、服务未发生，
        // 原样全退（含配送费）。D-414 的「配送费不退」只限履约后的售后裁决（见 compute）。
        return new Refund(caps.capProductFen(), caps.capServiceFen(), caps.capProductMl());
    }

    public static Refund compute(AfterSaleEnum.StrategyCode strategy, int approvedCount,
                                 DeliveryRefundSnapshot.Parsed snap) {
        requireNonNull(strategy);
        if (snap == null) {
            throw new JbkException("配送订单快照缺失，无法计算返还额度");
        }
        if (strategy == AfterSaleEnum.StrategyCode.REJECT) {
            // 驳回根本不建执行行；走到这里说明调用方漏了正向白名单，宁可炸也不能返回一个"零返还"
            // 的合法对象——那会让驳回也留下一条售后动作
            throw new JbkException("驳回不产生售后执行动作，不应计算返还额度");
        }
        if (strategy == AfterSaleEnum.StrategyCode.RESEND) {
            return Refund.NONE;
        }
        // D-414（2026-08-06 甲方确认）：配送服务已履约的售后，配送费不予退还，只退水费。
        // 拦在**新裁决**入口而非静默把 serviceFen 清零——运营选了「仅退配送费」却退 0 元，
        // 只会以为系统坏了；明确拒绝让运营改选仅退水品或补送。
        // 边界①：待接单取消走 fullRefund，不经本分支——履约前服务未发生，原样全退合理。
        // 边界②：历史已登记动作的执行链读动作行额度列、不重算策略，不受本拦截影响，
        //         已批准的旧动作仍可执行完毕。
        if (strategy == AfterSaleEnum.StrategyCode.SERVICE_FEE_ONLY
                || strategy == AfterSaleEnum.StrategyCode.PRODUCT_AND_SERVICE) {
            throw new JbkException("配送费不予退还，请改选仅退水品或补送");
        }
        if (approvedCount < 1) {
            throw new JbkException("批准数量必须为正，实际 " + approvedCount);
        }
        // 与快照的独立交叉核对：批准数量的上界来自 ws_delivery_task，这里再对一次 ws_order 快照。
        // 两张表本该一致，不一致说明有一侧被改写过，此时按哪一侧算都可能超额返还
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

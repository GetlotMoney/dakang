package com.jbk.serve.service.aftersale.refund;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jbk.tool.consts.aftersale.AfterSaleEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.exception.JbkException;

import java.util.List;

/**
 * 外部退款准入判定——<b>单一出处</b>（E2E-04 包B）。
 *
 * <p>任务书口径：未入账路径只放行「payment=2、order=6、无 {@code RECHARGE:<orderNo>} 入账流水、
 * 未因此建卡」的全额退款——四道闸合起来表达「这笔钱收了，但权益一点没发」，
 * 任一条不满足即 fail-closed（权益已发的退款须走权益批次冲正，否则用户既拿水又拿钱）。
 * 流水与建卡两侧都查：只查其一，任一侧被改写/补录就能骗过判定。</p>
 *
 * <p>本类只判定、不写库、不持有 Mapper：证据由调用方查好传进来，判定可被纯单测穷举，
 * 调用方无法「顺手补一条查询」把口径改宽。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public final class RefundEligibility {

    /** 充值退款动作在计算快照中冻结的路径字段。订单后续转 7/8 后仍靠它识别原准入路径。 */
    public static final String REFUND_PATH_FIELD = "refundPath";

    /** 两条来源相同、资金前提相反的充值退款路径。 */
    public enum RefundPath {
        /** payment=2/order=6 且从未入账、从未发卡，全额原路退款。 */
        UNSETTLED_FULL,
        /** order=4 且存在被本动作锁定的权益批次，按批次折算退款。 */
        ENTITLEMENT
    }

    private RefundEligibility() {
    }

    /**
     * 外部退款准入的证据集合。四项全部由调用方在<b>同一事务、锁定原单之后</b>查出。
     *
     * @param order            订单行
     * @param payment          支付单行
     * @param rechargeFlowSeen 是否存在 {@code RECHARGE:<orderNo>} 入账流水
     * @param issuedCardSeen   是否存在 {@code ISSUE_ORDER_ID = order.ID} 的水卡
     */
    public record Evidence(WsOrder order, WsPayment payment, boolean rechargeFlowSeen, boolean issuedCardSeen) {
    }

    /**
     * 断言这笔订单允许走「已付款未入账」全额外部退款，不允许则抛出可读错因。
     *
     * @return 允许退款的金额（分）：恒等于原支付金额，本路径只支持全额
     */
    public static long requireUnsettledFullRefund(Evidence evidence) {
        if (evidence == null || ObjectUtil.isNull(evidence.order()) || ObjectUtil.isNull(evidence.payment())) {
            throw new JbkException("退款准入证据缺失，拒绝退款");
        }
        WsOrder order = evidence.order();
        WsPayment payment = evidence.payment();

        // 主体与通道共键先于业务判定：任一项不一致，后续状态与金额不再具有同一业务含义
        if (ObjectUtil.notEqual(order.getDataStatus(), 0) || ObjectUtil.notEqual(payment.getDataStatus(), 0)) {
            throw new JbkException("订单或支付单已删除，拒绝退款");
        }
        if (ObjectUtil.notEqual(order.getOrderType(), TradeEnum.OrderType.CARD.getValue())) {
            throw new JbkException("只有购卡/充值订单可以走已付款未入账退款");
        }
        if (ObjectUtil.notEqual(order.getPayWay(), TradeEnum.PayWay.WECHAT.getValue())) {
            throw new JbkException("订单不是支付机构支付方式，拒绝原路退款");
        }
        if (ObjectUtil.notEqual(payment.getOrderId(), order.getId())) {
            throw new JbkException("支付单与订单归属错位，拒绝退款");
        }
        if (StrUtil.isBlank(payment.getOrderNo()) || StrUtil.isBlank(order.getOrderNo())
                || !StrUtil.equals(payment.getOrderNo(), order.getOrderNo())) {
            throw new JbkException("支付单与订单的商户单号不一致，拒绝退款");
        }
        if (!"CNY".equals(payment.getCurrency())) {
            throw new JbkException("原支付币种不是 CNY，拒绝退款");
        }

        // 闸① 钱确实收到了
        if (ObjectUtil.notEqual(payment.getPayStatus(), PAY_STATUS_SUCCESS)) {
            throw new JbkException("原支付单不是支付成功状态（实际 " + payment.getPayStatus() + "），拒绝退款");
        }
        // 闸② 只认「异常待补偿」这一种形状
        if (ObjectUtil.notEqual(order.getOrderStatus(), TradeEnum.OrderStatus.ABNORMAL.getValue())) {
            throw new JbkException("订单不是异常待补偿状态（实际 " + order.getOrderStatus()
                    + "），本期外部退款只支持已付款未入账异常单");
        }
        // 闸③ 权益未入账
        if (evidence.rechargeFlowSeen()) {
            throw new JbkException("该订单已存在充值入账流水，权益已发放，外部退款须走权益批次冲正（包D）");
        }
        // 闸④ 未因此建出水卡
        if (evidence.issuedCardSeen()) {
            throw new JbkException("该订单已发出水卡，权益已发放，外部退款须走权益批次冲正（包D）");
        }

        long amount = payment.getPayAmount() == null ? 0L : payment.getPayAmount();
        if (amount <= 0) {
            throw new JbkException("原支付金额非法（" + amount + "），拒绝退款");
        }
        if (ObjectUtil.notEqual(order.getOrderAmount(), amount)) {
            throw new JbkException("订单金额与原支付金额不一致，拒绝退款");
        }
        return amount;
    }

    /**
     * 从服务端冻结快照中读取充值退款路径。不得用当前订单状态或批次形状猜路径
     * （退款成功后订单转 7/8、批次形状可被并发改变）；路径在受理时冻结，未知值一律拒绝。
     */
    public static RefundPath requireRefundPath(WsAfterSaleAction action) {
        if (ObjectUtil.isNull(action)
                || ObjectUtil.notEqual(action.getSourceType(), AfterSaleEnum.SourceType.RECHARGE_REFUND.getValue())
                || ObjectUtil.notEqual(action.getActionType(), AfterSaleEnum.ActionType.GATEWAY_REFUND.getValue())) {
            throw new JbkException("充值退款动作类型或来源不一致，拒绝退款");
        }
        if (StrUtil.isBlank(action.getCalcSnapshot())) {
            throw new JbkException("充值退款动作缺少路径快照，转人工核对");
        }
        try {
            JSONObject snapshot = JSONUtil.parseObj(action.getCalcSnapshot());
            String code = snapshot.getStr(REFUND_PATH_FIELD);
            return RefundPath.valueOf(code);
        } catch (RuntimeException invalid) {
            throw new JbkException("充值退款动作路径快照非法，转人工核对");
        }
    }

    /**
     * 已入账充值/购卡退款的准入证据（E2E-04 包D-5，REQ-061）。
     *
     * @param order            订单行（必须 {@code ORDER_TYPE=2}、{@code ORDER_STATUS=4已完成}）
     * @param payment          支付单行
     * @param batchLockedByMe  该充值的权益批次是否已被<b>本次售后动作</b>锁定为 2退款锁定
     * @param plannedRefundFen 受理时算定并冻结在售后动作上的可退金额（分）
     */
    public record BatchEvidence(WsOrder order, WsPayment payment, boolean batchLockedByMe,
                                long plannedRefundFen) {
    }

    /**
     * 断言这笔订单允许走「已入账权益按批次折算」退款（与 {@link #requireUnsettledFullRefund} 互斥）。
     * 三道闸：订单必须 4已完成（放宽即同一笔钱按两条路径各退一次）；批次必须被<b>本次</b>动作锁定
     * （只要求「锁定」会让并发退款的后一笔拿前一笔的锁当资格）；金额取受理时冻结值、绝不重算
     * （重算会随批次剩余变化漂移）。
     *
     * @return 允许退款的金额（分）：恒等于受理时冻结的计划金额
     */
    public static long requireEntitlementRefund(BatchEvidence evidence) {
        if (evidence == null || ObjectUtil.isNull(evidence.order()) || ObjectUtil.isNull(evidence.payment())) {
            throw new JbkException("退款准入证据缺失，拒绝退款");
        }
        WsOrder order = evidence.order();
        WsPayment payment = evidence.payment();
        if (ObjectUtil.notEqual(payment.getOrderId(), order.getId())) {
            throw new JbkException("支付单与订单归属错位，拒绝退款");
        }
        if (ObjectUtil.notEqual(payment.getPayStatus(), PAY_STATUS_SUCCESS)) {
            throw new JbkException("原支付单不是支付成功状态（实际 " + payment.getPayStatus() + "），拒绝退款");
        }
        if (ObjectUtil.notEqual(order.getOrderType(), TradeEnum.OrderType.CARD.getValue())) {
            throw new JbkException("只有购卡/充值订单可以走权益批次退款");
        }
        if (ObjectUtil.notEqual(order.getOrderStatus(), TradeEnum.OrderStatus.FINISHED.getValue())) {
            throw new JbkException("订单不是已完成状态（实际 " + order.getOrderStatus()
                    + "），权益批次退款只受理已完成的充值订单");
        }
        if (!evidence.batchLockedByMe()) {
            throw new JbkException("权益批次未被本次售后动作锁定，拒绝退款——未锁定的批次仍可被消费，折算基准不稳定");
        }
        long planned = evidence.plannedRefundFen();
        if (planned <= 0) {
            throw new JbkException("受理时冻结的可退金额非法（" + planned + "），拒绝退款");
        }
        long paid = payment.getPayAmount() == null ? 0L : payment.getPayAmount();
        if (planned > paid) {
            // 折算结果超过原支付金额：公式或批次快照已被破坏，绝不按它出账
            throw new JbkException("折算可退金额(" + planned + ")超过原支付金额(" + paid + ")，拒绝退款");
        }
        return planned;
    }

    /**
     * 退款通道必须与原支付通道同源。当前支付与退款来源都冻结为 1微信、2模拟，
     * 不允许用模拟事实结算真实微信支付单，也不允许把模拟支付单送进真实退款通道。
     */
    public static void requireMatchingSource(WsPayment payment, Integer expectedRefundSource) {
        if (ObjectUtil.isNull(payment)) {
            throw new JbkException("原支付单缺失，拒绝退款");
        }
        if (!ObjectUtil.equals(expectedRefundSource, 1) && !ObjectUtil.equals(expectedRefundSource, 2)) {
            throw new JbkException("退款来源非法，拒绝退款");
        }
        if (ObjectUtil.notEqual(payment.getPaySource(), expectedRefundSource)) {
            throw new JbkException("退款来源与原支付来源不一致：支付来源 " + payment.getPaySource()
                    + "，退款来源 " + expectedRefundSource + "，拒绝退款");
        }
    }

    /**
     * 累计退款封顶：本次 + 已成功 不得超过原支付金额。「已成功」必须由调用方在
     * <b>锁定原支付单之后</b>聚合，否则读到并发提交前的旧视图，两笔各自不超额、合起来超额。
     *
     * @param paidAmountFen     原支付金额（分）
     * @param succeededFen      该支付单已成功退款金额合计（分）
     * @param requestedFen      本次请求退款金额（分）
     */
    public static void requireWithinPaidAmount(long paidAmountFen, long succeededFen, long requestedFen) {
        if (paidAmountFen <= 0) {
            throw new JbkException("原支付金额非法（" + paidAmountFen + "），拒绝退款");
        }
        if (requestedFen <= 0) {
            throw new JbkException("本次退款金额必须为正，实际 " + requestedFen);
        }
        if (succeededFen < 0) {
            throw new JbkException("已成功退款金额为负（" + succeededFen + "），账本异常，拒绝退款");
        }
        long remaining = Math.subtractExact(paidAmountFen, succeededFen);
        if (requestedFen > remaining) {
            throw new JbkException("退款金额超出原支付金额：本次 " + requestedFen + "，剩余 " + remaining
                    + "（原支付 " + paidAmountFen + "，已退 " + succeededFen + "）");
        }
    }

    /**
     * 一张订单必须恰有一张有效支付单——两条退款路径共用这一份。
     * 多张即账本分叉，fail-closed 而不是取第一张（按哪张退、封顶按哪张算都没有确定答案）。
     *
     * @param payments 调用方在<b>锁定原单之后</b>查出的有效支付单列表
     * @return 唯一那张支付单
     */
    public static WsPayment requireSinglePayment(List<WsPayment> payments) {
        if (payments == null || payments.isEmpty()) {
            throw new JbkException("订单没有有效支付单，拒绝退款");
        }
        if (payments.size() > 1) {
            throw new JbkException("订单存在 " + payments.size() + " 张有效支付单，账本分叉，拒绝退款");
        }
        return payments.get(0);
    }

    /** 支付成功(1342#2)。 */
    private static final int PAY_STATUS_SUCCESS = 2;
}

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
 * <h3>本包只放行一条路径，且刻意窄</h3>
 * <p>任务书原文：「当前无权益批次时，外部退款只允许 payment=2、order=6、
 * 不存在 {@code RECHARGE:<orderNo>} 入账流水、未生成或未增加水卡权益」，
 * 用途限定为<b>「已付款但未入账异常单」的全额退款</b>。</p>
 *
 * <p>为什么必须这么窄：权益批次模型是包D 的内容。在它到位之前，
 * 一旦水卡权益<b>已经发出去</b>，就没有任何数据结构能回答「这笔外部退款应该冲正哪一批权益、
 * 冲多少、用户已经用掉的部分怎么算」。此时放行外部退款 = 用户既拿到了水，又拿回了钱。
 * 因此本类的四道闸合起来只表达一件事：<b>这笔钱收了，但权益一点没发。</b>
 * 四条缺一不可，任一条不满足即 fail-closed，不做「大概是安全的」这种判断。</p>
 *
 * <h3>四道闸各自防什么</h3>
 * <ol>
 *   <li>{@code payment.PAY_STATUS=2}：钱确实收到了。没收到钱就退款是凭空出账。</li>
 *   <li>{@code order.ORDER_STATUS=6 异常待补偿}：这是「收了钱但没能完成」的唯一合法形状。
 *       已完成(4)的单退款要走权益冲正，待支付(1)的单根本没收到钱。</li>
 *   <li>不存在 {@code RECHARGE:<orderNo>} 入账流水：入账流水是权益已发的<b>直接证据</b>，
 *       且由唯一键保证「发过就一定有且只有一条」。</li>
 *   <li>不存在 {@code ISSUE_ORDER_ID=orderId} 的水卡：第 3 条查的是「有没有加权益」，
 *       这条查的是「有没有因此建出一张卡」。首购路径两者同事务写入，
 *       但只查其一意味着任何一侧被改写、补录或人工修数都能骗过判定，
 *       而代价是给一张已经发出去的卡对应的订单全额退款。</li>
 * </ol>
 *
 * <p>本类<b>只判定、不写库</b>，也不持有任何 Mapper：证据由调用方查好传进来。
 * 这样判定逻辑可被纯单测穷举，而调用方无法「顺手在判定里补一条查询」把口径改宽。</p>
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

        // 主体与通道共键先于业务判定：任何一项不一致，后续状态与金额都不再具有同一业务含义。
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
     * 从服务端冻结快照中读取充值退款路径。
     *
     * <p>不能再用当前订单状态或“现在有没有批次”猜路径：退款成功后订单会转 7/8，
     * 并发恢复入账也可能在受理后改变批次形状。路径必须在受理动作创建时冻结，未知值一律拒绝。</p>
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
     * 断言这笔订单允许走「已入账权益按批次折算」退款，不允许则抛出可读错因。
     *
     * <h3>与 {@link #requireUnsettledFullRefund} 是两条互斥的路径，故各有一套闸</h3>
     * <p>那条路径的前提是「权益一点没发」，这条恰好相反：权益<b>已经发了</b>，
     * 所以安全性不再来自「没发过」，而来自三件事：</p>
     * <ol>
     *   <li>订单是 4已完成——「钱收了、权益也发了」的唯一形状。6异常待补偿 属于另一条路径，
     *       7/8 说明已经退过；放宽这一条就会出现同一笔钱按两条路径各退一次；</li>
     *   <li>权益批次已被<b>本次售后动作</b>锁定：锁定意味着从受理那一刻起这批权益不能再被消费，
     *       金额折算的分母才是稳定的。只要求「锁定」而不要求「锁定方是我」是不够的——
     *       那样两笔并发退款里的后一笔会拿前一笔的锁当成自己的资格；</li>
     *   <li>可退金额由服务端在受理时算定并冻结（{@code EntitlementRefundPlan}），
     *       本方法只核验它为正且不超过原支付金额，绝不重算：重算会随批次剩余的后续变化漂移。</li>
     * </ol>
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
     * 累计退款封顶：本次 + 已成功 不得超过原支付金额。
     *
     * <p>与包A 的三维额度封顶同一条纪律，区别只在锚点——那边是订单快照，这边是原支付金额。
     * 「已成功」由调用方在<b>锁定原支付单之后</b>聚合，否则会读到并发退款提交前的旧视图，
     * 两笔各自看起来都没超额，合起来超额（与包A 的 RR 快照陷阱同源）。</p>
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
     * 一张订单必须恰有一张有效支付单——<b>两条退款路径共用这一份</b>。
     *
     * <p>零张说明订单从未支付成功过（准入闸随后也会拒），多张说明账本已分叉——
     * 此时「按哪张支付单退款、封顶按哪张的金额算」没有确定答案，任何一种选法都可能退错，
     * 故 fail-closed 而不是取第一张。</p>
     *
     * <p><b>为什么收进本类</b>：它原先在 {@code RefundRequestTxServiceImpl} 与
     * {@code EntitlementRefundTxServiceImpl} 各有一份私有实现，两份在落地的同一轮里就已经漂移
     * （一份区分「0 张 / 多张」两种拒因，另一份糊成一句）。判定逻辑一律外借不重写，
     * 拒因精确度也是判定的一部分：运营看到「订单没有有效支付单」与「存在 2 张支付单，账本分叉」
     * 要做的事完全不同。</p>
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

    /** 支付成功(1342#2)。此处写常量而非 import 一个只有一个取值被用到的枚举，与判定同处一屏便于核对。 */
    private static final int PAY_STATUS_SUCCESS = 2;
}

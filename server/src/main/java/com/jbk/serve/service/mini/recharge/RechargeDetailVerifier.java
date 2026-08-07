package com.jbk.serve.service.mini.recharge;

import com.jbk.serve.service.mini.card.WaterCardScope;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsPaymentEvent;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.trade.vo.MiniRechargeDetailVo;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 充值订单只读证据校验器。
 *
 * <p>小程序订单详情与 PC 订单追溯共用这一入口，避免各自维护一套支付、发卡和入账
 * 共键算法。全部查询跨逻辑删除态；任一污染或错位均 fail-closed。</p>
 */
@Component
@RequiredArgsConstructor
public class RechargeDetailVerifier {

    private static final int ORDER_TYPE_RECHARGE = 2;
    private static final int PAY_WAY_WECHAT = 1;
    private static final int CARD_FLOW_RECHARGE = 1;

    private final RechargeIdentityMapper identityMapper;
    private final RechargeRefundEvidenceVerifier refundEvidenceVerifier;

    public MiniRechargeDetailVo verify(WsOrder order) {
        if (order == null || !ObjectUtil.equals(order.getDataStatus(), 0)
                || !ObjectUtil.equals(order.getOrderType(), ORDER_TYPE_RECHARGE)
                || !ObjectUtil.equals(order.getPayWay(), PAY_WAY_WECHAT)) {
            throw new JbkException("充值订单主体不可用或类型错误");
        }

        RechargeSnapshot.Parsed snapshot;
        try {
            snapshot = RechargeSnapshot.parse(order.getPackageSnap());
        } catch (RuntimeException invalidSnapshot) {
            return new MiniRechargeDetailVo().setSnapshotValid(false);
        }
        if (!StrUtil.equals(snapshot.capturedTime(), order.getCreateTime())
                || !StrUtil.equals(snapshot.packageId(), String.valueOf(order.getPackageId()))
                || !ObjectUtil.equals(snapshot.payAmount(), order.getOrderAmount())) {
            throw new JbkException("充值订单快照与订单共键错位");
        }
        RechargeCredit credit = RechargeCredit.of(snapshot);
        boolean purchase = RechargeSnapshot.PURCHASE_MODE_FIRST_CARD.equals(snapshot.purchaseMode());
        boolean creditedLifecycle = isCreditedLifecycle(order.getOrderStatus());
        requireCardTiming(order, purchase, creditedLifecycle);

        WsPayment payment = requirePayment(order, snapshot);
        List<WsPaymentEvent> events = safe(identityMapper
                .selectEventsByOrderNoIncludingDeleted(order.getOrderNo()));
        requireEvents(order, payment, events);
        List<WsWalletFlow> flows = safe(identityMapper
                .selectFlowsByOrderIdIncludingDeleted(order.getId())).stream()
                .filter(flow -> ObjectUtil.equals(flow.getFlowType(), CARD_FLOW_RECHARGE))
                .toList();

        MiniRechargeDetailVo result = baseDetail(snapshot, payment, events);
        WsWalletFlow flow = requireFlow(order, payment, events, flows, credit, purchase);
        if (flow != null) {
            result.setFlowAmountChange(flow.getAmountChange())
                    .setFlowMlChange(flow.getMlChange())
                    .setFlowAmountAfter(flow.getAmountAfter())
                    .setFlowMlAfter(flow.getMlAfter());
        }
        refundEvidenceVerifier.requireIfRefunded(order);
        if (purchase && !creditedLifecycle) {
            return result;
        }

        WsCard card = requireCard(order, snapshot, purchase);
        WaterCardScope actualScope = WaterCardScope.normalize(card.getScopeJson(), "充值订单目标卡");
        return result.setCardId(card.getId())
                .setCardNo(card.getCardNo())
                .setCardType(card.getCardType())
                .setIssueOrderId(purchase ? card.getIssueOrderId() : null)
                .setScopeDescription(actualScope.description())
                .setCardBalanceFen(card.getBalanceAmount())
                .setCardBalanceMl(card.getBalanceMl())
                .setCardExpireTime(card.getExpireTime());
    }

    private MiniRechargeDetailVo baseDetail(RechargeSnapshot.Parsed snapshot, WsPayment payment,
                                            List<WsPaymentEvent> events) {
        return new MiniRechargeDetailVo()
                .setSnapshotValid(true)
                .setPurchaseMode(snapshot.purchaseMode())
                .setPackageName(snapshot.packageName())
                .setPayAmountFen(snapshot.payAmount())
                .setWaterMl(snapshot.waterMl())
                .setBonusAmountFen(snapshot.bonusAmount())
                .setExpireDays(snapshot.expireDays())
                .setPayStatus(payment.getPayStatus())
                .setPaySource(payment.getPaySource())
                .setProcessingStatus(RechargePayStatus.aggregateProcessing(events));
    }

    private void requireCardTiming(WsOrder order, boolean purchase, boolean completed) {
        if (purchase && completed && order.getCardId() == null) {
            throw new JbkException("首次购卡完成订单缺少发行卡关联");
        }
        if (purchase && !completed && order.getCardId() != null) {
            throw new JbkException("未完成首次购卡订单不应提前关联水卡");
        }
        if (!purchase && order.getCardId() == null) {
            throw new JbkException("已有卡充值订单缺少目标卡");
        }
    }

    private WsPayment requirePayment(WsOrder order, RechargeSnapshot.Parsed snapshot) {
        List<WsPayment> payments = safe(identityMapper
                .selectPaymentsByOrderIdIncludingDeleted(order.getId()));
        if (payments.size() != 1) {
            throw new JbkException("充值订单支付单必须恰好一条");
        }
        WsPayment payment = payments.get(0);
        String expectedExpire = RechargePayExpire.compute(
                snapshot.capturedTime(), snapshot.expireTimeAtCreate());
        if (!ObjectUtil.equals(payment.getDataStatus(), 0)
                || !ObjectUtil.equals(payment.getOrderId(), order.getId())
                || !StrUtil.equals(payment.getOrderNo(), order.getOrderNo())
                || !ObjectUtil.equals(payment.getPayAmount(), order.getOrderAmount())
                || !"CNY".equals(payment.getCurrency())
                || (!ObjectUtil.equals(payment.getPaySource(), 1)
                    && !ObjectUtil.equals(payment.getPaySource(), 2))
                || !StrUtil.equals(payment.getPayExpireTime(), expectedExpire)) {
            throw new JbkException("充值订单与支付单共键或付款资格错位");
        }
        if (ObjectUtil.equals(payment.getPayStatus(), RechargePayStatus.PAY_SUCCESS)) {
            if (StrUtil.isBlank(payment.getTransactionId())
                    || StrUtil.isBlank(payment.getPaySuccessTime())
                    || !RechargePayExpire.paidInTime(
                            payment.getPaySuccessTime(), payment.getPayExpireTime())) {
                throw new JbkException("充值成功支付单缺少权威事实或已超过付款截止时间");
            }
            if (StrUtil.isNotBlank(order.getFinishTime())
                    && order.getFinishTime().compareTo(payment.getPaySuccessTime()) < 0) {
                throw new JbkException("充值订单完成时间早于支付成功时间");
            }
        } else if (ObjectUtil.equals(payment.getPayStatus(), RechargePayStatus.PAY_PENDING)
                && (StrUtil.isNotBlank(payment.getTransactionId())
                    || StrUtil.isNotBlank(payment.getPaySuccessTime()))) {
            throw new JbkException("待支付记录携带成功支付证据");
        }
        return payment;
    }

    private void requireEvents(WsOrder order, WsPayment payment, List<WsPaymentEvent> events) {
        for (WsPaymentEvent event : events) {
            if (!ObjectUtil.equals(event.getDataStatus(), 0)
                    || !StrUtil.equals(event.getOrderNo(), order.getOrderNo())
                    || !ObjectUtil.equals(event.getOrderId(), order.getId())
                    || !ObjectUtil.equals(event.getPaymentId(), payment.getId())
                    || !ObjectUtil.equals(event.getPaySource(), payment.getPaySource())) {
                throw new JbkException("充值订单存在删除态或错位支付事实");
            }
            if (RechargePayStatus.SUCCESS.equals(event.getTradeState())
                    && (!ObjectUtil.equals(event.getPayAmount(), payment.getPayAmount())
                        || !"CNY".equals(event.getCurrency())
                        || !StrUtil.equals(event.getTransactionId(), payment.getTransactionId())
                        || !StrUtil.equals(event.getPaySuccessTime(), payment.getPaySuccessTime()))) {
                throw new JbkException("充值订单成功支付事实与支付单不一致");
            }
        }
    }

    private WsWalletFlow requireFlow(WsOrder order, WsPayment payment,
                                     List<WsPaymentEvent> events, List<WsWalletFlow> flows,
                                     RechargeCredit credit, boolean purchase) {
        RechargePayStatus.Resolved state = RechargePayStatus.resolve(
                order, payment, events, flows.size());
        if (!state.ok()) {
            throw new JbkException("充值状态证据不一致：" + state.reason());
        }
        if (!isCreditedLifecycle(order.getOrderStatus())) {
            return null;
        }
        WsWalletFlow flow = flows.get(0);
        if (!ObjectUtil.equals(flow.getDataStatus(), 0)
                || !ObjectUtil.equals(flow.getFlowType(), CARD_FLOW_RECHARGE)
                || !ObjectUtil.equals(flow.getCardId(), order.getCardId())
                || !ObjectUtil.equals(flow.getUserId(), order.getUserId())
                || !ObjectUtil.equals(flow.getOrderId(), order.getId())
                || !StrUtil.equals(flow.getBizIdempotencyKey(), "RECHARGE:" + order.getOrderNo())
                || !ObjectUtil.equals(flow.getAmountChange(), credit.amountFen())
                || !ObjectUtil.equals(flow.getMlChange(), credit.ml())
                || flow.getAmountAfter() == null || flow.getMlAfter() == null) {
            throw new JbkException("已完成充值订单流水共键或权益值不一致");
        }
        if (purchase && (!ObjectUtil.equals(flow.getAmountAfter(), credit.amountFen())
                || !ObjectUtil.equals(flow.getMlAfter(), credit.ml()))) {
            throw new JbkException("首次购卡流水 AFTER 与零初始权益不一致");
        }
        return flow;
    }

    private boolean isCreditedLifecycle(Integer status) {
        return ObjectUtil.equals(status, TradeEnum.OrderStatus.FINISHED.getValue())
                || RechargeRefundEvidenceVerifier.isRefundedStatus(status);
    }

    private WsCard requireCard(WsOrder order, RechargeSnapshot.Parsed snapshot, boolean purchase) {
        List<WsCard> cards = safe(identityMapper
                .selectCardsByIdIncludingDeleted(order.getCardId()));
        if (cards.size() != 1 || !ObjectUtil.equals(cards.get(0).getDataStatus(), 0)
                || !ObjectUtil.equals(cards.get(0).getId(), order.getCardId())
                || !ObjectUtil.equals(cards.get(0).getUserId(), order.getUserId())) {
            throw new JbkException("充值订单目标卡缺失、删除或归属错位");
        }
        WsCard card = cards.get(0);
        WaterCardScope actualScope = WaterCardScope.normalize(card.getScopeJson(), "充值订单目标卡");
        if (!actualScope.sameAuthorityAs(snapshot.cardScope())) {
            throw new JbkException("充值订单目标卡范围与订单快照不一致");
        }
        if (purchase && !ObjectUtil.equals(card.getIssueOrderId(), order.getId())) {
            throw new JbkException("首次购卡发行锚点与订单不一致");
        }
        if (purchase && !ObjectUtil.equals(card.getCardType(), snapshot.plannedCardType())) {
            throw new JbkException("首次购卡实际卡类型与订单快照不一致");
        }
        return card;
    }

    private static <T> List<T> safe(List<T> rows) {
        return rows == null ? List.of() : rows;
    }
}

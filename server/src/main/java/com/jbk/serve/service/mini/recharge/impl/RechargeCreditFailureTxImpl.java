package com.jbk.serve.service.mini.recharge.impl;

import com.jbk.tool.utils.DateUtils;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.serve.service.mini.recharge.IRechargeCreditFailureTx;
import com.jbk.serve.service.mini.recharge.RechargePayStatus;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsPaymentEvent;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/** L2 v2 §6.5：失败后重新锁定现状，绝不沿用失败事务的对象。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeCreditFailureTxImpl implements IRechargeCreditFailureTx {

    private static final int ORDER_PAID = 2;
    private static final int ORDER_FINISHED = 4;
    private static final int PAY_SUCCESS = 2;

    private final RechargeCreditMapper mapper;
    private final RechargeLockedState lockedState;
    private final RechargeLedgerVerifier ledgerVerifier;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void record(Long eventId, boolean retryable, String reason, String now) {
        RechargeLockedState.Locked locked = lockedState.load(eventId);
        WsPayment payment = locked.payment();
        WsOrder order = locked.order();
        String safeReason = StrUtil.maxLength(StrUtil.blankToDefault(reason, "充值权益处理失败"), 500);

        if (!ObjectUtil.equals(payment.getPayStatus(), PAY_SUCCESS)) {
            throw new JbkException("失败落痕时支付单不在成功态，拒绝覆盖现状");
        }
        if (ObjectUtil.equals(order.getOrderStatus(), ORDER_FINISHED)) {
            settleCompletedOrder(locked, safeReason, now);
            return;
        }
        if (!ObjectUtil.equals(order.getOrderStatus(), ORDER_PAID)) {
            throw new JbkException("失败落痕时订单不在可写前态，拒绝覆盖现状");
        }

        if (retryable) {
            updateMatchingGroup(locked, true, safeReason, now);
            return;
        }
        if (mapper.markOrderAbnormal(order.getId(), StrUtil.maxLength("充值入账待人工：" + safeReason, 200), now) != 1) {
            throw new JbkException("订单异常落痕 CAS 失败");
        }
        updateMatchingGroup(locked, false, safeReason, now);
    }

    private void settleCompletedOrder(RechargeLockedState.Locked locked, String reason, String now) {
        try {
            RechargeLockedState.Verified verified = lockedState.verifyForCompleted(locked);
            ledgerVerifier.requireAlreadyCredited(locked.order(), locked.card(), locked.flows(), verified.credit(),
                    verified.snapshot(), locked.payment().getPaySuccessTime());
            processMatchingGroup(locked, now);
        } catch (RuntimeException mismatch) {
            log.error("已完成充值单的失败落痕核验不一致：orderNo={} reason={} verifyError={}",
                    locked.order().getOrderNo(), reason, mismatch.getMessage());
            reconcileMatchingGroup(locked, StrUtil.maxLength(reason + "；完成态核验失败：" + mismatch.getMessage(), 500), now);
        }
    }

    private void updateMatchingGroup(RechargeLockedState.Locked locked, boolean retryable,
                                     String reason, String now) {
        int targets = 0;
        for (WsPaymentEvent row : locked.events()) {
            if (!matches(row, locked.payment(), locked.order()) || ObjectUtil.equals(row.getProcessingStatus(), 3)) {
                continue;
            }
            targets++;
            Integer expected = row.getProcessingStatus();
            if (!ObjectUtil.equals(expected, 1) && !ObjectUtil.equals(expected, 2)
                    && !ObjectUtil.equals(expected, 4)) {
                throw new JbkException("支付事实组含不可覆盖的失败状态：" + expected);
            }
            int changed = retryable
                    ? mapper.markEventRetryWait(row.getId(), expected, DateUtils.plusSeconds(now, 60), reason, now)
                    : mapper.markEventReconciliation(row.getId(), expected, reason, now);
            if (changed != 1) {
                throw new JbkException("支付事实组失败落痕影响行数异常");
            }
        }
        if (targets == 0) {
            throw new JbkException("没有可落痕的匹配成功支付事实");
        }
    }

    private void processMatchingGroup(RechargeLockedState.Locked locked, String now) {
        for (WsPaymentEvent row : locked.events()) {
            if (!matches(row, locked.payment(), locked.order()) || ObjectUtil.equals(row.getProcessingStatus(), 3)) {
                continue;
            }
            if (mapper.markEventProcessed(row.getId(), row.getProcessingStatus(), now) != 1) {
                throw new JbkException("已完成订单事件收敛影响行数异常");
            }
        }
    }

    private void reconcileMatchingGroup(RechargeLockedState.Locked locked, String reason, String now) {
        for (WsPaymentEvent row : locked.events()) {
            if (!matches(row, locked.payment(), locked.order()) || ObjectUtil.equals(row.getProcessingStatus(), 3)
                    || ObjectUtil.equals(row.getProcessingStatus(), 5)) {
                continue;
            }
            if (mapper.markEventReconciliation(row.getId(), row.getProcessingStatus(), reason, now) != 1) {
                throw new JbkException("已完成订单异常事件落痕影响行数异常");
            }
        }
    }

    private boolean matches(WsPaymentEvent row, WsPayment payment, WsOrder order) {
        return RechargePayStatus.SUCCESS.equals(row.getTradeState())
                && ObjectUtil.equals(row.getDataStatus(), 0)
                && ObjectUtil.equals(row.getPaymentId(), payment.getId())
                && ObjectUtil.equals(row.getOrderId(), order.getId())
                && StrUtil.equals(row.getOrderNo(), order.getOrderNo())
                && ObjectUtil.equals(row.getPaySource(), payment.getPaySource())
                && ObjectUtil.equals(row.getPayAmount(), payment.getPayAmount())
                && "CNY".equals(row.getCurrency())
                && StrUtil.equals(row.getTransactionId(), payment.getTransactionId())
                && StrUtil.equals(row.getPaySuccessTime(), payment.getPaySuccessTime());
    }

}

package com.jbk.serve.service.mini.recharge.impl;

import cn.hutool.core.util.ObjectUtil;
import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.serve.service.mini.recharge.IRechargePayCloseTx;
import com.jbk.serve.service.mini.recharge.RechargePayStatus;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 关单事务实现（契约 §6.2 第 6 条）。
 *
 * <p>固定锁序 payment → order，与事务 B 的前两级一致——关单和入账可能同时到达同一订单，
 * 反序取锁就会互相死锁。</p>
 *
 * <p>三条 CAS 全在同一事务：payment 1→4、order 1→5、事实 2→3。任一影响行数不为 1 都抛异常整体回滚，
 * 绝不留下"支付单关了但订单还在待支付"或"状态已推进但事实仍待处理（下次查单会再关一次）"的中间态。</p>
 */
@Service
@RequiredArgsConstructor
public class RechargePayCloseTxImpl implements IRechargePayCloseTx {

    /** 关单原因写进 CANCEL_REASON，让 PC/小程序能解释订单为什么变成已关闭。 */
    private static final String CLOSE_REASON = "支付方查单确认未支付并关闭";

    private final RechargeCreditMapper creditMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result close(Long orderId, Long paymentId, Long eventId, String now) {
        WsPayment payment = creditMapper.lockPayment(paymentId);
        WsOrder order = creditMapper.lockOrder(orderId);
        if (payment == null || order == null
                || !ObjectUtil.equals(payment.getDataStatus(), 0)
                || !ObjectUtil.equals(order.getDataStatus(), 0)) {
            return Result.MISMATCH;
        }
        int pay = payment.getPayStatus() == null ? -1 : payment.getPayStatus();
        int ord = order.getOrderStatus() == null ? -1 : order.getOrderStatus();

        // 重复 CLOSED 只能在**精确** payment 4/order 5 下幂等收敛。
        // 不用「payment 4 或 order 5 之一成立」放行：半推进状态本身就是需要人工看的异常。
        if (pay == RechargePayStatus.PAY_CLOSED && ord == RechargePayStatus.ORDER_CANCELLED) {
            markProcessed(eventId, now);
            return Result.ALREADY;
        }
        if (pay != RechargePayStatus.PAY_PENDING || ord != RechargePayStatus.ORDER_PENDING) {
            // 与任何成功态或其他组合冲突：不得回退，交人工对账
            return Result.MISMATCH;
        }

        if (creditMapper.markPaymentClosed(paymentId, now) != 1) {
            // 锁内读到 1 却改不动，说明前态在同一事务视图外被改写，宁可回滚也不重试
            throw new IllegalStateException("支付单关闭 CAS 影响行数异常，paymentId=" + paymentId);
        }
        if (creditMapper.markOrderClosed(orderId, CLOSE_REASON, now) != 1) {
            throw new IllegalStateException("订单关闭 CAS 影响行数异常，orderId=" + orderId);
        }
        markProcessed(eventId, now);
        return Result.CLOSED;
    }

    private void markProcessed(Long eventId, String now) {
        if (creditMapper.markEventProcessed(eventId, RechargePayStatus.P_PROCESSING, now) != 1) {
            throw new IllegalStateException("关闭事实收敛影响行数异常，eventId=" + eventId);
        }
    }
}

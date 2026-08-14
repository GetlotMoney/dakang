package com.jbk.serve.service.mini.recharge.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.service.mini.recharge.IRechargePayConfirmTx;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 事务A 实现：两条 CAS 更新，任一不符预期即判 MISMATCH，绝不"尽力推进"。
 */
@Service
@RequiredArgsConstructor
public class RechargePayConfirmTxImpl implements IRechargePayConfirmTx {

    private static final int PAY_SUCCESS = 2;
    private static final int ORDER_PAID = 2;
    private static final int ORDER_FINISHED = 4;

    private final RechargeCreditMapper creditMapper;
    private final RechargeIdentityMapper identityMapper;
    /** 订阅通知登记：与支付事实同事务——确认回滚意味着这笔支付没被认定，通知必须一起消失。 */
    private final com.jbk.serve.service.mini.notify.WechatNotifyEnqueue notifyEnqueue;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result confirm(WsOrder order, WsPayment payment, String transactionId,
                          String paySuccessTime, String now) {
        int payRows = creditMapper.markPaymentSuccess(payment.getId(), transactionId, paySuccessTime, now);
        if (payRows != 1) {
            // 没改动 = 支付单已不在 1待支付。只有「已经是成功且交易号与成功时间完全一致」才算重复回调，
            // 否则就是同一支付单被两笔不同交易写过，属于必须人工介入的资金错位。
            WsPayment current = reread(payment.getOrderId());
            if (current == null
                    || !ObjectUtil.equals(current.getPayStatus(), PAY_SUCCESS)
                    || !StrUtil.equals(current.getTransactionId(), transactionId)
                    || !StrUtil.equals(current.getPaySuccessTime(), paySuccessTime)) {
                return Result.MISMATCH;
            }
            return alreadyOrMismatch(order, now);
        }
        int orderRows = creditMapper.markOrderPaid(order.getId(), now);
        if (orderRows != 1) {
            // 支付单刚被本次改成功，订单却不在 1待支付——两者已经不同步，回滚并转人工
            throw new IllegalStateException("订单状态与支付单不同步，orderNo=" + order.getOrderNo());
        }
        // 只在**首次**确认时登记（重复回调走上面的 alreadyOrMismatch 分支，不到这里），
        // 因此同一笔支付不会因为微信重投而多通知一次。
        // 这条只说「钱收到了」；「权益到卡了」是另一条（RECHARGE_CREDITED），两者之间
        // 还隔着入账事务，不能合并成一条——合并会让入账转人工的单也发出「已到账」。
        notifyEnqueue.enqueue(com.jbk.tool.consts.mini.WechatNotifyEnum.EventType.PAYMENT_SUCCEEDED,
                com.jbk.tool.consts.mini.WechatNotifyEnum.BizObjectType.ORDER,
                order.getOrderNo(), order.getUserId(),
                cn.hutool.json.JSONUtil.createObj().set("paySuccessTime", paySuccessTime));
        return Result.CONFIRMED;
    }

    /** 支付单已成功时，订单必须已在 2已支付 或 4已完成，否则算错位。 */
    private Result alreadyOrMismatch(WsOrder order, String now) {
        List<WsOrder> rows = identityMapper.selectOrdersByOrderNoIncludingDeleted(order.getOrderNo());
        if (rows == null || rows.size() != 1) {
            return Result.MISMATCH;
        }
        Integer status = rows.get(0).getOrderStatus();
        if (ObjectUtil.equals(status, ORDER_PAID) || ObjectUtil.equals(status, ORDER_FINISHED)) {
            return Result.ALREADY;
        }
        if (ObjectUtil.equals(status, 1)) {
            // 支付单成功但订单还停在待支付：把订单补推到已支付，让入账可以继续
            return creditMapper.markOrderPaid(order.getId(), now) == 1 ? Result.ALREADY : Result.MISMATCH;
        }
        return Result.MISMATCH;
    }

    private WsPayment reread(Long orderId) {
        List<WsPayment> rows = identityMapper.selectPaymentsByOrderIdIncludingDeleted(orderId);
        return rows == null || rows.size() != 1 ? null : rows.get(0);
    }
}

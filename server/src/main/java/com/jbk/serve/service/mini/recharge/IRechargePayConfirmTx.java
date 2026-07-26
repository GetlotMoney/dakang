package com.jbk.serve.service.mini.recharge;

import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;

/**
 * 支付事实认定事务（L2-T 事务A）：支付单转成功 + 订单转已支付，同事务。
 *
 * <p>与入账（事务B）刻意分开：钱收到了是一个事实，权益到账是另一个动作。
 * 两者之间崩溃时订单停在 2已支付，pay-status 返回 PAID_CREDIT_PENDING，
 * 入账可以安全重放——合成一个大事务反而会让"钱已收"这个事实一起丢掉。</p>
 */
public interface IRechargePayConfirmTx {

    enum Result {
        /** 本次把支付单与订单推进到了成功态。 */
        CONFIRMED,
        /** 之前已推进过且与本次事实一致（重复回调）。 */
        ALREADY,
        /** 状态或关键字段错位，不得继续入账。 */
        MISMATCH
    }

    Result confirm(WsOrder order, WsPayment payment, String transactionId,
                   String paySuccessTime, String now);
}

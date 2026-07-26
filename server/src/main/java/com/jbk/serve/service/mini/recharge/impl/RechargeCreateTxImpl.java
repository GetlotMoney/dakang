package com.jbk.serve.service.mini.recharge.impl;

import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.mini.recharge.IRechargeCreateTx;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 充值订单创建事务：order + payment 同事务写入，任一失败整体回滚（§4.2）。
 *
 * <p>本 Bean 只负责"写"，不做幂等判定——幂等由编排层按 ORDER_NO 跨全状态查询决定，
 * 唯一键冲突（DuplicateKeyException）也由编排层重读后走同一套核验，
 * <b>绝不把 DuplicateKey 直接当成功</b>。</p>
 */
@Service
@RequiredArgsConstructor
public class RechargeCreateTxImpl implements IRechargeCreateTx {

    /** 币种一期固定 CNY。 */
    private static final String CURRENCY_CNY = "CNY";
    /** 支付状态(1342)：1 待支付。 */
    private static final int PAY_STATUS_PENDING = 1;

    private final WsOrderMapper wsOrderMapper;
    private final RechargeIdentityMapper identityMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WsOrder create(WsOrder order, String payExpireTime, int paySource) {
        if (wsOrderMapper.insert(order) != 1 || order.getId() == null) {
            throw new JbkException("充值订单创建失败");
        }
        WsPayment payment = new WsPayment();
        payment.setDataStatus(0);
        payment.setCreateBy(order.getUserId());
        payment.setCreateTime(order.getCreateTime());
        payment.setUpdateBy(order.getUserId());
        payment.setUpdateTime(order.getCreateTime());
        payment.setOrderId(order.getId());
        payment.setOrderNo(order.getOrderNo());
        // 金额与订单严格一致，绝不取前端值。
        payment.setPayAmount(order.getOrderAmount());
        payment.setPayStatus(PAY_STATUS_PENDING);
        // 来源由受信任服务端适配器决定（§5.2），不接受任何外部输入。
        payment.setPaySource(paySource);
        payment.setCurrency(CURRENCY_CNY);
        // 不可变付款截止时间，创建后不得修改。
        payment.setPayExpireTime(payExpireTime);
        if (identityMapper.insertPayment(payment) != 1) {
            throw new JbkException("支付单创建失败");
        }
        return order;
    }
}

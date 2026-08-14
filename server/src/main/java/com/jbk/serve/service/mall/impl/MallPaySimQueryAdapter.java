package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import com.jbk.serve.mapper.mall.WsMallOrderMapper;
import com.jbk.serve.mapper.mall.WsMallPaymentMapper;
import com.jbk.serve.service.mall.IMallPayQueryAdapter;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.data.mall.po.WsMallOrder;
import com.jbk.tool.data.mall.po.WsMallPayment;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Pay-Sim 查单适配（E2E-09 S2，仅隔离环境启用）。
 *
 * <p>由 {@code mall.pay-sim.enabled=true} 装配；生产不注册本 Bean，超时关单链路
 * 因此拿不到权威答复而整体停摆——这正是期望行为，宁可订单挂着也不误关。</p>
 *
 * <p>模拟口径：已支付成功→SUCCESS；待支付且未过期→NOTPAY；待支付且已过付款截止
 * →CLOSED；其余状态返回 null（不表态）。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mall.pay-sim.enabled", havingValue = "true")
public class MallPaySimQueryAdapter implements IMallPayQueryAdapter {

    @Autowired
    private WsMallOrderMapper orderMapper;
    @Autowired
    private WsMallPaymentMapper paymentMapper;

    @Override
    public int paySource() {
        return MallEnum.PaySource.PAY_SIM.getValue();
    }

    @Override
    public String queryTradeState(String orderNo) {
        WsMallOrder order = orderMapper.selectByOrderNoIncludingDeleted(orderNo);
        WsMallPayment payment = paymentMapper.selectByOrderNoIncludingDeleted(orderNo);
        if (ObjectUtil.isNull(order) || ObjectUtil.isNull(payment)) {
            return null;
        }
        if (ObjectUtil.equal(payment.getPayStatus(), MallEnum.PayStatus.SUCCESS.getValue())) {
            return MallEnum.TradeState.SUCCESS;
        }
        if (!ObjectUtil.equal(order.getOrderStatus(), MallEnum.OrderStatus.PENDING_PAY.getValue())) {
            return null;
        }
        return DateUtils.time().compareTo(order.getPayExpireTime()) > 0
                ? MallEnum.TradeState.CLOSED
                : MallEnum.TradeState.NOTPAY;
    }
}

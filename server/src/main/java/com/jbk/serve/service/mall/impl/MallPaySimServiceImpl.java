package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import com.jbk.serve.mapper.mall.WsMallOrderMapper;
import com.jbk.serve.mapper.mall.WsMallPaymentMapper;
import com.jbk.serve.service.mall.IMallOrderService;
import com.jbk.serve.service.mall.IMallPayFactService;
import com.jbk.serve.service.mall.IMallPaySimService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.data.mall.po.WsMallOrder;
import com.jbk.tool.data.mall.po.WsMallPayment;
import com.jbk.tool.data.mall.po.WsMallPaymentFact;
import com.jbk.tool.data.mall.vo.MallOrderDetailVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Pay-Sim 模拟支付实现（E2E-09 S2，仅隔离环境启用）。
 *
 * <p>事实键 {@code MALLSIM-<orderNo>} 由订单号确定性派生：同一订单无论点几次支付，
 * 都只会有一条支付事实——重复点击天然幂等，不靠前端防抖。</p>
 *
 * <p>本类不写任何资金或库存字段：置支付单成功在事务A、实销与订单推进在事务B，
 * 两段都在事实处理器里。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "mall.pay-sim.enabled", havingValue = "true")
public class MallPaySimServiceImpl implements IMallPaySimService {

    /** Pay-Sim 事实键前缀：与查单渠道命名空间不重叠。 */
    static final String SIM_EVENT_KEY_PREFIX = "MALLSIM-";
    /** Pay-Sim 交易号前缀：与真实支付方交易号命名空间不重叠。 */
    static final String SIM_TRANSACTION_PREFIX = "MALLSIMTX";

    @Autowired
    private WsMallOrderMapper orderMapper;
    @Autowired
    private WsMallPaymentMapper paymentMapper;
    @Autowired
    private IMallPayFactService payFactService;
    @Autowired
    private IMallOrderService orderService;

    @Override
    public MallOrderDetailVo pay(Long userId, String orderNo) {
        WsMallOrder order = requireOwnPayableOrder(userId, orderNo);
        WsMallPayment payment = paymentMapper.selectByOrderNoIncludingDeleted(orderNo);
        if (ObjectUtil.isNull(payment)) {
            throw new JbkException("支付单缺失，无法支付");
        }
        if (ObjectUtil.equal(order.getOrderStatus(), MallEnum.OrderStatus.CANCELLED.getValue())) {
            throw new JbkException("订单已取消，无法支付");
        }
        int paySource = MallEnum.PaySource.PAY_SIM.getValue();
        int channel = MallEnum.FactChannel.PAY_SIM.getValue();
        String eventKey = SIM_EVENT_KEY_PREFIX + orderNo;
        // 先查后建：同一笔模拟支付只有一个成功时间，重复点击不得各自生成一份"现在"
        WsMallPaymentFact fact = payFactService.findFact(paySource, channel, eventKey);
        if (ObjectUtil.isNull(fact)) {
            // 只在"即将造一笔新的成功事实"这一刻闸付款窗：已有事实的重放不该被过期挡回去
            requireWithinPayWindow(order, payment);
            // 事务A：落事实并（校验通过时）置支付单成功
            fact = payFactService.recordFact(paySource, channel, eventKey, orderNo,
                    MallEnum.TradeState.SUCCESS,
                    SIM_TRANSACTION_PREFIX + orderNo,
                    payment.getPayAmountFen(),
                    DateUtils.time(),
                    MallEnum.VerifyMethod.PAY_SIM_INTERNAL,
                    "{\"channel\":\"pay-sim\",\"orderNo\":\"" + orderNo + "\"}");
        }
        // 事务B：推进（失败只影响本次返回，事实已留存，Worker 会重放）
        payFactService.process(fact.getId());
        return orderService.detailForUser(userId, orderNo);
    }

    @Override
    public MallOrderDetailVo payStatus(Long userId, String orderNo) {
        return orderService.detailForUser(userId, orderNo);
    }

    /**
     * 付款窗闸门：已过付款截止时间就不允许再模拟出一笔新的成功事实。
     *
     * <p>超时关单 Worker 每分钟才扫一轮，且必须先拿到支付方查单 CLOSED 才敢关，所以
     * "订单已过期但还挂在待支付"是常态窗口。这道闸只负责不让新的成功事实在该窗口里
     * 被造出来；订单与支付单的唯一终态仍然由 casSuccess 与 casClose 决定，
     * 本方法不做任何"先查后写"的状态改写。</p>
     */
    private void requireWithinPayWindow(WsMallOrder order, WsMallPayment payment) {
        String deadline = order.getPayExpireTime();
        if (!DateUtils.isCanonicalBusinessTime(deadline)
                || !ObjectUtil.equal(deadline, payment.getPayExpireTime())) {
            throw new JbkException("订单付款截止时间异常，无法支付（请人工核查）");
        }
        if (DateUtils.time().compareTo(deadline) > 0) {
            throw new JbkException("订单已过支付时间，无法支付");
        }
    }

    /** 归属校验；他人订单一律按不存在处理（可支付状态的判定在调用处）。 */
    private WsMallOrder requireOwnPayableOrder(Long userId, String orderNo) {
        WsMallOrder order = orderMapper.selectByOrderNoIncludingDeleted(orderNo);
        if (ObjectUtil.isNull(order) || !ObjectUtil.equal(order.getUserId(), userId)) {
            throw new JbkException("订单不存在");
        }
        return order;
    }
}

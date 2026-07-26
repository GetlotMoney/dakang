package com.jbk.serve.service.mini.impl;

import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.service.mini.recharge.IRechargePayFactService;
import com.jbk.serve.service.mini.recharge.IRechargePaySourceAdapter;
import com.jbk.serve.service.mini.recharge.RechargePayStatus;
import com.jbk.tool.data.mini.bo.MiniPaySimBo;
import com.jbk.tool.data.mini.vo.MiniPaySimVo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsPaymentEvent;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pay-Sim「继续支付」的可支付性前置守卫。
 *
 * <p>本类的全部立场：<b>不可支付的单必须在造事实之前就被拒</b>。
 * 没有这层守卫时，一个已完成/已取消/已超期的订单也能落一条 SUCCESS 事实，
 * 再由事务 A 判超时把订单推进到 6 转人工——钱没真收到，订单却坏了。
 * 因此每条拒绝用例都必须同时断言「一条支付事实都没落、处理器一次都没被调」。</p>
 */
class MiniPaySimPayableGuardTest {

    private static final Long ME = 9L;
    private static final long ORDER_ID = 777L;
    private static final long PAYMENT_ID = 888L;
    private static final String ORDER_NO = "RC0000000000000000000000000001";
    private static final long AMOUNT = 9900L;
    /** 远期截止时间：真实时钟怎么走都在窗口内。 */
    private static final String FUTURE_EXPIRE = "20991231235959";
    /** 已过去的截止时间：真实时钟怎么走都已超期。 */
    private static final String PAST_EXPIRE = "20200101000000";

    private RechargeIdentityMapper identityMapper;
    private RechargeCreditMapper creditMapper;
    private IRechargePayFactService factService;
    private MiniPaySimServiceImpl service;

    private WsOrder order;
    private WsPayment payment;

    @BeforeEach
    void setup() {
        identityMapper = Mockito.mock(RechargeIdentityMapper.class);
        creditMapper = Mockito.mock(RechargeCreditMapper.class);
        factService = Mockito.mock(IRechargePayFactService.class);
        IRechargePaySourceAdapter adapter = () -> IRechargePaySourceAdapter.PAY_SIM;
        service = new MiniPaySimServiceImpl(identityMapper, creditMapper, factService, adapter);

        order = order(RechargePayStatus.ORDER_PENDING);
        payment = payment(RechargePayStatus.PAY_PENDING, FUTURE_EXPIRE);
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(ORDER_NO))
                .thenAnswer(invocation -> List.of(order));
        when(identityMapper.selectPaymentsByOrderIdIncludingDeleted(ORDER_ID))
                .thenAnswer(invocation -> List.of(payment));
    }

    // ================= 放行 =================

    /** 唯一放行组合：payment 1/order 1 且未过截止时间。 */
    @Test
    void pendingOrderWithinWindowIsAccepted() {
        WsPaymentEvent saved = new WsPaymentEvent();
        saved.setId(1234L);
        when(creditMapper.selectEventByProviderKey(anyInt(), anyInt(), anyString()))
                .thenReturn(null, saved);
        when(factService.process(1234L))
                .thenReturn(new IRechargePayFactService.Outcome("CREDITED", "已入账"));

        MiniPaySimVo vo = service.pay(bo(), ME);

        assertEquals("CREDITED", vo.getResultCode());
        assertEquals(ORDER_NO, vo.getOrderNo());
        verify(factService).process(1234L);
    }

    // ================= 订单终态：拒因必须互相可区分 =================

    @Test
    void finishedOrderIsRejectedWithItsOwnReason() {
        order.setOrderStatus(RechargePayStatus.ORDER_FINISHED);
        payment.setPayStatus(RechargePayStatus.PAY_SUCCESS);
        assertTrue(reject().contains("已完成"));
        assertNoFactWritten();
    }

    @Test
    void cancelledOrderIsRejectedWithItsOwnReason() {
        order.setOrderStatus(RechargePayStatus.ORDER_CANCELLED);
        payment.setPayStatus(RechargePayStatus.PAY_CLOSED);
        assertTrue(reject().contains("取消"));
        assertNoFactWritten();
    }

    @Test
    void abnormalOrderIsRejectedWithItsOwnReason() {
        order.setOrderStatus(RechargePayStatus.ORDER_ABNORMAL);
        payment.setPayStatus(RechargePayStatus.PAY_SUCCESS);
        assertTrue(reject().contains("人工对账"));
        assertNoFactWritten();
    }

    /**
     * 4/5/6 三种拒因必须两两不同。
     * 合并成一句"不可支付"会让用户对一个永远付不成的单反复重试，
     * 也会让排障时分不清是订单已完成还是已被取消。
     */
    @Test
    void terminalOrderReasonsAreMutuallyDistinguishable() {
        order.setOrderStatus(RechargePayStatus.ORDER_FINISHED);
        String finished = reject();
        order.setOrderStatus(RechargePayStatus.ORDER_CANCELLED);
        String cancelled = reject();
        order.setOrderStatus(RechargePayStatus.ORDER_ABNORMAL);
        String abnormal = reject();

        assertNotEquals(finished, cancelled);
        assertNotEquals(finished, abnormal);
        assertNotEquals(cancelled, abnormal);
        assertNoFactWritten();
    }

    @Test
    void paidOrderPendingCreditIsRejected() {
        order.setOrderStatus(RechargePayStatus.ORDER_PAID);
        payment.setPayStatus(RechargePayStatus.PAY_SUCCESS);
        assertTrue(reject().contains("入账"));
        assertNoFactWritten();
    }

    // ================= 支付单态 =================

    /** order 仍为 1 但支付单已成功：钱已经收到了，绝不能再造第二条事实。 */
    @Test
    void successfulPaymentUnderPendingOrderIsRejected() {
        payment.setPayStatus(RechargePayStatus.PAY_SUCCESS);
        assertTrue(reject().contains("支付成功事实"));
        assertNoFactWritten();
    }

    @Test
    void closedPaymentUnderPendingOrderIsRejected() {
        payment.setPayStatus(RechargePayStatus.PAY_CLOSED);
        assertTrue(reject().contains("支付已关闭"));
        assertNoFactWritten();
    }

    /** 未登记的支付单状态 fail-closed：不认识 ≠ 可以付。 */
    @Test
    void unknownPaymentStatusIsRejected() {
        payment.setPayStatus(3);
        assertTrue(reject().contains("状态异常"));
        assertNoFactWritten();
    }

    /** 未登记的订单状态同样 fail-closed。 */
    @Test
    void unknownOrderStatusIsRejected() {
        order.setOrderStatus(8);
        assertTrue(reject().contains("不可支付"));
        assertNoFactWritten();
    }

    // ================= 付款截止时间 =================

    @Test
    void expiredPaymentWindowIsRejectedBeforeAnyFact() {
        payment.setPayExpireTime(PAST_EXPIRE);
        String message = reject();
        assertTrue(message.contains("付款截止时间"));
        assertTrue(message.contains(PAST_EXPIRE));
        assertNoFactWritten();
    }

    /** 截止时间缺失时无从判断按时与否，只能拒——不得默认放行。 */
    @Test
    void missingPayExpireTimeIsRejected() {
        payment.setPayExpireTime(null);
        assertTrue(reject().contains("付款截止时间缺失"));
        assertNoFactWritten();
    }

    // ================= 辅助 =================

    private String reject() {
        return assertThrows(JbkException.class, () -> service.pay(bo(), ME)).getMessage();
    }

    /** 拒绝路径的核心断言：既没落事实，也没触发事实处理器。 */
    private void assertNoFactWritten() {
        verify(creditMapper, never()).insertEvent(anyString(), anyLong(), anyLong(), anyInt(), anyInt(),
                anyString(), anyString(), anyString(), anyLong(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), anyString(), anyInt());
        verify(factService, never()).process(any());
    }

    private static MiniPaySimBo bo() {
        MiniPaySimBo bo = new MiniPaySimBo();
        bo.setOrderNo(ORDER_NO);
        return bo;
    }

    private static WsOrder order(int orderStatus) {
        WsOrder o = new WsOrder();
        o.setId(ORDER_ID);
        o.setOrderNo(ORDER_NO);
        o.setOrderType(2);
        o.setUserId(ME);
        o.setOrderAmount(AMOUNT);
        o.setPayWay(1);
        o.setOrderStatus(orderStatus);
        o.setDataStatus(0);
        return o;
    }

    private static WsPayment payment(int payStatus, String payExpireTime) {
        WsPayment p = new WsPayment();
        p.setId(PAYMENT_ID);
        p.setOrderId(ORDER_ID);
        p.setOrderNo(ORDER_NO);
        p.setPayAmount(AMOUNT);
        p.setPayStatus(payStatus);
        p.setPaySource(IRechargePaySourceAdapter.PAY_SIM);
        p.setCurrency("CNY");
        p.setPayExpireTime(payExpireTime);
        p.setDataStatus(0);
        return p;
    }
}

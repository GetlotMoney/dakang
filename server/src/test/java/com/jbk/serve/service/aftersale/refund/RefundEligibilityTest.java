package com.jbk.serve.service.aftersale.refund;

import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部退款准入判定单测（E2E-04 包B）。
 *
 * <p>方法学：每条否定用例都从<b>一个能通过的基线</b>出发，只拨坏一项。
 * 若改为「自造一个到处都不合法的对象」，任何一道闸都能让它抛出，
 * 于是删掉被测的那道闸测试照样绿——那是假绿的典型形状。</p>
 *
 * <p>每条否定用例都断言<b>具体错因</b>而非只断言抛出：四道闸的错因彼此不同，
 * 只断言 assertThrows 会让「闸 A 被删、闸 B 顶上」这种漂移无法被发现。</p>
 */
class RefundEligibilityTest {

    private static final long ORDER_ID = 501L;
    private static final String ORDER_NO = "WO20260729000001";
    private static final long PAID_FEN = 10000L;

    /** 基线：已付款(2) + 异常待补偿(6) + 无入账流水 + 无发卡 —— 四闸全过。 */
    private RefundEligibility.Evidence baseline() {
        return new RefundEligibility.Evidence(order(6), payment(2, PAID_FEN), false, false);
    }

    private WsOrder order(int status) {
        WsOrder o = new WsOrder();
        o.setId(ORDER_ID);
        o.setOrderNo(ORDER_NO);
        o.setOrderType(2);
        o.setOrderAmount(PAID_FEN);
        o.setPayWay(1);
        o.setOrderStatus(status);
        o.setDataStatus(0);
        return o;
    }

    private WsPayment payment(int payStatus, Long amount) {
        WsPayment p = new WsPayment();
        p.setId(9001L);
        p.setOrderId(ORDER_ID);
        p.setOrderNo(ORDER_NO);
        p.setPayStatus(payStatus);
        p.setPayAmount(amount);
        p.setCurrency("CNY");
        p.setDataStatus(0);
        return p;
    }

    private String rejectReason(RefundEligibility.Evidence evidence) {
        return assertThrows(JbkException.class,
                () -> RefundEligibility.requireUnsettledFullRefund(evidence)).getMessage();
    }

    // ==================== 基线与放行金额 ====================

    /** 基线必须通过，且返回的是原支付金额——本路径只支持全额，返回值即「退多少」的唯一出处。 */
    @Test
    void unsettledAbnormalOrderIsRefundableForFullPaidAmount() {
        assertEquals(PAID_FEN, RefundEligibility.requireUnsettledFullRefund(baseline()));
    }

    // ==================== 四道闸逐条 ====================

    /** 闸①：没收到钱就退款是凭空出账。 */
    @Test
    void unpaidPaymentIsRejected() {
        for (int payStatus : new int[] { 1, 3, 4 }) {
            String reason = rejectReason(new RefundEligibility.Evidence(
                    order(6), payment(payStatus, PAID_FEN), false, false));
            assertTrue(reason.contains("不是支付成功状态"), "payStatus=" + payStatus + " 实际=" + reason);
        }
    }

    /**
     * 闸②：只认异常待补偿(6)。
     * 已完成(4) 是最危险的一个——它意味着权益已经正常发放，此时全额退款即「钱货两清后再退钱」。
     */
    @Test
    void nonAbnormalOrderStatusIsRejected() {
        for (int status : new int[] { 1, 2, 3, 4, 5, 7, 8 }) {
            String reason = rejectReason(new RefundEligibility.Evidence(
                    order(status), payment(2, PAID_FEN), false, false));
            assertTrue(reason.contains("不是异常待补偿状态"), "orderStatus=" + status + " 实际=" + reason);
        }
    }

    /** 闸③：入账流水是权益已发的直接证据。 */
    @Test
    void existingRechargeFlowIsRejected() {
        String reason = rejectReason(new RefundEligibility.Evidence(
                order(6), payment(2, PAID_FEN), true, false));
        assertTrue(reason.contains("已存在充值入账流水"), "实际=" + reason);
        assertTrue(reason.contains("包D"), "错因应指明正确去向是权益批次冲正，否则运营会反复重试");
    }

    /**
     * 闸④：已建出水卡同样是权益已发。
     * 与闸③分开断言是关键——首购路径下两者同事务写入，只留一道闸时，
     * 任一侧被补录或人工修数都能骗过判定，代价是给一张已发出的卡全额退款。
     */
    @Test
    void issuedCardIsRejectedEvenWithoutRechargeFlow() {
        String reason = rejectReason(new RefundEligibility.Evidence(
                order(6), payment(2, PAID_FEN), false, true));
        assertTrue(reason.contains("已发出水卡"), "实际=" + reason);
    }

    // ==================== 归属核验 ====================

    @Test
    void mismatchedPaymentOwnershipIsRejectedBeforeBusinessGates() {
        WsPayment foreign = payment(2, PAID_FEN);
        foreign.setOrderId(ORDER_ID + 1);
        String reason = rejectReason(new RefundEligibility.Evidence(order(6), foreign, false, false));
        assertTrue(reason.contains("归属错位"), "实际=" + reason);
    }

    @Test
    void mismatchedOrderNoIsRejected() {
        WsPayment other = payment(2, PAID_FEN);
        other.setOrderNo("WO-ANOTHER-ORDER");
        String reason = rejectReason(new RefundEligibility.Evidence(order(6), other, false, false));
        assertTrue(reason.contains("商户单号不一致"), "实际=" + reason);
    }

    @Test
    void missingEvidenceIsRejected() {
        assertTrue(rejectReason(null).contains("证据缺失"));
        assertTrue(rejectReason(new RefundEligibility.Evidence(null, payment(2, PAID_FEN), false, false))
                .contains("证据缺失"));
        assertTrue(rejectReason(new RefundEligibility.Evidence(order(6), null, false, false))
                .contains("证据缺失"));
    }

    @Test
    void nonPositivePaidAmountIsRejected() {
        for (Long amount : new Long[] { null, 0L, -1L }) {
            String reason = rejectReason(new RefundEligibility.Evidence(
                    order(6), payment(2, amount), false, false));
            assertTrue(reason.contains("原支付金额非法"), "amount=" + amount + " 实际=" + reason);
        }
    }

    @Test
    void refundSourceMustEqualOriginalPaymentSource() {
        WsPayment simPayment = payment(2, PAID_FEN);
        simPayment.setPaySource(2);
        RefundEligibility.requireMatchingSource(simPayment, 2);

        String reason = assertThrows(JbkException.class,
                () -> RefundEligibility.requireMatchingSource(simPayment, 1)).getMessage();
        assertTrue(reason.contains("退款来源与原支付来源不一致"), "实际=" + reason);
        assertThrows(JbkException.class,
                () -> RefundEligibility.requireMatchingSource(simPayment, null));
    }

    // ==================== 累计封顶 ====================

    @Test
    void refundWithinRemainingIsAllowed() {
        RefundEligibility.requireWithinPaidAmount(10000L, 0L, 10000L);
        RefundEligibility.requireWithinPaidAmount(10000L, 4000L, 6000L);
        RefundEligibility.requireWithinPaidAmount(10000L, 9999L, 1L);
    }

    /** 边界必须是「恰好用完可以、多一分不行」，而不是差一位的宽松。 */
    @Test
    void refundExceedingRemainingByOneFenIsRejected() {
        String reason = assertThrows(JbkException.class,
                () -> RefundEligibility.requireWithinPaidAmount(10000L, 4000L, 6001L)).getMessage();
        assertTrue(reason.contains("超出原支付金额"), "实际=" + reason);
        assertTrue(reason.contains("剩余 6000"), "错因必须给出剩余额度，否则运营无从决定改退多少：" + reason);
    }

    @Test
    void secondFullRefundAfterASuccessfulOneIsRejected() {
        String reason = assertThrows(JbkException.class,
                () -> RefundEligibility.requireWithinPaidAmount(10000L, 10000L, 10000L)).getMessage();
        assertTrue(reason.contains("剩余 0"), "实际=" + reason);
    }

    @Test
    void illegalCapInputsAreRejected() {
        assertTrue(assertThrows(JbkException.class,
                () -> RefundEligibility.requireWithinPaidAmount(0L, 0L, 100L)).getMessage().contains("原支付金额非法"));
        assertTrue(assertThrows(JbkException.class,
                () -> RefundEligibility.requireWithinPaidAmount(10000L, 0L, 0L)).getMessage().contains("必须为正"));
        assertTrue(assertThrows(JbkException.class,
                () -> RefundEligibility.requireWithinPaidAmount(10000L, -1L, 100L)).getMessage().contains("账本异常"));
    }

    // ==================== 退款号派生 ====================

    /**
     * 确定性是资金安全属性而非风格：含随机成分时，「写库成功但请求发送超时」的重试会用新号再发一次，
     * 而支付机构按 out_refund_no 幂等——两个号即两笔退款，用户收到双份钱。
     */
    @Test
    void refundNoIsDeterministicPerAfterSaleAction() {
        assertEquals(RefundNo.derive(88L), RefundNo.derive(88L));
        assertTrue(!RefundNo.derive(88L).equals(RefundNo.derive(89L)));
        assertEquals(32, RefundNo.derive(88L).length(), "必须正好填满 REFUND_NO 列宽 varchar(32)");
        assertTrue(RefundNo.derive(88L).startsWith(RefundNo.PREFIX));
    }

    @Test
    void refundNoRejectsIllegalAfterSaleId() {
        for (Long id : new Long[] { null, 0L, -1L }) {
            assertTrue(assertThrows(JbkException.class, () -> RefundNo.derive(id))
                    .getMessage().contains("售后动作ID非法"));
        }
    }
}

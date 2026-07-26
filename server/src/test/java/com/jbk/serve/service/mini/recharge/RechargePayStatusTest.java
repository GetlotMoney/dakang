package com.jbk.serve.service.mini.recharge;

import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsPaymentEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.jbk.serve.service.mini.recharge.RechargePayStatus.CLOSED;
import static com.jbk.serve.service.mini.recharge.RechargePayStatus.NOTPAY;
import static com.jbk.serve.service.mini.recharge.RechargePayStatus.P_PENDING;
import static com.jbk.serve.service.mini.recharge.RechargePayStatus.P_PROCESSED;
import static com.jbk.serve.service.mini.recharge.RechargePayStatus.P_PROCESSING;
import static com.jbk.serve.service.mini.recharge.RechargePayStatus.P_RECONCILIATION;
import static com.jbk.serve.service.mini.recharge.RechargePayStatus.P_RETRY_WAIT;
import static com.jbk.serve.service.mini.recharge.RechargePayStatus.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pay-status 精确状态矩阵（§9.1）。事件写入属 L2-T，这里用构造事实覆盖全部分支。
 */
class RechargePayStatusTest {

    private static final String EXPIRE = "20260722103000";

    private WsOrder order(int status) {
        WsOrder o = new WsOrder();
        o.setOrderStatus(status);
        return o;
    }

    private WsPayment payment(int status) {
        WsPayment p = new WsPayment();
        p.setPayStatus(status);
        p.setPayExpireTime(EXPIRE);
        return p;
    }

    private WsPaymentEvent evt(String state, int processing, String successTime) {
        WsPaymentEvent e = new WsPaymentEvent();
        e.setTradeState(state);
        e.setProcessingStatus(processing);
        e.setPaySuccessTime(successTime);
        return e;
    }

    private RechargePayStatus.Resolved resolve(int pay, int ord, List<WsPaymentEvent> events, long flows) {
        return RechargePayStatus.resolve(order(ord), payment(pay), events, flows);
    }

    // payment 1 / order 1 待支付
    @Test
    void pendingBranch() {
        assertEquals("WAITING_PAYMENT", resolve(1, 1, List.of(), 0).statusCode());
        // 允许已处理的 NOTPAY
        assertTrue(resolve(1, 1, List.of(evt(NOTPAY, P_PROCESSED, null)), 0).ok());
        // 未收敛的 NOTPAY / 任何 SUCCESS / CLOSED / 有流水 → mismatch
        assertFalse(resolve(1, 1, List.of(evt(NOTPAY, P_PENDING, null)), 0).ok());
        assertFalse(resolve(1, 1, List.of(evt(SUCCESS, P_PENDING, "20260722100000")), 0).ok());
        assertFalse(resolve(1, 1, List.of(evt(CLOSED, P_PROCESSED, null)), 0).ok());
        assertFalse(resolve(1, 1, List.of(), 1).ok(), "待支付不得有充值流水");
    }

    // payment 4 / order 5 已关闭
    @Test
    void closedBranch() {
        assertEquals("CLOSED", resolve(4, 5, List.of(evt(CLOSED, P_PROCESSED, null)), 0).statusCode());
        // 可保留更早的 PROCESSED NOTPAY
        assertTrue(resolve(4, 5, List.of(evt(NOTPAY, P_PROCESSED, null), evt(CLOSED, P_PROCESSED, null)), 0).ok());
        assertFalse(resolve(4, 5, List.of(), 0).ok(), "缺关闭事实");
        assertFalse(resolve(4, 5, List.of(evt(CLOSED, P_PENDING, null)), 0).ok(), "关闭事实未处理");
        assertFalse(resolve(4, 5, List.of(evt(CLOSED, P_PROCESSED, null),
                evt(SUCCESS, P_PROCESSED, "20260722100000")), 0).ok(), "不得同时有成功事实");
        assertFalse(resolve(4, 5, List.of(evt(CLOSED, P_PROCESSED, null)), 1).ok(), "不得有流水");
    }

    // payment 2 / order 2 已支付待入账
    @Test
    void paidPendingCreditBranch() {
        assertEquals("PAID_CREDIT_PENDING",
                resolve(2, 2, List.of(evt(SUCCESS, P_PENDING, "20260722100000")), 0).statusCode());
        assertTrue(resolve(2, 2, List.of(evt(SUCCESS, P_PROCESSING, EXPIRE)), 0).ok(), "等于截止时间算按时");
        // 超时支付不算按时 → 缺按时成功事实
        assertFalse(resolve(2, 2, List.of(evt(SUCCESS, P_PENDING, "20260722103001")), 0).ok());
        // 已处理/待对账的成功事实不该出现在此组合
        assertFalse(resolve(2, 2, List.of(evt(SUCCESS, P_PROCESSED, "20260722100000")), 0).ok());
        assertFalse(resolve(2, 2, List.of(evt(SUCCESS, P_RECONCILIATION, "20260722100000")), 0).ok());
        assertFalse(resolve(2, 2, List.of(evt(SUCCESS, P_PENDING, "20260722100000")), 1).ok(), "尚未入账不得有流水");
    }

    // payment 2 / order 4 已完成
    @Test
    void completedBranch() {
        List<WsPaymentEvent> ok = List.of(evt(SUCCESS, P_PROCESSED, "20260722100000"));
        assertEquals("COMPLETED", resolve(2, 4, ok, 1).statusCode());
        assertFalse(resolve(2, 4, ok, 0).ok(), "已完成必须恰好一条流水");
        assertFalse(resolve(2, 4, ok, 2).ok(), "重复入账流水必须 mismatch");
        assertFalse(resolve(2, 4, List.of(evt(SUCCESS, P_PENDING, "20260722100000")), 1).ok(),
                "缺已处理的成功事实");
        assertFalse(resolve(2, 4, List.of(evt(SUCCESS, P_PROCESSED, "20260722103001")), 1).ok(),
                "超时成功不得判为已完成");
        assertFalse(resolve(2, 4, List.of(evt(SUCCESS, P_PROCESSED, "20260722100000"),
                evt(SUCCESS, P_RECONCILIATION, "20260722100000")), 1).ok(), "混入待对账必须 mismatch");
    }

    // payment 2 / order 6 异常待对账
    @Test
    void abnormalBranch() {
        // 全部待对账
        assertEquals("RECONCILIATION_REQUIRED",
                resolve(2, 6, List.of(evt(SUCCESS, P_RECONCILIATION, "20260722100000")), 0).statusCode());
        // 已授权恢复组：同一非空组键 + 审批人 + 只处于 RETRY_WAIT/PROCESSING
        WsPaymentEvent a = evt(SUCCESS, P_RETRY_WAIT, "20260722100000");
        a.setRecoveryApprovalGroupKey("G1");
        a.setRecoveryApprovedBy(1L);
        assertTrue(resolve(2, 6, List.of(a), 0).ok());
        // 缺审批人 → 拒绝
        WsPaymentEvent noApprover = evt(SUCCESS, P_RETRY_WAIT, "20260722100000");
        noApprover.setRecoveryApprovalGroupKey("G1");
        assertFalse(resolve(2, 6, List.of(noApprover), 0).ok());
        // 混用组键 → 拒绝
        WsPaymentEvent b = evt(SUCCESS, P_RETRY_WAIT, "20260722100000");
        b.setRecoveryApprovalGroupKey("G2");
        b.setRecoveryApprovedBy(1L);
        assertFalse(resolve(2, 6, List.of(a, b), 0).ok());
        // 留有未批准的 PENDING → 拒绝
        assertFalse(resolve(2, 6, List.of(a, evt(SUCCESS, P_PENDING, "20260722100000")), 0).ok());
        // 已有完成流水 → 拒绝
        assertFalse(resolve(2, 6, List.of(evt(SUCCESS, P_RECONCILIATION, "20260722100000")), 1).ok());
    }

    /**
     * 恢复组授权闸门：组键为空 = 没走过审批，必须拒绝。
     *
     * <p>原用例只覆盖了「有组键但缺审批人」和「组键不同」，从没构造过空组键，
     * 于是把 {@code return false} 翻成 {@code return true}、或把组键比较改成"非空才比较"
     * （本项目真实踩过的缺陷形态）都不会红。放行的后果是纯粹越权：
     * 任何没走审批流的异常成功事实——补数据脚本、重放的回调——都能被解释成
     * 「同一已授权恢复组」，把本该冻结待查的订单送进恢复路径。</p>
     */
    @Test
    void recoveryGroupRequiresNonBlankKeyOnEveryEvent() {
        // 组键为 null / 空串：即便审批人齐备也必须拒绝
        for (String blank : new String[] { null, "" }) {
            WsPaymentEvent e = evt(SUCCESS, P_RETRY_WAIT, "20260722100000");
            e.setRecoveryApprovalGroupKey(blank);
            e.setRecoveryApprovedBy(1L);
            assertFalse(resolve(2, 6, List.of(e), 0).ok(),
                    "空授权组键必须拒绝，组键=" + blank);
        }
        // 首条有组键、后续为 null：不得因为"非空才比较"而被放行
        WsPaymentEvent head = evt(SUCCESS, P_RETRY_WAIT, "20260722100000");
        head.setRecoveryApprovalGroupKey("G1");
        head.setRecoveryApprovedBy(1L);
        WsPaymentEvent tailNullKey = evt(SUCCESS, P_RETRY_WAIT, "20260722100000");
        tailNullKey.setRecoveryApprovedBy(1L);
        assertFalse(resolve(2, 6, List.of(head, tailNullKey), 0).ok(),
                "组内混入无组键事件必须拒绝");
    }

    // payment 2 / order 7 退款合同未启用：fail-closed，绝不拼成成功
    @Test
    void refundNotEnabled() {
        RechargePayStatus.Resolved r = resolve(2, 7, List.of(), 0);
        assertFalse(r.ok());
        assertEquals("REFUND_CONTRACT_NOT_ENABLED", r.statusCode());
    }

    // 其余组合（含充值单 order 3/8）一律 mismatch；禁止数值序比较
    @Test
    void otherCombinationsAreMismatch() {
        assertFalse(resolve(2, 3, List.of(), 0).ok());
        assertFalse(resolve(2, 8, List.of(), 0).ok());
        assertFalse(resolve(1, 2, List.of(), 0).ok());
        assertFalse(resolve(2, 1, List.of(), 0).ok());
        assertFalse(resolve(4, 1, List.of(), 0).ok());
        // order 4 > order 2 的数值序若被误用会把未支付判成完成——此处确保不会
        assertFalse(resolve(1, 4, List.of(), 1).ok());
    }

    // 未登记的 TRADE_STATE 只留证转人工，不参与解释
    @Test
    void unknownTradeStateRejected() {
        assertFalse(resolve(1, 1, List.of(evt("REVOKED", P_PROCESSED, null)), 0).ok());
    }

    // 处理态聚合优先级
    @Test
    void processingAggregationPriority() {
        assertEquals("WAITING_PAYMENT", RechargePayStatus.aggregateProcessing(List.of()));
        assertEquals("RECONCILIATION_REQUIRED", RechargePayStatus.aggregateProcessing(
                List.of(evt(SUCCESS, P_PROCESSED, null), evt(SUCCESS, P_RECONCILIATION, null))));
        assertEquals("PROCESSING", RechargePayStatus.aggregateProcessing(
                List.of(evt(SUCCESS, P_PROCESSED, null), evt(SUCCESS, P_PROCESSING, null))));
        assertEquals("RETRY_WAIT", RechargePayStatus.aggregateProcessing(
                List.of(evt(SUCCESS, P_PENDING, null), evt(SUCCESS, P_RETRY_WAIT, null))));
        assertEquals("PENDING", RechargePayStatus.aggregateProcessing(
                List.of(evt(SUCCESS, P_PROCESSED, null), evt(SUCCESS, P_PENDING, null))));
        assertEquals("PROCESSED", RechargePayStatus.aggregateProcessing(
                List.of(evt(SUCCESS, P_PROCESSED, null))));
    }
}

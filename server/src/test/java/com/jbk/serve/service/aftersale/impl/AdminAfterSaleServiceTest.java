package com.jbk.serve.service.aftersale.impl;

import com.jbk.serve.service.aftersale.refund.IRefundSourceAdapter;
import com.jbk.serve.service.aftersale.refund.IEntitlementRefundTxService;
import com.jbk.serve.service.aftersale.refund.IRefundRequestTxService;
import com.jbk.serve.service.aftersale.IResendFulfillmentTxService;
import com.jbk.serve.mapper.aftersale.WsRefundMapper;
import com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper;
import com.jbk.serve.service.aftersale.IAfterSaleActionTxService;
import com.jbk.serve.service.aftersale.IWaterAbnormalReconcileTxService;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.ActionStatus;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.ActionType;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.SourceType;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.StrategyCode;
import com.jbk.tool.data.aftersale.bo.AfterSaleExecuteBo;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.CannotAcquireLockException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PC 售后编排层（{@link AdminAfterSaleServiceImpl}）单测。
 *
 * <p>本层<b>不动钱</b>：资金写入、CAS、封顶全在事务层，已由 {@code AfterSaleRefundTxDbTest}
 * 用真库真事务覆盖。这里要钉住的是编排层独有的三件事——
 * ① 进事务前的四道准入闸；② 认领落空时不得继续执行；③ 执行失败后的终局分流。
 * 故用 Mockito 而非真库：把事务层换成桩，才能精确断言「编排层调了什么、没调什么」。</p>
 *
 * <p>刻意<b>不</b>在这里重测金额与状态机——那样只会得到一份与事务层重复且更弱的断言，
 * 且两份判定一旦漂移，弱的那份会先绿。</p>
 */
class AdminAfterSaleServiceTest {

    private static final long ACTION_ID = 501L;
    private static final String AFTER_SALE_NO = "AS0000000000000000000000000001";
    private static final Long ADMIN = 9L;

    private WsAfterSaleActionMapper actionMapper;
    private IAfterSaleActionTxService actionTx;
    private IWaterAbnormalReconcileTxService waterTx;
    private AdminAfterSaleServiceImpl service;
    private IRefundRequestTxService refundRequestTx;
    private IRefundSourceAdapter refundAdapter;
    private IResendFulfillmentTxService resendTx;
    private WsRefundMapper refundMapper;
    private IEntitlementRefundTxService entitlementRefundTx;

    @BeforeEach
    void setup() {
        actionMapper = Mockito.mock(WsAfterSaleActionMapper.class);
        actionTx = Mockito.mock(IAfterSaleActionTxService.class);
        waterTx = Mockito.mock(IWaterAbnormalReconcileTxService.class);
        // 包B/包C 给编排层加了三条能力（退款请求、退款来源、补送生成）。
        // 本类只测售后执行编排，这三个给 mock 即可——但必须显式给出，
        // 让「构造器又多了一个资金能力」这件事在测试里可见，而不是被通配符吞掉。
        refundRequestTx = Mockito.mock(IRefundRequestTxService.class);
        refundAdapter = Mockito.mock(IRefundSourceAdapter.class);
        resendTx = Mockito.mock(IResendFulfillmentTxService.class);
        refundMapper = Mockito.mock(WsRefundMapper.class);
        // 包D-5 再加一条：已入账充值退款的受理（锁批次 + 登记动作）。同样显式给出而不是通配。
        entitlementRefundTx = Mockito.mock(IEntitlementRefundTxService.class);
        service = new AdminAfterSaleServiceImpl(actionMapper, actionTx, waterTx,
                refundMapper, refundRequestTx, refundAdapter, resendTx, entitlementRefundTx);
        // 默认认领成功，个别用例再覆盖
        when(actionTx.claimIndependent(anyLong(), any(), anyLong(), anyString())).thenReturn(true);
    }

    private WsAfterSaleAction action(ActionStatus status, String strategyCode, int dataStatus) {
        WsAfterSaleAction a = new WsAfterSaleAction();
        a.setId(ACTION_ID);
        a.setAfterSaleNo(AFTER_SALE_NO);
        a.setSourceType(SourceType.DELIVERY_APPEAL.getValue());
        a.setSourceId(77L);
        a.setOrderId(5L);
        a.setUserId(1L);
        a.setCardId(1L);
        a.setActionType(ActionType.CARD_COMPENSATE.getValue());
        a.setStrategyCode(strategyCode);
        a.setActionStatus(status.getValue());
        a.setVersion(1);
        a.setRetryCount(0);
        a.setDataStatus(dataStatus);
        return a;
    }

    private AfterSaleExecuteBo bo(String afterSaleNo) {
        AfterSaleExecuteBo b = new AfterSaleExecuteBo();
        b.setId(ACTION_ID);
        b.setAfterSaleNo(afterSaleNo);
        return b;
    }

    private void seedAction(WsAfterSaleAction a) {
        when(actionMapper.selectByIdIncludingDeleted(ACTION_ID)).thenReturn(a);
    }

    /** 四道准入闸的共用断言：抛出 + 绝不进入认领与资金事务。 */
    private void assertRejectedBeforeClaim(String expectedReason) {
        JbkException ex = assertThrows(JbkException.class, () -> service.execute(bo(AFTER_SALE_NO), ADMIN));
        assertTrue(ex.getMessage().contains(expectedReason), "实际=" + ex.getMessage());
        verify(actionTx, never()).claimIndependent(anyLong(), any(), anyLong(), anyString());
        verify(actionTx, never()).executeInTx(anyLong(), anyLong(), anyString());
    }

    // ==================== execute：四道准入闸 ====================

    @Test
    void missingActionIsRejectedBeforeClaim() {
        when(actionMapper.selectByIdIncludingDeleted(ACTION_ID)).thenReturn(null);
        assertRejectedBeforeClaim("售后动作不存在");
    }

    @Test
    void logicallyDeletedActionIsRejectedBeforeClaim() {
        seedAction(action(ActionStatus.PENDING, StrategyCode.PRODUCT_ONLY.name(), 1));
        assertRejectedBeforeClaim("已被逻辑删除");
    }

    /**
     * 售后号与 ID 必须同指一行。这道闸防的是「运营开着过期列表点执行」：
     * 列表刷新前后同一行的 ID 可能已指向另一笔返还，只认 ID 就会执行错的那笔。
     */
    @Test
    void mismatchedAfterSaleNoIsRejectedBeforeClaim() {
        seedAction(action(ActionStatus.PENDING, StrategyCode.PRODUCT_ONLY.name(), 0));
        JbkException ex = assertThrows(JbkException.class,
                () -> service.execute(bo("AS-SOME-OTHER-NO"), ADMIN));
        assertTrue(ex.getMessage().contains("售后号与动作ID不匹配"), "实际=" + ex.getMessage());
        verify(actionTx, never()).claimIndependent(anyLong(), any(), anyLong(), anyString());
    }

    /** 补送/驳回不产生资金返还，不该从执行入口出账。 */
    @Test
    void nonAssetStrategyIsRejectedBeforeClaim() {
        seedAction(action(ActionStatus.PENDING, StrategyCode.RESEND.name(), 0));
        assertRejectedBeforeClaim("不产生资金返还");
    }

    /** 终态行不可执行（状态机预检；真正的判定仍在 claim 的 CAS 里）。 */
    @Test
    void terminalStatusIsRejectedBeforeClaim() {
        seedAction(action(ActionStatus.SUCCESS, StrategyCode.PRODUCT_ONLY.name(), 0));
        assertRejectedBeforeClaim("当前状态不可执行");
    }

    // ==================== execute：认领与执行的衔接 ====================

    /**
     * 认领落空必须<b>停在这里</b>：认领是并发裁决点，落空意味着别人已经拿走这笔。
     * 继续调 executeInTx 就是两个线程同时进资金段。
     */
    @Test
    void failedClaimStopsBeforeMoneyPath() {
        seedAction(action(ActionStatus.PENDING, StrategyCode.PRODUCT_ONLY.name(), 0));
        when(actionTx.claimIndependent(anyLong(), any(), anyLong(), anyString())).thenReturn(false);

        JbkException ex = assertThrows(JbkException.class, () -> service.execute(bo(AFTER_SALE_NO), ADMIN));
        assertTrue(ex.getMessage().contains("已被他人认领"), "实际=" + ex.getMessage());
        verify(actionTx, never()).executeInTx(anyLong(), anyLong(), anyString());
        // 没认到就没有「执行中」的行可落终态，此时写终态反而会覆盖别人正在推进的那笔
        verify(actionTx, never()).markTerminalIndependent(anyLong(), any(), Mockito.anyInt(),
                any(), any(), anyLong(), anyString());
    }

    @Test
    void successfulPathClaimsThenExecutesAndNeverMarksTerminal() {
        seedAction(action(ActionStatus.PENDING, StrategyCode.PRODUCT_ONLY.name(), 0));

        service.execute(bo(AFTER_SALE_NO), ADMIN);

        verify(actionTx).claimIndependent(eq(ACTION_ID), eq(1), eq(ADMIN), anyString());
        verify(actionTx).executeInTx(eq(ACTION_ID), eq(ADMIN), anyString());
        verify(actionTx, never()).markTerminalIndependent(anyLong(), any(), Mockito.anyInt(),
                any(), any(), anyLong(), anyString());
    }

    // ==================== execute：失败后的终局分流 ====================

    /**
     * 基础设施类失败（锁等待/死锁）判为可重试：带重试排期落 RETRY_WAIT。
     * 这类失败重试一次往往就过，判成需人工对账会把运营淹没在假告警里。
     */
    @Test
    void transientFailureFallsIntoRetryWaitWithSchedule() {
        seedAction(action(ActionStatus.PENDING, StrategyCode.PRODUCT_ONLY.name(), 0));
        Mockito.doThrow(new CannotAcquireLockException("lock wait timeout"))
                .when(actionTx).executeInTx(anyLong(), anyLong(), anyString());

        assertThrows(RuntimeException.class, () -> service.execute(bo(AFTER_SALE_NO), ADMIN));

        verify(actionTx).markTerminalIndependent(eq(ACTION_ID), any(),
                eq(ActionStatus.RETRY_WAIT.getValue()),
                // 可重试必须带排期：nextRetryTime 为空时 markTerminal 的 SQL 会走终态分支，
                // 计数不递增、上限闸也不生效，重试将永不耗尽
                argThatNotNull(), anyString(), eq(ADMIN), anyString());
    }

    /**
     * 业务类失败（账本断裂、卡换主等）判为需人工对账：<b>不给重试排期</b>。
     * 这类失败重试多少次都是同样结果，排期只会让它在 Worker 里空转。
     */
    @Test
    void businessFailureFallsIntoReconciliationWithoutSchedule() {
        seedAction(action(ActionStatus.PENDING, StrategyCode.PRODUCT_ONLY.name(), 0));
        Mockito.doThrow(new JbkException("返还目标水卡已换主，拒绝返还"))
                .when(actionTx).executeInTx(anyLong(), anyLong(), anyString());

        assertThrows(JbkException.class, () -> service.execute(bo(AFTER_SALE_NO), ADMIN));

        verify(actionTx).markTerminalIndependent(eq(ACTION_ID), any(),
                eq(ActionStatus.RECONCILIATION_REQUIRED.getValue()),
                isNull(), anyString(), eq(ADMIN), anyString());
    }

    /**
     * 落终态失败不得吞掉原始失败：运营需要看到的是「为什么退款失败」，
     * 而不是「落痕时数据库也炸了」。原因链断在这里，排查会从资金问题跑偏成基础设施问题。
     */
    @Test
    void terminalMarkFailureDoesNotSwallowOriginalCause() {
        seedAction(action(ActionStatus.PENDING, StrategyCode.PRODUCT_ONLY.name(), 0));
        Mockito.doThrow(new JbkException("账本断裂"))
                .when(actionTx).executeInTx(anyLong(), anyLong(), anyString());
        Mockito.doThrow(new IllegalStateException("落痕也失败"))
                .when(actionTx).markTerminalIndependent(anyLong(), any(), Mockito.anyInt(),
                        any(), any(), anyLong(), anyString());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.execute(bo(AFTER_SALE_NO), ADMIN));
        assertTrue(ex.getMessage().contains("账本断裂"),
                "必须抛出原始业务失败而不是落痕失败，实际=" + ex.getMessage());
    }

    // ==================== 取水核账：只做委托，不得夹带自己的判定 ====================

    @Test
    void waterAbnormalPreviewDelegatesWithoutSideEffect() {
        service.previewWaterAbnormal(66L);

        verify(waterTx).preview(66L);
        verify(actionTx, never()).executeInTx(anyLong(), anyLong(), anyString());
    }

    @Test
    void waterAbnormalConfirmDelegatesToReconcileTxOnly() {
        service.confirmWaterAbnormal(66L, "核对无误", ADMIN);

        verify(waterTx).confirm(eq(66L), eq("核对无误"), eq(ADMIN), anyString());
        // 核账零资金写入：一旦这里走上返还内核，「两侧对开」的编译期护栏就被绕过了
        verify(actionTx, never()).executeInTx(anyLong(), anyLong(), anyString());
        verify(actionTx, never()).claimIndependent(anyLong(), any(), anyLong(), anyString());
    }

    /**
     * 充值退款必须在锁批次、登记动作之前确认退款通道可用。
     * 否则真实微信适配器未配置时，一次失败点击会永久冻结用户权益。
     */
    @Test
    void rechargeRefundChecksSourceCapabilityBeforePreparingEntitlement() {
        Mockito.doThrow(new JbkException("退款通道未配置"))
                .when(refundAdapter).requireOperable();

        JbkException ex = assertThrows(JbkException.class,
                () -> service.requestRechargeRefund(66L, "用户申请退款", ADMIN));

        assertTrue(ex.getMessage().contains("退款通道未配置"), "实际=" + ex.getMessage());
        verify(entitlementRefundTx, never()).prepare(anyLong(), any(), anyLong(), anyString());
        verify(refundRequestTx, never()).createPending(anyLong(), anyLong(), anyString());
    }

    /** 未入账异常单同样必须先过退款能力闸，不能留下孤立动作或本地退款单。 */
    @Test
    void unsettledRechargeRefundChecksCapabilityBeforeCreatingLocalEvidence() {
        Mockito.doThrow(new JbkException("退款通道未配置"))
                .when(refundAdapter).requireOperable();

        assertThrows(JbkException.class,
                () -> service.requestUnsettledRechargeRefund(66L, "核对迟到支付", ADMIN));

        verify(refundRequestTx, never()).createUnsettledRechargePending(
                anyLong(), anyString(), anyLong(), anyString());
        verify(refundAdapter, never()).acceptRefund(anyString(), anyString(), anyLong(), anyString());
    }

    /** 通用退款入口不得接收充值退款动作，避免绕过充值退款专用财务权限与受理流程。 */
    @Test
    void genericRefundEntryRejectsRechargeRefundAction() {
        WsAfterSaleAction rechargeRefund = action(ActionStatus.PENDING, StrategyCode.PRODUCT_ONLY.name(), 0);
        rechargeRefund.setSourceType(SourceType.RECHARGE_REFUND.getValue());
        rechargeRefund.setActionType(ActionType.GATEWAY_REFUND.getValue());
        seedAction(rechargeRefund);

        JbkException ex = assertThrows(JbkException.class,
                () -> service.requestRefund(bo(AFTER_SALE_NO), ADMIN));

        assertTrue(ex.getMessage().contains("充值退款必须走专用入口"), "实际=" + ex.getMessage());
        verify(refundRequestTx, never()).createPending(anyLong(), anyLong(), anyString());
        verify(refundAdapter, never()).acceptRefund(anyString(), anyString(), anyLong(), anyString());
    }

    /** 补送入口也必须使用 ID + 售后号共键，不能只信任页面回传的主键。 */
    @Test
    void resendRejectsMismatchedAfterSaleNoBeforeGeneration() {
        WsAfterSaleAction resend = action(ActionStatus.PENDING, StrategyCode.RESEND.name(), 0);
        resend.setActionType(ActionType.RESEND.getValue());
        seedAction(resend);

        JbkException ex = assertThrows(JbkException.class,
                () -> service.generateResend(bo("AS-SOME-OTHER-NO"), ADMIN));

        assertTrue(ex.getMessage().contains("售后号与动作ID不匹配"), "实际=" + ex.getMessage());
        verify(resendTx, never()).generate(anyLong(), anyLong(), anyString());
    }

    private static String argThatNotNull() {
        return Mockito.argThat(v -> v != null && !v.isEmpty());
    }

    @Test
    void detailRejectsMissingAction() {
        when(actionMapper.selectAdminActionById(ACTION_ID)).thenReturn(null);
        assertThrows(JbkException.class, () -> service.detail(ACTION_ID));
    }
}

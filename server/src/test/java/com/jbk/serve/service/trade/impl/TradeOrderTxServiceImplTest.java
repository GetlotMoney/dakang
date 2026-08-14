package com.jbk.serve.service.trade.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.aftersale.batch.EntitlementLedger;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TradeOrderTxServiceImplTest {

    private TradeCardMapper cardMapper;
    private WsOrderMapper orderMapper;
    private WsWalletFlowMapper flowMapper;
    private IWsDomainEventService eventService;
    private TradeOrderTxServiceImpl service;
    private EntitlementLedger ledger;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WsOrder.class);
    }

    /**
     * 结算的定位读与锁内当前读返回同一行：生产侧先无锁定位（拿 cardId 去锁卡），
     * 再对订单做 FOR UPDATE 当前读，全部结算口径取自锁内行。
     */
    private void givenOrder(WsOrder order) {
        when(orderMapper.selectById(1L)).thenReturn(order);
        when(orderMapper.selectByIdForUpdate(1L)).thenReturn(order);
    }

    /** 结算入口无条件锁卡（锁序对齐「卡 → 订单」）；补偿分支的卡主取自这一行。 */
    private void givenLockedCard() {
        WsCard locked = new WsCard().setUserId(30L);
        locked.setDataStatus(0);
        when(cardMapper.selectByIdForUpdate(10L)).thenReturn(locked);
    }

    @BeforeEach
    void setUp() {
        cardMapper = mock(TradeCardMapper.class);
        orderMapper = mock(WsOrderMapper.class);
        flowMapper = mock(WsWalletFlowMapper.class);
        eventService = mock(IWsDomainEventService.class);
        service = new TradeOrderTxServiceImpl();
        ReflectionTestUtils.setField(service, "tradeCardMapper", cardMapper);
        ReflectionTestUtils.setField(service, "wsOrderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "walletFlowMapper", flowMapper);
        ReflectionTestUtils.setField(service, "domainEventService", eventService);
        // 包D-4：退差回补权益批次。本类只钉「退差算得对不对、写没写卡与流水」，
        // 批次侧的真实行为由 WaterOrderTxDbTest / EntitlementLedgerDbTest 用真库钉，
        // 故这里给一个 Mock 台账——但下面的用例会显式断言它被调用了几次、参数是什么，
        // 不然「接线被删掉」在这个类里看不出来
        ledger = mock(EntitlementLedger.class);
        ReflectionTestUtils.setField(service, "entitlementLedger", ledger);
        // E2E-08 完成挂点协作方：mock 分账（分账行为由 SettlementDbTest 用真库锁定）
        ReflectionTestUtils.setField(service, "splitService",
                mock(com.jbk.serve.service.settlement.ISplitService.class));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-1L})
    void rejectsMissingOrNegativeActualMlWithoutBusinessWrites(Long actualMl) {
        givenOrder(balanceOrder());

        assertThrows(JbkException.class, () -> service.settleWaterOrder(1L, true, actualMl));

        assertNoSettlementWrites();
        verify(eventService).recordReliable(eq(OpsEnum.EventType.ORDER_STATUS), eq("WO-1"), isNull(), any());
    }

    @Test
    void rejectsPlanAboveCreateLimitWithoutBusinessWrites() {
        WsOrder order = balanceOrder().setPlanMl(99_001L);
        givenOrder(order);

        assertThrows(JbkException.class, () -> service.settleWaterOrder(1L, true, 1L));

        assertNoSettlementWrites();
    }

    @Test
    void rejectsUnsupportedPayWayWithoutTreatingItAsWaterCredit() {
        WsOrder order = balanceOrder().setPayWay(TradeEnum.PayWay.WECHAT.getValue());
        givenOrder(order);

        assertThrows(JbkException.class, () -> service.settleWaterOrder(1L, true, 1_000L));

        assertNoSettlementWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"unitPriceFenPerLiter\":-1}",
            "{\"unitPriceFenPerLiter\":1.5}",
            "{\"unitPriceFenPerLiter\":100001}",
            "{\"unitPriceFenPerLiter\":\"01\"}"
    })
    void rejectsIllegalNumericUnitPriceWithoutBusinessWrites(String snapshot) {
        givenOrder(balanceOrder().setPackageSnap(snapshot));

        assertThrows(JbkException.class, () -> service.settleWaterOrder(1L, true, 1_000L));

        assertNoSettlementWrites();
    }

    @Test
    void rejectsPrechargeThatDoesNotMatchValidPlanAndPriceSnapshot() {
        WsOrder order = balanceOrder().setOrderAmount(99L);
        givenOrder(order);

        assertThrows(JbkException.class, () -> service.settleWaterOrder(1L, true, 1_000L));

        assertNoSettlementWrites();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"not-json", "{}"})
    void explicitZeroUsesApprovedPriceFallbackAndRefundsAtMostPrecharge(String snapshot) {
        givenLockedCard();
        WsOrder order = balanceOrder().setPackageSnap(snapshot);
        givenOrder(order);
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        // CARD-MEMBER：补偿归属条件=卡主(30)，UPDATE_BY=实际使用人(20)；卡主由结算前 selectById 读出
        when(cardMapper.compensateBalance(eq(10L), eq(100L), eq(30L), eq(20L), anyString())).thenReturn(1);
        // 故意与锁内卡主(30)不同：补偿的归属条件若退回无锁 selectById，下面 eq(30L) 立刻变红
        when(cardMapper.selectById(10L)).thenReturn(
                new WsCard().setUserId(31L).setBalanceAmount(1_100L).setBalanceMl(500L));

        assertTrue(service.settleWaterOrder(1L, true, 0L));

        verify(eventService).recordReliable(eq(OpsEnum.EventType.ORDER_STATUS), eq("WO-1"), isNull(), any());
        verify(cardMapper).compensateBalance(eq(10L), eq(100L), eq(30L), eq(20L), anyString());
        ArgumentCaptor<WsWalletFlow> flowCaptor = ArgumentCaptor.forClass(WsWalletFlow.class);
        verify(flowMapper).insert(flowCaptor.capture());
        assertEquals(100L, flowCaptor.getValue().getAmountChange());
        assertEquals(1_100L, flowCaptor.getValue().getAmountAfter());
    }

    @Test
    void rejectsActualChargeAbovePrechargeWithoutBusinessWrites() {
        WsOrder order = balanceOrder()
                .setPlanMl(1_000L)
                .setOrderAmount(99L)
                .setPackageSnap("{\"unitPriceFenPerLiter\":100}");
        givenOrder(order);

        assertThrows(JbkException.class, () -> service.settleWaterOrder(1L, true, 1_000L));

        assertNoSettlementWrites();
    }

    @Test
    void validBalanceOrderPartiallyRefundsUsingFrozenPrice() {
        givenLockedCard();
        givenOrder(balanceOrder());
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(cardMapper.compensateBalance(eq(10L), eq(50L), eq(30L), eq(20L), anyString())).thenReturn(1);
        // 同上：AFTER 快照行的 USER_ID 与锁内卡主不同，用来区分卡主到底取自哪一次读
        when(cardMapper.selectById(10L)).thenReturn(
                new WsCard().setUserId(31L).setBalanceAmount(1_050L).setBalanceMl(500L));

        assertTrue(service.settleWaterOrder(1L, true, 2_500L));

        verify(cardMapper).compensateBalance(eq(10L), eq(50L), eq(30L), eq(20L), anyString());
        ArgumentCaptor<WsWalletFlow> flowCaptor = ArgumentCaptor.forClass(WsWalletFlow.class);
        verify(flowMapper).insert(flowCaptor.capture());
        assertEquals(50L, flowCaptor.getValue().getAmountChange());

        // 包D-4 正向断言：退差必须<b>同时</b>回补权益批次，且额度与卡的变动逐维相等。
        // 删掉 settleWaterOrder 里的 restoreEntitlement 那一行，本段立刻红——
        // 只靠上面的卡与流水断言，「加了卡却没回补批次」是看不出来的，
        // 而它的后果是那 50 分余额从此凑不出批次额度、永远花不掉。
        ArgumentCaptor<EntitlementLedger.CardAfter> cardCaptor =
                ArgumentCaptor.forClass(EntitlementLedger.CardAfter.class);
        verify(ledger).restoreOnRefundBack(cardCaptor.capture(), eq("DISPENSE:WO-1"),
                eq(50L), eq(0L), eq(20L), anyString());
        assertEquals(10L, cardCaptor.getValue().cardId());
        assertEquals(30L, cardCaptor.getValue().ownerUserId());
        assertEquals(1_050L, cardCaptor.getValue().amountAfter());
        assertEquals(500L, cardCaptor.getValue().mlAfter());
    }

    @Test
    void zeroDiffSettlementNeitherTouchesCardNorEntitlementBatches() {
        // 足量出水没有退差：卡不动、流水不写、批次也一律不碰。
        // 这条与上一条互为对照——回补只在真有返还时发生，不是每次结算都刷一遍批次
        givenOrder(balanceOrder());
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        assertTrue(service.settleWaterOrder(1L, true, 5_000L));

        verify(cardMapper, never()).compensateBalance(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verify(flowMapper, never()).insert(any(WsWalletFlow.class));
        verifyNoInteractions(ledger);
    }

    // 锁内卡行的 DATA_STATUS 闸门与拒绝分支：删掉 lockCardForSettlement 里的 DATA_STATUS 判断，
    // 或让 requireLockedCardOwner 不抛，本组用例立刻变红（此前这两处零覆盖）
    @Test
    void logicallyDeletedCardRejectsCompensationWithoutAnyWrite() {
        WsCard deleted = new WsCard().setUserId(30L);
        deleted.setDataStatus(1);
        when(cardMapper.selectByIdForUpdate(10L)).thenReturn(deleted);
        givenOrder(balanceOrder());
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        JbkException denied = assertThrows(JbkException.class,
                () -> service.settleWaterOrder(1L, true, 2_500L));

        assertEquals("退差补偿入账失败（水卡不存在或已删除）", denied.getMsg());
        verify(cardMapper, never()).compensateBalance(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verify(flowMapper, never()).insert(any(WsWalletFlow.class));
        verifyNoInteractions(ledger);
    }

    @Test
    void missingCardRowRejectsCompensationWithoutAnyWrite() {
        when(cardMapper.selectByIdForUpdate(10L)).thenReturn(null);
        givenOrder(balanceOrder());
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        assertThrows(JbkException.class, () -> service.settleWaterOrder(1L, true, 2_500L));

        verify(cardMapper, never()).compensateBalance(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verify(flowMapper, never()).insert(any(WsWalletFlow.class));
    }

    // 零退差结算不碰卡：卡缺失时既有语义是「照常完成」，不得被前置锁卡改成拦死
    @Test
    void zeroRefundSettlementStillCompletesWhenCardRowMissing() {
        when(cardMapper.selectByIdForUpdate(10L)).thenReturn(null);
        givenOrder(balanceOrder());
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        assertTrue(service.settleWaterOrder(1L, true, 5_000L));

        verify(flowMapper, never()).insert(any(WsWalletFlow.class));
    }

    // ============ 锁序不变式（R2 复审 D2-01）：卡 → 订单，不得反向 ============
    // 反向即与下单链构成 ABBA 死锁；并发复现靠运气，故钉可确定观测的顺序：
    // 定位读 → 卡锁 → 订单锁 → 订单写。只断言卡锁早于 UPDATE 不够。

    @Test
    void settlementLocksCardBeforeWritingOrderRow() {
        givenLockedCard();
        givenOrder(balanceOrder());
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        assertTrue(service.settleWaterOrder(1L, true, 5_000L));

        var order = inOrder(cardMapper, orderMapper);
        order.verify(orderMapper).selectById(1L);
        order.verify(cardMapper).selectByIdForUpdate(10L);
        order.verify(orderMapper).selectByIdForUpdate(1L);
        order.verify(orderMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    void overDispenseAlsoLocksCardBeforeWritingOrderRow() {
        // 超量分支提前 return，同样不得绕过锁序前置
        givenLockedCard();
        givenOrder(balanceOrder());
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        assertTrue(service.settleWaterOrder(1L, true, 5_001L));

        var order = inOrder(cardMapper, orderMapper);
        order.verify(orderMapper).selectById(1L);
        order.verify(cardMapper).selectByIdForUpdate(10L);
        order.verify(orderMapper).selectByIdForUpdate(1L);
        order.verify(orderMapper).update(isNull(), any(Wrapper.class));
    }

    // ============ B19：超量出水必须可识别（REQ-035），不得静默按已完成收口 ============

    @Test
    void overDispenseMovesOrderToAbnormalKeepingRawActualWithoutAnyCompensation() {
        givenOrder(balanceOrder());
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        assertTrue(service.settleWaterOrder(1L, true, 5_001L));

        SettleUpdate update = captureSettleUpdate();
        assertTrue(update.sqlSet().contains("ORDER_STATUS=" + TradeEnum.OrderStatus.ABNORMAL.getValue()),
                "超量必须落 6异常待补偿，实际 SET=" + update.sqlSet());
        assertTrue(update.sqlSet().contains("ACTUAL_ML=5001"),
                "必须保留设备上报原始水量，不得改写成计划量，实际 SET=" + update.sqlSet());
        assertTrue(update.where().contains("ORDER_STATUSIN(?,?)"),
                "终态推进必须带精确前态 CAS，实际 WHERE=" + update.where());
        // 不追加扣款、不生成退款/退差流水、不回补权益批次
        verify(cardMapper, never()).compensateBalance(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verify(cardMapper, never()).compensateMl(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verify(cardMapper, never()).deductBalance(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verify(flowMapper, never()).insert(any(WsWalletFlow.class));
        verifyNoInteractions(ledger);
        // 审计：至少含订单号、计划量、实际量、超出量
        ArgumentCaptor<Object> evidence = ArgumentCaptor.forClass(Object.class);
        verify(eventService).recordReliable(eq(OpsEnum.EventType.ORDER_STATUS), eq("WO-1"),
                isNull(), evidence.capture());
        String text = String.valueOf(evidence.getValue());
        assertTrue(text.contains("5000") && text.contains("5001") && text.contains("\"overMl\":1"),
                "审计证据必须含 planMl/actualMl/overMl，实际=" + text);
    }

    @Test
    void hugeOverDispenseStaysFailClosedWithoutOverflow() {
        givenOrder(balanceOrder());
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        assertTrue(service.settleWaterOrder(1L, true, Long.MAX_VALUE));

        SettleUpdate update = captureSettleUpdate();
        assertTrue(update.sqlSet().contains("ORDER_STATUS=" + TradeEnum.OrderStatus.ABNORMAL.getValue()),
                "大幅超量同样 fail-closed，实际 SET=" + update.sqlSet());
        verify(flowMapper, never()).insert(any(WsWalletFlow.class));
    }

    @Test
    void repeatedOverDispenseResultNeitherRewritesStateNorDuplicatesAudit() {
        // 并发/重投：前态 CAS 影响行数为 0 即已被结算，既不改单也不再落审计
        givenOrder(balanceOrder());
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertFalse(service.settleWaterOrder(1L, true, 5_001L));

        verify(flowMapper, never()).insert(any(WsWalletFlow.class));
        verify(eventService, never()).recordReliable(any(), anyString(), any(), any());
    }

    @Test
    void failedResultWithOverageKeepsExistingAbnormalPathWithoutRefund() {
        // success=false 且超量：既有口径不变（6异常待核 + 结算量封顶 ⇒ 零退差）
        givenOrder(balanceOrder());
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        assertTrue(service.settleWaterOrder(1L, false, 5_001L));

        verify(cardMapper, never()).compensateBalance(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verify(flowMapper, never()).insert(any(WsWalletFlow.class));
    }

    /** 结算 UPDATE 的 SET 片段（参数已回填）与 WHERE 片段（去空格）。 */
    private record SettleUpdate(String sqlSet, String where) {
    }

    private SettleUpdate captureSettleUpdate() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<WsOrder>> captor =
                ArgumentCaptor.forClass((Class<LambdaUpdateWrapper<WsOrder>>) (Class<?>) LambdaUpdateWrapper.class);
        verify(orderMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<WsOrder> wrapper = captor.getValue();
        String sqlSet = wrapper.getSqlSet();
        for (Map.Entry<String, Object> e : wrapper.getParamNameValuePairs().entrySet()) {
            sqlSet = sqlSet.replace("#{ew.paramNameValuePairs." + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return new SettleUpdate(sqlSet.replace(" ", ""), wrapper.getTargetSql().replace(" ", ""));
    }

    private WsOrder balanceOrder() {
        return new WsOrder()
                .setId(1L)
                .setOrderNo("WO-1")
                .setUserId(20L)
                .setCardId(10L)
                .setOrderType(TradeEnum.OrderType.WATER.getValue())
                .setPayWay(TradeEnum.PayWay.CARD_BALANCE.getValue())
                .setOrderStatus(TradeEnum.OrderStatus.PAID.getValue())
                .setPlanMl(5_000L)
                .setOrderAmount(100L)
                .setPackageSnap("{\"unitPriceFenPerLiter\":20}");
    }

    private void assertNoSettlementWrites() {
        verify(orderMapper, never()).update(isNull(), any(Wrapper.class));
        verify(cardMapper, never()).compensateBalance(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verify(cardMapper, never()).compensateMl(anyLong(), anyLong(), anyLong(), anyLong(), anyString());
        verify(flowMapper, never()).insert(any(WsWalletFlow.class));
    }
}

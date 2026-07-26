package com.jbk.serve.service.trade.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeOrderTxServiceImplTest {

    private TradeCardMapper cardMapper;
    private WsOrderMapper orderMapper;
    private WsWalletFlowMapper flowMapper;
    private IWsDomainEventService eventService;
    private TradeOrderTxServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WsOrder.class);
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
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-1L})
    void rejectsMissingOrNegativeActualMlWithoutBusinessWrites(Long actualMl) {
        when(orderMapper.selectById(1L)).thenReturn(balanceOrder());

        assertThrows(JbkException.class, () -> service.settleWaterOrder(1L, true, actualMl));

        assertNoSettlementWrites();
        verify(eventService).recordReliable(eq(OpsEnum.EventType.ORDER_STATUS), eq("WO-1"), isNull(), any());
    }

    @Test
    void rejectsPlanAboveCreateLimitWithoutBusinessWrites() {
        WsOrder order = balanceOrder().setPlanMl(99_001L);
        when(orderMapper.selectById(1L)).thenReturn(order);

        assertThrows(JbkException.class, () -> service.settleWaterOrder(1L, true, 1L));

        assertNoSettlementWrites();
    }

    @Test
    void rejectsUnsupportedPayWayWithoutTreatingItAsWaterCredit() {
        WsOrder order = balanceOrder().setPayWay(TradeEnum.PayWay.WECHAT.getValue());
        when(orderMapper.selectById(1L)).thenReturn(order);

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
        when(orderMapper.selectById(1L)).thenReturn(balanceOrder().setPackageSnap(snapshot));

        assertThrows(JbkException.class, () -> service.settleWaterOrder(1L, true, 1_000L));

        assertNoSettlementWrites();
    }

    @Test
    void rejectsPrechargeThatDoesNotMatchValidPlanAndPriceSnapshot() {
        WsOrder order = balanceOrder().setOrderAmount(99L);
        when(orderMapper.selectById(1L)).thenReturn(order);

        assertThrows(JbkException.class, () -> service.settleWaterOrder(1L, true, 1_000L));

        assertNoSettlementWrites();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"not-json", "{}"})
    void explicitZeroUsesApprovedPriceFallbackAndRefundsAtMostPrecharge(String snapshot) {
        WsOrder order = balanceOrder().setPackageSnap(snapshot);
        when(orderMapper.selectById(1L)).thenReturn(order);
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        // CARD-MEMBER：补偿归属条件=卡主(30)，UPDATE_BY=实际使用人(20)；卡主由结算前 selectById 读出
        when(cardMapper.compensateBalance(eq(10L), eq(100L), eq(30L), eq(20L), anyString())).thenReturn(1);
        when(cardMapper.selectById(10L)).thenReturn(
                new WsCard().setUserId(30L).setBalanceAmount(1_100L).setBalanceMl(500L));

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
        when(orderMapper.selectById(1L)).thenReturn(order);

        assertThrows(JbkException.class, () -> service.settleWaterOrder(1L, true, 1_000L));

        assertNoSettlementWrites();
    }

    @Test
    void validBalanceOrderPartiallyRefundsUsingFrozenPrice() {
        when(orderMapper.selectById(1L)).thenReturn(balanceOrder());
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(cardMapper.compensateBalance(eq(10L), eq(50L), eq(30L), eq(20L), anyString())).thenReturn(1);
        when(cardMapper.selectById(10L)).thenReturn(
                new WsCard().setUserId(30L).setBalanceAmount(1_050L).setBalanceMl(500L));

        assertTrue(service.settleWaterOrder(1L, true, 2_500L));

        verify(cardMapper).compensateBalance(eq(10L), eq(50L), eq(30L), eq(20L), anyString());
        ArgumentCaptor<WsWalletFlow> flowCaptor = ArgumentCaptor.forClass(WsWalletFlow.class);
        verify(flowMapper).insert(flowCaptor.capture());
        assertEquals(50L, flowCaptor.getValue().getAmountChange());
    }

    private WsOrder balanceOrder() {
        return new WsOrder()
                .setId(1L)
                .setOrderNo("WO-1")
                .setUserId(20L)
                .setCardId(10L)
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

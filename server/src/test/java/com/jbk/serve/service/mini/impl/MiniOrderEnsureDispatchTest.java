package com.jbk.serve.service.mini.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.device.IWsCommandService;
import com.jbk.serve.service.mini.IMiniDeviceService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.trade.ITradeOrderTxService;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import com.jbk.tool.data.mini.vo.ScanSessionInfo;
import com.jbk.tool.data.mini.vo.WaterEligibilityVo;
import com.jbk.tool.data.trade.bo.CreateWaterOrderBo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.trade.vo.OrderDetailVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0 悬挂单闸回归（编排层）：资金事务提交后的每一条路径都必须走 ensureDispatchOrTerminal——
 * 会话消费失败不阻断指令创建；幂等重试补齐指令；补发失败进入可追溯异常终态；
 * 已绑指令/已推进订单不重复触发。目标不变式：绝不存在「订单 2、CMD_ID 空、无指令」的稳定悬挂态。
 */
class MiniOrderEnsureDispatchTest {

    private static final Long USER_ID = 9L;
    private static final String REQUEST_ID = "scan-session-p0";
    private static final Long CARD_ID = 101L;
    private static final Long WATER_TYPE_ID = 8L;
    private static final Long PLAN_ML = 5_000L;
    private static final int PAY_WAY = 3;
    private static final Long ORDER_ID = 61L;
    private static final String ORDER_NO = MiniOrderServiceImpl.buildOrderNo(USER_ID, REQUEST_ID);

    private MiniOrderServiceImpl service;
    private IMiniDeviceService miniDeviceService;
    private ITradeOrderTxService tradeOrderTxService;
    private IWsCommandService commandService;
    private WsOrderMapper wsOrderMapper;
    private WsWalletFlowMapper walletFlowMapper;
    private WsCommandMapper commandMapper;
    private WsDeviceOutletMapper outletMapper;
    private WsDeviceMapper deviceMapper;
    private IWsDomainEventService domainEventService;
    private ValueOperations<String, Object> valueOperations;

    static {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WsOrder.class);
        TableInfoHelper.initTableInfo(assistant, WsWalletFlow.class);
        TableInfoHelper.initTableInfo(assistant, WsCommand.class);
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() {
        service = new MiniOrderServiceImpl();
        miniDeviceService = Mockito.mock(IMiniDeviceService.class);
        tradeOrderTxService = Mockito.mock(ITradeOrderTxService.class);
        commandService = Mockito.mock(IWsCommandService.class);
        wsOrderMapper = Mockito.mock(WsOrderMapper.class);
        walletFlowMapper = Mockito.mock(WsWalletFlowMapper.class);
        commandMapper = Mockito.mock(WsCommandMapper.class);
        outletMapper = Mockito.mock(WsDeviceOutletMapper.class);
        deviceMapper = Mockito.mock(WsDeviceMapper.class);
        domainEventService = Mockito.mock(IWsDomainEventService.class);
        RedisTemplate<String, Object> redis = Mockito.mock(RedisTemplate.class);
        valueOperations = Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(walletFlowMapper.selectCount(any())).thenReturn(1L);

        ReflectionTestUtils.setField(service, "miniDeviceService", miniDeviceService);
        ReflectionTestUtils.setField(service, "tradeOrderTxService", tradeOrderTxService);
        ReflectionTestUtils.setField(service, "commandService", commandService);
        ReflectionTestUtils.setField(service, "wsOrderMapper", wsOrderMapper);
        ReflectionTestUtils.setField(service, "walletFlowMapper", walletFlowMapper);
        ReflectionTestUtils.setField(service, "commandMapper", commandMapper);
        ReflectionTestUtils.setField(service, "outletMapper", outletMapper);
        ReflectionTestUtils.setField(service, "deviceMapper", deviceMapper);
        ReflectionTestUtils.setField(service, "domainEventService", domainEventService);
        ReflectionTestUtils.setField(service, "redis", redis);
    }

    private CreateWaterOrderBo request() {
        return new CreateWaterOrderBo()
                .setScanSessionId(REQUEST_ID)
                .setCardId(CARD_ID)
                .setWaterTypeId(WATER_TYPE_ID)
                .setPlanMl(PLAN_ML)
                .setPayWay(PAY_WAY);
    }

    private WsOrder paidOrder(Long cmdId) {
        WsOrder order = new WsOrder()
                .setId(ORDER_ID)
                .setOrderNo(ORDER_NO)
                .setOrderType(TradeEnum.OrderType.WATER.getValue())
                .setUserId(USER_ID)
                .setCardId(CARD_ID)
                .setPlanMl(PLAN_ML)
                .setOrderAmount(0L)
                .setPayWay(PAY_WAY)
                .setOrderStatus(TradeEnum.OrderStatus.PAID.getValue())
                .setCmdId(cmdId)
                .setPackageSnap("{\"requestId\":\"" + REQUEST_ID + "\",\"unitPriceFenPerLiter\":20,"
                        + "\"planMl\":" + PLAN_ML + ",\"payWay\":" + PAY_WAY
                        + ",\"waterTypeId\":" + WATER_TYPE_ID + "}");
        order.setCreateTime("20260723090000");
        order.setUpdateTime("20260723090000");
        return order;
    }

    private void stubFreshCreationPath() {
        when(miniDeviceService.loadScanSession(REQUEST_ID, USER_ID)).thenReturn(new ScanSessionInfo()
                .setUserId(USER_ID).setQrcodeId(11L).setStationId(41L).setDeviceId(21L).setOutletId(31L));
        when(miniDeviceService.checkEligibility(REQUEST_ID, CARD_ID, USER_ID))
                .thenReturn(new WaterEligibilityVo().setAvailability("AVAILABLE"));
        when(outletMapper.selectById(31L)).thenReturn(new WsDeviceOutlet()
                .setId(31L).setDeviceId(21L).setWaterTypeId(WATER_TYPE_ID).setOutletPrice("20"));
        when(deviceMapper.selectById(21L)).thenReturn(new WsDevice().setId(21L).setStationId(41L));
        when(tradeOrderTxService.createWaterOrder(any(), any(ScanSessionInfo.class), anyString()))
                .thenAnswer(invocation -> {
                    WsOrder order = invocation.getArgument(0);
                    order.setId(ORDER_ID);
                    return order;
                });
    }

    // 1. 会话消费（Redis 删除）抛异常：不阻断指令创建，且不重复扣卡、不重复建单
    @Test
    void sessionConsumeFailureStillDispatchesWithoutDoubleCharge() {
        stubFreshCreationPath();
        Mockito.doThrow(new IllegalStateException("redis down"))
                .when(miniDeviceService).consumeScanSession(REQUEST_ID);
        when(wsOrderMapper.selectOne(any())).thenReturn(null, paidOrder(null));

        OrderDetailVo detail = service.createWaterOrder(request(), USER_ID);

        assertEquals(ORDER_NO, detail.getOrder().getOrderNo());
        // 指令创建必须发生：会话消费只是提示层清理，失败绝不能把已扣款订单晾成悬挂单
        verify(commandService, times(1)).sendDispenseForOrder(ORDER_ID);
        // 不重复扣卡（资金事务恰一次）；编排层不得直接写订单/流水
        verify(tradeOrderTxService, times(1)).createWaterOrder(any(), any(ScanSessionInfo.class), anyString());
        verify(wsOrderMapper, never()).insert(any(WsOrder.class));
        verify(walletFlowMapper, never()).insert(any(WsWalletFlow.class));
    }

    // 2. 幂等重试命中「订单 2、CMD_ID 空」：必须补齐指令，而不是原样返回悬挂单
    @Test
    void idempotentRetryBackfillsMissingDispatch() {
        when(wsOrderMapper.selectOne(any())).thenReturn(paidOrder(null));

        OrderDetailVo detail = service.createWaterOrder(request(), USER_ID);

        assertEquals(ORDER_NO, detail.getOrder().getOrderNo());
        verify(commandService, times(1)).sendDispenseForOrder(ORDER_ID);
        verify(tradeOrderTxService, never()).createWaterOrder(any(), any(ScanSessionInfo.class), anyString());
        verify(miniDeviceService, never()).consumeScanSession(anyString());
    }

    // 3. 幂等重试补发失败：必须走无痕兜底转「6 异常待补偿」，响应返回最新异常态
    @Test
    void idempotentRetryDispatchFailureMovesOrderToTraceableAbnormal() {
        WsOrder pending = paidOrder(null);
        WsOrder abnormal = paidOrder(null).setOrderStatus(TradeEnum.OrderStatus.ABNORMAL.getValue())
                .setCancelReason("出水指令下发失败且未生成指令，转异常待补偿：MQTT publish failed");
        // 第一次：幂等查库；第二次：兜底后的回读（已是异常终态）
        when(wsOrderMapper.selectOne(any())).thenReturn(pending, abnormal);
        // 兜底内部：回读订单仍 2 且无 CMD_ID、指令行为 0 → CAS 转异常
        when(wsOrderMapper.selectById(ORDER_ID)).thenReturn(paidOrder(null));
        when(commandMapper.selectCount(any())).thenReturn(0L);
        when(wsOrderMapper.update(isNull(), any())).thenReturn(1);
        Mockito.doThrow(new IllegalStateException("MQTT publish failed"))
                .when(commandService).sendDispenseForOrder(ORDER_ID);

        OrderDetailVo detail = service.createWaterOrder(request(), USER_ID);

        // 终态只能二选一：这里指令未留痕 → 订单必须进入可追溯异常终态，且响应如实反映
        assertEquals(TradeEnum.OrderStatus.ABNORMAL.getValue(), detail.getOrder().getOrderStatus());
        verify(wsOrderMapper, times(1)).update(isNull(), any());
        verify(domainEventService, times(1)).record(any(), anyString(), any(), anyString());
    }

    // 4. 已绑定 CMD_ID 或已推进状态：不重复触发下发（既有指令状态机负责）
    @Test
    void skipsWhenCommandBoundOrOrderProgressed() {
        when(wsOrderMapper.selectOne(any())).thenReturn(paidOrder(99L));
        service.createWaterOrder(request(), USER_ID);
        verify(commandService, never()).sendDispenseForOrder(anyLong());

        Mockito.reset(commandService, wsOrderMapper);
        when(wsOrderMapper.selectOne(any()))
                .thenReturn(paidOrder(null).setOrderStatus(TradeEnum.OrderStatus.FINISHED.getValue()));
        service.createWaterOrder(request(), USER_ID);
        verify(commandService, never()).sendDispenseForOrder(anyLong());
    }
}

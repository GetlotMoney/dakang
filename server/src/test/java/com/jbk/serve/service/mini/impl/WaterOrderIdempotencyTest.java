package com.jbk.serve.service.mini.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.device.IWsCommandService;
import com.jbk.serve.service.mini.IMiniDeviceService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.trade.ITradeOrderTxService;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import com.jbk.tool.data.mini.vo.ScanSessionInfo;
import com.jbk.tool.data.mini.vo.WaterEligibilityVo;
import com.jbk.tool.data.trade.bo.CreateWaterOrderBo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.trade.vo.OrderDetailVo;
import com.jbk.tool.exception.JbkException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 取水订单服务级幂等回归：Redis 不是事实源，扫码会话被消费后仍可按确定性单号返原单；
 * 同一 requestId 只有完整参数一致才可复用，冲突路径不得扣卡、消费会话或下发设备指令。
 */
class WaterOrderIdempotencyTest {

    private static final Long USER_ID = 9L;
    private static final String REQUEST_ID = "scan-session-abc123";
    private static final Long CARD_ID = 101L;
    private static final Long WATER_TYPE_ID = 8L;
    private static final Long PLAN_ML = 5_000L;
    private static final int PAY_WAY = 2;
    private static final String ORDER_NO = "WO05A8DC178D49C203BF19";
    private static final String CACHE_KEY = "order:req:9:" + REQUEST_ID;

    private MiniOrderServiceImpl service;
    private IMiniDeviceService miniDeviceService;
    private ITradeOrderTxService tradeOrderTxService;
    private IWsCommandService commandService;
    private WsOrderMapper wsOrderMapper;
    private WsWalletFlowMapper walletFlowMapper;
    private WsDeviceOutletMapper outletMapper;
    private WsDeviceMapper deviceMapper;
    private IWsDomainEventService domainEventService;
    private RedisTemplate<String, Object> redis;
    private ValueOperations<String, Object> valueOperations;

    static {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WsOrder.class);
        TableInfoHelper.initTableInfo(assistant, WsWalletFlow.class);
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
        outletMapper = Mockito.mock(WsDeviceOutletMapper.class);
        deviceMapper = Mockito.mock(WsDeviceMapper.class);
        domainEventService = Mockito.mock(IWsDomainEventService.class);
        redis = Mockito.mock(RedisTemplate.class);
        valueOperations = Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(walletFlowMapper.selectCount(any())).thenReturn(1L);

        ReflectionTestUtils.setField(service, "miniDeviceService", miniDeviceService);
        ReflectionTestUtils.setField(service, "tradeOrderTxService", tradeOrderTxService);
        ReflectionTestUtils.setField(service, "commandService", commandService);
        ReflectionTestUtils.setField(service, "wsOrderMapper", wsOrderMapper);
        ReflectionTestUtils.setField(service, "walletFlowMapper", walletFlowMapper);
        ReflectionTestUtils.setField(service, "outletMapper", outletMapper);
        ReflectionTestUtils.setField(service, "deviceMapper", deviceMapper);
        ReflectionTestUtils.setField(service, "domainEventService", domainEventService);
        ReflectionTestUtils.setField(service, "redis", redis);
        // E2E-08 归因快照协作方：mock 恒返回 null 推荐人（归因行为由 AttributionDbTest 锁定）
        ReflectionTestUtils.setField(service, "inviteService",
                Mockito.mock(com.jbk.serve.service.settlement.IInviteService.class));
    }

    @Test
    void cacheHitReturnsPersistedOrderWithoutReusingConsumedSession() {
        WsOrder existing = existingOrder();
        when(valueOperations.get(CACHE_KEY)).thenReturn(ORDER_NO);
        when(wsOrderMapper.selectOne(any())).thenReturn(existing);

        OrderDetailVo detail = service.createWaterOrder(request(), USER_ID);

        assertEquals(ORDER_NO, detail.getOrder().getOrderNo());
        verify(valueOperations).get(CACHE_KEY);
        verify(valueOperations, never()).get("order:req:" + REQUEST_ID);
        verifyIdempotentReplayOnlyBackfillsDispatch();
    }

    /**
     * S2：成功后再调价，同 requestId 重放仍须返回原订单原金额。
     *
     * <p>本用例真正要钉死的不变式是「幂等判定早于任何报价读取」——一旦有人把报价漂移闸挪到
     * 幂等检查之前，一笔已扣款成功的订单在后台调价后重放就会被 5411 打回，用户看到失败、
     * 钱却已经扣了。因此断言不能只看单号：还要证明重放路径压根没去读出水口现价
     * （outletMapper 零调用），且返回金额仍是首次冻结价算出的那一份。</p>
     */
    @Test
    void replayAfterPriceChangeStillReturnsOriginalOrderWithoutRecharging() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(ORDER_NO);
        when(wsOrderMapper.selectOne(any())).thenReturn(existingOrder());

        OrderDetailVo detail = service.createWaterOrder(request(), USER_ID);

        assertEquals(ORDER_NO, detail.getOrder().getOrderNo());
        // 金额仍取首次冻结价（20 分/升 × 5 升 = 100 分），绝不按现价重算
        assertEquals(100L, detail.getOrder().getOrderAmountFen());
        // 重放路径完全不读出水口档案：读了就意味着报价闸有机会介入，已成功订单可被改判
        verify(outletMapper, never()).selectById(any());
        // 不重新读会话、不重复扣卡、不重复建单
        verify(miniDeviceService, never()).loadScanSession(anyString(), any());
        verify(tradeOrderTxService, never()).createWaterOrder(any(), any(), anyString());
        verifyIdempotentReplayOnlyBackfillsDispatch();
    }

    @Test
    void cacheFailureStillReturnsPersistedOrderBeforeReadingConsumedSession() {
        when(valueOperations.get(CACHE_KEY)).thenThrow(new IllegalStateException("redis unavailable"));
        when(wsOrderMapper.selectOne(any())).thenReturn(existingOrder());

        OrderDetailVo detail = service.createWaterOrder(request(), USER_ID);

        assertEquals(ORDER_NO, detail.getOrder().getOrderNo());
        verifyIdempotentReplayOnlyBackfillsDispatch();
    }

    @Test
    void legacySnapshotWithoutWaterTypeUsesPersistedOutletBeforeConsumedSession() {
        WsOrder legacy = existingOrder()
                .setOutletId(31L)
                .setPackageSnap(JSONUtil.createObj()
                        .set("requestId", REQUEST_ID)
                        .set("unitPriceFenPerLiter", 20)
                        .set("planMl", PLAN_ML)
                        .set("payWay", PAY_WAY)
                        .toString());
        when(wsOrderMapper.selectOne(any())).thenReturn(legacy);
        when(outletMapper.selectById(31L)).thenReturn(new WsDeviceOutlet()
                .setId(31L)
                .setWaterTypeId(WATER_TYPE_ID));

        OrderDetailVo detail = service.createWaterOrder(request(), USER_ID);

        assertEquals(ORDER_NO, detail.getOrder().getOrderNo());
        verify(outletMapper).selectById(31L);
        verifyIdempotentReplayOnlyBackfillsDispatch();
    }

    @Test
    void sameRequestWithChangedBusinessParametersIsRejectedWithoutSideEffects() {
        when(wsOrderMapper.selectOne(any())).thenReturn(existingOrder());
        List<CreateWaterOrderBo> conflicts = List.of(
                request().setCardId(102L),
                request().setPlanMl(6_000L),
                request().setPayWay(TradeEnum.PayWay.CARD_ML.getValue()),
                request().setWaterTypeId(9L));

        for (CreateWaterOrderBo conflict : conflicts) {
            JbkException error = assertThrows(JbkException.class,
                    () -> service.createWaterOrder(conflict, USER_ID));
            assertEquals("同一请求参数已发生变化，请勿重复提交", error.getMessage());
        }

        verifyNoCreationSideEffects();
        verify(valueOperations, never()).set(anyString(), any(), anyLong(), any());
    }

    @Test
    void corruptedPersistedPriceAmountOrStatusIsRejectedWithoutSideEffects() {
        WsOrder badPrice = existingOrder().setPackageSnap(JSONUtil.createObj()
                .set("requestId", REQUEST_ID)
                .set("unitPriceFenPerLiter", -1)
                .set("planMl", PLAN_ML)
                .set("payWay", PAY_WAY)
                .set("waterTypeId", WATER_TYPE_ID)
                .toString());
        WsOrder badAmount = existingOrder().setOrderAmount(99L);
        WsOrder badStatus = existingOrder().setOrderStatus(TradeEnum.OrderStatus.UNPAID.getValue());
        when(wsOrderMapper.selectOne(any())).thenReturn(badPrice, badAmount, badStatus);

        for (int attempt = 0; attempt < 3; attempt++) {
            JbkException error = assertThrows(JbkException.class,
                    () -> service.createWaterOrder(request(), USER_ID));
            assertEquals("既有订单幂等数据异常，请联系客服处理", error.getMessage());
        }

        verifyNoCreationSideEffects();
    }

    @Test
    void duplicateKeyConcurrentLoserReReadsAndReturnsWinnerOnlyWhenParametersMatch() {
        when(wsOrderMapper.selectOne(any())).thenReturn(null, existingOrder());
        when(miniDeviceService.loadScanSession(REQUEST_ID, USER_ID)).thenReturn(scanSession());
        when(miniDeviceService.checkEligibility(REQUEST_ID, CARD_ID, USER_ID))
                .thenReturn(new WaterEligibilityVo().setAvailability("AVAILABLE"));
        when(outletMapper.selectById(31L)).thenReturn(new WsDeviceOutlet()
                .setId(31L)
                .setDeviceId(21L)
                .setWaterTypeId(WATER_TYPE_ID)
                .setOutletPrice("20"));
        when(deviceMapper.selectById(21L)).thenReturn(new WsDevice().setId(21L).setStationId(41L));
        when(tradeOrderTxService.createWaterOrder(any(), any(ScanSessionInfo.class), anyString()))
                .thenThrow(new DuplicateKeyException("uk_order_no"));

        OrderDetailVo detail = service.createWaterOrder(request(), USER_ID);

        assertEquals(ORDER_NO, detail.getOrder().getOrderNo());
        ArgumentCaptor<WsOrder> orderCaptor = ArgumentCaptor.forClass(WsOrder.class);
        verify(tradeOrderTxService).createWaterOrder(orderCaptor.capture(), any(ScanSessionInfo.class), anyString());
        assertEquals(WATER_TYPE_ID,
                JSONUtil.parseObj(orderCaptor.getValue().getPackageSnap()).getLong("waterTypeId"));
        verify(miniDeviceService, never()).consumeScanSession(anyString());
        // P0：输方拿到的赢方订单仍是 2 已支付且 CMD_ID 空——必须与普通幂等重试同口径补齐指令，
        // 不允许原样返回悬挂单（sendDispenseForOrder 内部 CAS 保证不会与赢方重复产生有效指令）。
        verify(commandService, times(1)).sendDispenseForOrder(61L);
        verify(valueOperations, never()).set(anyString(), any(), anyLong(), any());
    }

    /**
     * CARD-SCOPE ⑩ 的编排面回归：下单事务内校验失败（范围/归属/共键等任一原因抛出）时，
     * 扫码会话必须保持未消费、设备指令零下发、幂等缓存不写——用户重扫/重试仍可正常下单。
     */
    @Test
    void txRejectionLeavesScanSessionUnconsumedAndDispatchesNothing() {
        when(wsOrderMapper.selectOne(any())).thenReturn(null);
        when(miniDeviceService.loadScanSession(REQUEST_ID, USER_ID)).thenReturn(scanSession());
        when(miniDeviceService.checkEligibility(REQUEST_ID, CARD_ID, USER_ID))
                .thenReturn(new WaterEligibilityVo().setAvailability("AVAILABLE"));
        when(outletMapper.selectById(31L)).thenReturn(new WsDeviceOutlet()
                .setId(31L)
                .setDeviceId(21L)
                .setWaterTypeId(WATER_TYPE_ID)
                .setOutletPrice("20"));
        when(deviceMapper.selectById(21L)).thenReturn(new WsDevice().setId(21L).setStationId(41L));
        when(tradeOrderTxService.createWaterOrder(any(), any(ScanSessionInfo.class), anyString()))
                .thenThrow(new JbkException("该水卡不适用于当前设备，请更换设备或水卡"));

        JbkException error = assertThrows(JbkException.class,
                () -> service.createWaterOrder(request(), USER_ID));

        assertEquals("该水卡不适用于当前设备，请更换设备或水卡", error.getMessage());
        verify(miniDeviceService, never()).consumeScanSession(anyString());
        verify(commandService, never()).sendDispenseForOrder(any());
        verify(valueOperations, never()).set(anyString(), any(), anyLong(), any());
    }

    /** 预检返回卡侧阻断时编排层直接拒单：不进事务、不消费会话、不下发指令。 */
    @Test
    void cardBlockFromEligibilityRejectsBeforeTransaction() {
        when(wsOrderMapper.selectOne(any())).thenReturn(null);
        when(miniDeviceService.loadScanSession(REQUEST_ID, USER_ID)).thenReturn(scanSession());
        when(miniDeviceService.checkEligibility(REQUEST_ID, CARD_ID, USER_ID))
                .thenReturn(new WaterEligibilityVo().setAvailability("AVAILABLE")
                        .setCardBlock(new WaterEligibilityVo.CardBlockVo()
                                .setCode("CARD_NOT_ACCESSIBLE").setMessage("水卡不存在或无权使用")));

        JbkException error = assertThrows(JbkException.class,
                () -> service.createWaterOrder(request(), USER_ID));

        assertEquals("水卡不存在或无权使用", error.getMessage());
        verify(tradeOrderTxService, never()).createWaterOrder(any(), any(ScanSessionInfo.class), anyString());
        verify(miniDeviceService, never()).consumeScanSession(anyString());
        verify(commandService, never()).sendDispenseForOrder(any());
        // P1-C：非范围类卡阻断（归属/冻结等）不属范围拒绝审计，不得落可靠事件
        verify(domainEventService, never()).recordReliableOnce(any(), anyString(), anyString(), any(), any());
    }

    /**
     * P1-C 编排层真实提交面：预检复检给出 CARD_SCOPE_DENIED 时，
     * 必须以确定性幂等键落恰一条可靠拒绝审计，且不进事务、不消费会话、不下发指令。
     */
    @Test
    void scopeBlockedSubmitWritesSingleReliableAuditWithDeterministicKey() {
        when(wsOrderMapper.selectOne(any())).thenReturn(null);
        when(miniDeviceService.loadScanSession(REQUEST_ID, USER_ID)).thenReturn(scanSession());
        when(miniDeviceService.checkEligibility(REQUEST_ID, CARD_ID, USER_ID))
                .thenReturn(new WaterEligibilityVo().setAvailability("AVAILABLE")
                        .setCardBlock(new WaterEligibilityVo.CardBlockVo()
                                .setCode("CARD_SCOPE_DENIED")
                                .setMessage("该水卡不适用于当前设备，请更换设备或水卡")));

        JbkException error = assertThrows(JbkException.class,
                () -> service.createWaterOrder(request(), USER_ID));

        assertEquals("该水卡不适用于当前设备，请更换设备或水卡", error.getMessage());
        String derivedOrderNo = MiniOrderServiceImpl.buildOrderNo(USER_ID, REQUEST_ID);
        // 拒绝证据必须走独立提交变体：同键跨层/跨次撞键按已留痕处理，不做读回核验
        verify(domainEventService, times(1)).recordReliableOnceIndependent(
                eq(com.jbk.tool.consts.ops.OpsEnum.EventType.ORDER_STATUS),
                eq(derivedOrderNo), eq("CARD_SCOPE_DENY:" + derivedOrderNo), any(), any());
        verify(domainEventService, never()).recordReliableOnce(any(), any(), any(), any(), any());
        verify(tradeOrderTxService, never()).createWaterOrder(any(), any(ScanSessionInfo.class), anyString());
        verify(miniDeviceService, never()).consumeScanSession(anyString());
        verify(commandService, never()).sendDispenseForOrder(any());
    }

    private ScanSessionInfo scanSession() {
        return new ScanSessionInfo()
                // S2：会话冻结报价——下单装配的单价/水种唯一来源
                .setScanSessionId(REQUEST_ID)
                .setWaterTypeId(WATER_TYPE_ID)
                .setUnitPriceFenPerLiter(20)
                .setQuotedAt("20260723120000")
                .setUserId(USER_ID)
                .setQrcodeId(11L)
                .setStationId(41L)
                .setDeviceId(21L)
                .setOutletId(31L);
    }

    @Test
    void cacheKeysAreIsolatedByUser() {
        assertEquals(CACHE_KEY, MiniOrderServiceImpl.buildRequestCacheKey(USER_ID, REQUEST_ID));
        assertTrue(!MiniOrderServiceImpl.buildRequestCacheKey(10L, REQUEST_ID).equals(CACHE_KEY));
    }

    private CreateWaterOrderBo request() {
        return new CreateWaterOrderBo()
                .setScanSessionId(REQUEST_ID)
                .setCardId(CARD_ID)
                .setWaterTypeId(WATER_TYPE_ID)
                .setPlanMl(PLAN_ML)
                .setPayWay(PAY_WAY);
    }

    private WsOrder existingOrder() {
        WsOrder order = new WsOrder()
                .setId(61L)
                .setOrderNo(ORDER_NO)
                .setOrderType(TradeEnum.OrderType.WATER.getValue())
                .setUserId(USER_ID)
                .setCardId(CARD_ID)
                .setPlanMl(PLAN_ML)
                .setOrderAmount(100L)
                .setPayWay(PAY_WAY)
                .setOrderStatus(TradeEnum.OrderStatus.PAID.getValue())
                .setPackageSnap(JSONUtil.createObj()
                        .set("requestId", REQUEST_ID)
                        .set("unitPriceFenPerLiter", 20)
                        .set("planMl", PLAN_ML)
                        .set("payWay", PAY_WAY)
                        .set("waterTypeId", WATER_TYPE_ID)
                        .toString());
        order.setCreateTime("20260722090000");
        order.setUpdateTime("20260722090000");
        return order;
    }

    private void verifyNoCreationSideEffects() {
        verify(miniDeviceService, never()).loadScanSession(anyString(), any());
        verify(miniDeviceService, never()).checkEligibility(anyString(), any(), any());
        verify(miniDeviceService, never()).consumeScanSession(anyString());
        verify(tradeOrderTxService, never()).createWaterOrder(any(), any(ScanSessionInfo.class), anyString());
        verify(commandService, never()).sendDispenseForOrder(any());
        verify(domainEventService, never()).record(any(), anyString(), any(), anyString());
    }

    /**
     * P0 后的幂等重放口径：不重读会话、不重扣卡、不写缓存，但对「2 已支付且 CMD_ID 空」的既有订单
     * 必须触发一次幂等补发（sendDispenseForOrder 内部 CMD_ID 抢占 CAS 保证不产生第二条有效指令）。
     */
    private void verifyIdempotentReplayOnlyBackfillsDispatch() {
        verify(miniDeviceService, never()).loadScanSession(anyString(), any());
        verify(miniDeviceService, never()).checkEligibility(anyString(), any(), any());
        verify(miniDeviceService, never()).consumeScanSession(anyString());
        verify(tradeOrderTxService, never()).createWaterOrder(any(), any(ScanSessionInfo.class), anyString());
        verify(commandService, times(1)).sendDispenseForOrder(61L);
        verify(domainEventService, never()).record(any(), anyString(), any(), anyString());
    }
}

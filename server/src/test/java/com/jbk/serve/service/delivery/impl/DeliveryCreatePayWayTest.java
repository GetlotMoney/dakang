package com.jbk.serve.service.delivery.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jbk.serve.mapper.delivery.WsDeliveryAutoRuleMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.product.WsWaterTypeMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.delivery.DeliveryOrderNo;
import com.jbk.serve.service.delivery.IDeliveryOrderService;
import com.jbk.serve.service.delivery.IDeliveryOrderTxService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.data.delivery.bo.DeliveryCreateBo;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.product.po.WsWaterType;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-214 创单支付方式结构层单测（MiniRecharge 系列同风格 mock/假库；
 * 资金事务本体的 DB 事实由 {@link DeliveryOrderTxDbTest} 钉住，这里只证编排层：
 * payWay 白名单、null→2 兼容老验收包、金额口径装配、同 requestId 改 payWay 零副作用拒绝）。
 */
class DeliveryCreatePayWayTest {

    private static final Long ME = 9L;
    private static final String REQ = "1f4a2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b";

    private WsOrderMapper orderMapper;
    private WsDeliveryTaskMapper taskMapper;
    private WsStationMapper stationMapper;
    private WsWaterTypeMapper waterTypeMapper;
    private WsDeliveryAutoRuleMapper autoRuleMapper;
    private IDeliveryOrderTxService orderTxService;
    private IWsDomainEventService domainEventService;
    private DeliveryOrderServiceImpl service;

    @BeforeEach
    void setup() {
        orderMapper = Mockito.mock(WsOrderMapper.class);
        taskMapper = Mockito.mock(WsDeliveryTaskMapper.class);
        stationMapper = Mockito.mock(WsStationMapper.class);
        waterTypeMapper = Mockito.mock(WsWaterTypeMapper.class);
        autoRuleMapper = Mockito.mock(WsDeliveryAutoRuleMapper.class);
        orderTxService = Mockito.mock(IDeliveryOrderTxService.class);
        domainEventService = Mockito.mock(IWsDomainEventService.class);
        service = new DeliveryOrderServiceImpl();
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "stationMapper", stationMapper);
        ReflectionTestUtils.setField(service, "waterTypeMapper", waterTypeMapper);
        ReflectionTestUtils.setField(service, "autoRuleMapper", autoRuleMapper);
        ReflectionTestUtils.setField(service, "orderTxService", orderTxService);
        ReflectionTestUtils.setField(service, "domainEventService", domainEventService);
        // E2E-08 归因快照协作方：mock 恒返回 null 推荐人（归因行为由 AttributionDbTest 锁定）
        ReflectionTestUtils.setField(service, "inviteService",
                Mockito.mock(com.jbk.serve.service.settlement.IInviteService.class));

        when(orderMapper.selectOne(any())).thenReturn(null);
        WsStation station = new WsStation();
        station.setId(41L);
        station.setStationStatus(1);
        when(stationMapper.selectById(41L)).thenReturn(station);
        WsWaterType waterType = new WsWaterType();
        waterType.setId(8L);
        waterType.setWaterName("纯净水");
        waterType.setWaterStatus(1);
        when(waterTypeMapper.selectById(8L)).thenReturn(waterType);
        when(orderTxService.createPaidDeliveryOrder(any(), any(), any(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private DeliveryCreateBo bo(Integer payWay) {
        DeliveryCreateBo bo = new DeliveryCreateBo();
        bo.setRequestId(REQ);
        bo.setCardId("100");
        bo.setStationId("41");
        bo.setWaterTypeId("8");
        bo.setContainerSpec("20L桶");
        bo.setDeliveryCount(2);
        bo.setPlanReturnCount(1);
        bo.setReceiveAddress("光谷软件园 A1 栋 502");
        bo.setReceivePhone("13900001111");
        bo.setDeliveryMode(1);
        bo.setPayWay(payWay);
        return bo;
    }

    // ================= 1：payWay 白名单（结构校验，任何非法值零副作用拒绝） =================

    @Test
    void invalidPayWayIsRejectedWithZeroSideEffects() {
        for (Integer illegal : new Integer[]{0, 1, 4, -2, 99}) {
            JbkException ex = assertThrows(JbkException.class,
                    () -> service.createDeliveryOrder(bo(illegal), ME), "payWay=" + illegal);
            assertTrue(ex.getMessage().contains("配送支付方式不合法"), "实际=" + ex.getMessage());
        }
        verify(orderTxService, never()).createPaidDeliveryOrder(any(), any(), any(), anyString());
    }

    // ================= 2：null → 2 兼容老验收包，金额口径与既有语义逐字段一致 =================

    @Test
    void nullPayWayDefaultsToBalanceWithLegacyAmounts() {
        service.createDeliveryOrder(bo(null), ME);

        ArgumentCaptor<WsOrder> orderCap = ArgumentCaptor.forClass(WsOrder.class);
        ArgumentCaptor<WsDeliveryTask> taskCap = ArgumentCaptor.forClass(WsDeliveryTask.class);
        verify(orderTxService).createPaidDeliveryOrder(orderCap.capture(), taskCap.capture(), any(), anyString());
        WsOrder order = orderCap.getValue();
        WsDeliveryTask task = taskCap.getValue();
        assertEquals(2, order.getPayWay(), "老验收包无 payWay 字段，按 2 兼容");
        assertEquals(3_000L, order.getOrderAmount(), "全余额：ORDER_AMOUNT=水费+配送费");
        assertEquals(2_600L, task.getWaterAmount());
        assertEquals(400L, task.getDeliveryFee());
        JSONObject snap = JSONUtil.parseObj(order.getPackageSnap());
        assertEquals(2, snap.getInt("payWay"));
        assertEquals(2_600L, snap.getLong("waterAmountFen"), "payWay=2 实际应扣水费=价目水费");
        assertEquals(2_600L, snap.getLong("priceWaterAmountFen"));
        assertEquals(40_000L, snap.getLong("waterMl"));
    }

    // ================= 3：payWay=3 金额口径（E2E-03 规则2 修订） =================

    @Test
    void mlPayWayBuildsFeeOnlyAmountAndZeroTaskWaterAmount() {
        service.createDeliveryOrder(bo(3), ME);

        ArgumentCaptor<WsOrder> orderCap = ArgumentCaptor.forClass(WsOrder.class);
        ArgumentCaptor<WsDeliveryTask> taskCap = ArgumentCaptor.forClass(WsDeliveryTask.class);
        verify(orderTxService).createPaidDeliveryOrder(orderCap.capture(), taskCap.capture(), any(), anyString());
        WsOrder order = orderCap.getValue();
        WsDeliveryTask task = taskCap.getValue();
        assertEquals(3, order.getPayWay());
        assertEquals(400L, order.getOrderAmount(), "混合结算：ORDER_AMOUNT=配送费，水费以水量抵扣");
        assertEquals(0L, task.getWaterAmount(), "任务行 WATER_AMOUNT 同口径记 0");
        assertEquals(400L, task.getDeliveryFee(), "DELIVERY_FEE 不变");
        JSONObject snap = JSONUtil.parseObj(order.getPackageSnap());
        assertEquals(3, snap.getInt("payWay"));
        assertEquals(0L, snap.getLong("waterAmountFen"), "快照水费=实际应扣口径");
        assertEquals(2_600L, snap.getLong("priceWaterAmountFen"), "价目参考另存，供展示与 E2E-04 折算");
        assertEquals(40_000L, snap.getLong("waterMl"), "20L桶×2 → 40000ml 抵扣量");
    }

    // ================= 4：同 requestId 改 payWay 是冲突不是重放（零副作用） =================

    @Test
    void replayWithChangedPayWayIsRejectedWithZeroSideEffects() {
        WsOrder existing = existingBalanceOrder(snapWithPayWay(2));
        when(orderMapper.selectOne(any())).thenReturn(existing);
        when(taskMapper.selectOne(any())).thenReturn(existingTask());

        JbkException ex = assertThrows(JbkException.class, () -> service.createDeliveryOrder(bo(3), ME));
        assertTrue(ex.getMessage().contains("同一 requestId 不可更换配送参数"), "实际=" + ex.getMessage());
        verify(orderTxService, never()).createPaidDeliveryOrder(any(), any(), any(), anyString());

        // 对照：同参（payWay=2 / 缺省）重放命中原单，仍零写入
        assertEquals(existing.getOrderNo(),
                service.createDeliveryOrder(bo(2), ME).order().getOrderNo());
        assertEquals(existing.getOrderNo(),
                service.createDeliveryOrder(bo(null), ME).order().getOrderNo());
        verify(orderTxService, never()).createPaidDeliveryOrder(any(), any(), any(), anyString());
    }

    /** 旧单快照缺 payWay 键（封板前落库）：按 2 对待——payWay=2/null 重放命中，payWay=3 拒绝。 */
    @Test
    void legacySnapshotWithoutPayWayIsTreatedAsBalance() {
        WsOrder existing = existingBalanceOrder(snapWithPayWay(null));
        when(orderMapper.selectOne(any())).thenReturn(existing);
        when(taskMapper.selectOne(any())).thenReturn(existingTask());

        assertEquals(existing.getOrderNo(),
                service.createDeliveryOrder(bo(null), ME).order().getOrderNo());
        assertEquals(existing.getOrderNo(),
                service.createDeliveryOrder(bo(2), ME).order().getOrderNo());
        JbkException ex = assertThrows(JbkException.class, () -> service.createDeliveryOrder(bo(3), ME));
        assertTrue(ex.getMessage().contains("同一 requestId 不可更换配送参数"), "实际=" + ex.getMessage());
        verify(orderTxService, never()).createPaidDeliveryOrder(any(), any(), any(), anyString());
    }

    // ================= 5：自动补货边界（规则表无支付方式列，fail-closed） =================

    @Test
    void autoRefillRejectsMlPayWay() {
        DeliveryCreateBo auto = bo(3);
        auto.setDeliveryMode(3);
        auto.setAutoRefillIntervalDays(7);
        JbkException ex = assertThrows(JbkException.class, () -> service.createDeliveryOrder(auto, ME));
        assertTrue(ex.getMessage().contains("自动补货暂仅支持水卡余额支付"), "实际=" + ex.getMessage());
        verify(orderTxService, never()).createPaidDeliveryOrder(any(), any(), any(), anyString());
    }

    // ================= 既有单与任务夹具（与生产装配同形） =================

    private String snapWithPayWay(Integer payWay) {
        JSONObject snap = JSONUtil.createObj()
                .set("requestId", REQ)
                .set("containerSpec", "20L桶")
                .set("deliveryCount", 2)
                .set("planReturnCount", 1)
                .set("waterTypeId", 8L)
                .set("unitWaterPriceFen", 1300L)
                .set("deliveryFeePerContainerFen", 200L)
                .set("waterAmountFen", 2600L)
                .set("deliveryFeeFen", 400L)
                .set("deliveryMode", 1);
        if (payWay != null) {
            snap.set("payWay", payWay).set("waterMl", 40_000L).set("priceWaterAmountFen", 2600L);
        }
        return snap.toString();
    }

    private WsOrder existingBalanceOrder(String snap) {
        WsOrder order = new WsOrder()
                .setOrderNo(DeliveryOrderNo.derive(ME, REQ))
                .setOrderType(3)
                .setUserId(ME)
                .setStationId(41L)
                .setCardId(100L)
                .setPackageSnap(snap)
                .setOrderAmount(3_000L)
                .setPayWay(2)
                .setOrderStatus(2);
        order.setId(1L);
        return order;
    }

    private WsDeliveryTask existingTask() {
        return new WsDeliveryTask()
                .setOrderId(1L)
                .setUserId(ME)
                .setStationId(41L)
                .setWaterAmount(2_600L)
                .setDeliveryFee(400L)
                .setReceiveAddress("光谷软件园 A1 栋 502")
                .setReceivePhone("13900001111")
                .setTaskNo("WDT-1");
    }
}

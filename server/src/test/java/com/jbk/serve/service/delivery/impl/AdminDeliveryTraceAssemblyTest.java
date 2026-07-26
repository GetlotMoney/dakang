package com.jbk.serve.service.delivery.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryExceptionMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryMediaMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.serve.service.delivery.IDeliveryAppealTxService;
import com.jbk.serve.service.message.IWsMessageService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.data.delivery.bo.DeliveryAppealDecideBo;
import com.jbk.tool.data.delivery.po.WsDeliveryAppeal;
import com.jbk.tool.data.delivery.po.WsDeliveryException;
import com.jbk.tool.data.delivery.po.WsDeliveryMedia;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.delivery.vo.AdminDeliveryAppealEvidenceVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryAppealItemVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryOrderTraceVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryTaskDetailVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryTaskItemVo;
import com.jbk.tool.data.message.po.WsMessage;
import com.jbk.tool.data.ops.po.WsDomainEvent;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.trade.vo.AdminOrderItemVo;
import com.jbk.tool.data.user.po.WsCourier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * E2E-03 包C 追溯/详情/证据装配层测试（纯 Mockito，不连库）。
 *
 * <p>钉住：ok 路径的脱敏投影与证据齐全；履约链 mismatch 隐藏全部正向证据但保留审计；
 * 资金链独立核验（履约 ok + 流水缺失 = 仅 payment mismatch）；裁决入口只透传包A 事务、
 * 本层零写入。</p>
 */
class AdminDeliveryTraceAssemblyTest {

    static {
        // LambdaQueryWrapper 需要实体表信息缓存（与 AdminOrderFlowAfterTest 同手法）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WsDeliveryTask.class);
        TableInfoHelper.initTableInfo(assistant, WsDeliveryAppeal.class);
        TableInfoHelper.initTableInfo(assistant, WsDeliveryException.class);
        TableInfoHelper.initTableInfo(assistant, WsDeliveryMedia.class);
        TableInfoHelper.initTableInfo(assistant, WsWalletFlow.class);
        TableInfoHelper.initTableInfo(assistant, WsMessage.class);
        TableInfoHelper.initTableInfo(assistant, WsDomainEvent.class);
    }

    private WsDeliveryTaskMapper taskMapper;
    private WsDeliveryAppealMapper appealMapper;
    private WsDeliveryExceptionMapper exceptionMapper;
    private WsDeliveryMediaMapper mediaMapper;
    private WsOrderMapper orderMapper;
    private WsWalletFlowMapper walletFlowMapper;
    private WsCourierMapper courierMapper;
    private IWsMessageService messageService;
    private IWsDomainEventService domainEventService;
    private IDeliveryAppealTxService appealTxService;
    private AdminDeliveryServiceImpl service;

    @BeforeEach
    void setup() {
        taskMapper = Mockito.mock(WsDeliveryTaskMapper.class);
        appealMapper = Mockito.mock(WsDeliveryAppealMapper.class);
        exceptionMapper = Mockito.mock(WsDeliveryExceptionMapper.class);
        mediaMapper = Mockito.mock(WsDeliveryMediaMapper.class);
        orderMapper = Mockito.mock(WsOrderMapper.class);
        walletFlowMapper = Mockito.mock(WsWalletFlowMapper.class);
        courierMapper = Mockito.mock(WsCourierMapper.class);
        messageService = Mockito.mock(IWsMessageService.class);
        domainEventService = Mockito.mock(IWsDomainEventService.class);
        appealTxService = Mockito.mock(IDeliveryAppealTxService.class);
        service = new AdminDeliveryServiceImpl(taskMapper, appealMapper, exceptionMapper,
                mediaMapper, orderMapper, walletFlowMapper, courierMapper,
                messageService, domainEventService, appealTxService);
    }

    // ==================== 造数 ====================

    private AdminOrderItemVo adminOrder() {
        AdminOrderItemVo order = new AdminOrderItemVo();
        order.setId(11L);
        order.setOrderNo("WDPKGC001");
        order.setOrderType(3);
        order.setUserId(6L);
        order.setUserName("张女士");
        order.setUserPhone("13900001111");
        order.setStationName("光谷软件园水站");
        order.setOrderAmount(4200L);
        order.setPayWay(2);
        order.setOrderStatus(4);
        return order;
    }

    private WsOrder orderPo(int orderStatus) {
        WsOrder order = new WsOrder()
                .setOrderNo("WDPKGC001")
                .setOrderType(3)
                .setUserId(6L)
                .setStationId(1L)
                .setCardId(9L)
                .setOrderAmount(4200L)
                .setPayWay(2)
                .setOrderStatus(orderStatus);
        order.setId(11L);
        order.setDataStatus(0);
        order.setCreateTime("20260724100000");
        return order;
    }

    private WsDeliveryTask signedTask() {
        WsDeliveryTask task = new WsDeliveryTask()
                .setTaskNo("DTPKGC001")
                .setOrderId(11L)
                .setUserId(6L)
                .setStationId(1L)
                .setCourierId(3L)
                .setWaterType("纯净水")
                .setContainerSpec("10L桶")
                .setDeliveryCount(3)
                .setPlanReturnCount(1)
                .setActualDeliveryCount(3)
                .setActualReturnCount(1)
                .setWaterAmount(3600L)
                .setDeliveryFee(600L)
                .setReceiveAddress("光谷软件园 A1 栋 502")
                .setReceivePhone("13700003333")
                .setTaskStatus(5)
                .setVersion(5)
                .setAcceptTime("20260724101000")
                .setDepartTime("20260724102000")
                .setArriveTime("20260724103000")
                .setSignTime("20260724104000")
                .setAppealDeadline("20260725104000")
                .setLocationStatus(2)
                .setSignPhotos("[{\"type\":1,\"mediaKey\":\"DMA\",\"time\":\"20260724104000\"},"
                        + "{\"type\":2,\"mediaKey\":\"DMB\",\"time\":\"20260724104000\"},"
                        + "{\"type\":3,\"mediaKey\":\"DMC\",\"time\":\"20260724104000\"}]");
        task.setId(77L);
        task.setDataStatus(0);
        task.setCreateTime("20260724100000");
        return task;
    }

    private WsCourier courier() {
        WsCourier courier = new WsCourier()
                .setUserId(66L)
                .setCourierName("李配送")
                .setCourierPhone("13800002222");
        courier.setId(3L);
        return courier;
    }

    private WsWalletFlow flow() {
        WsWalletFlow flow = new WsWalletFlow()
                .setCardId(9L)
                .setUserId(6L)
                .setFlowType(7)
                .setAmountChange(-4200L)
                .setMlChange(0L)
                .setAmountAfter(800L)
                .setOrderId(11L)
                .setFlowRemark("水配送 WDPKGC001")
                .setBizIdempotencyKey("DELIVERY:WDPKGC001");
        flow.setId(500L);
        flow.setCreateTime("20260724100000");
        return flow;
    }

    private WsDeliveryAppeal decidedAppeal() {
        WsDeliveryAppeal appeal = new WsDeliveryAppeal()
                .setTaskId(77L)
                .setOrderId(11L)
                .setUserId(6L)
                .setAppealReason("QUANTITY")
                .setAppealDesc("实收两桶")
                .setReceivedCount(2)
                .setAppealStatus(5)
                .setHandleBy(1L)
                .setHandleTime("20260724120000")
                .setHandleResult("核实少送一桶，补送待执行");
        appeal.setId(300L);
        appeal.setCreateTime("20260724110000");
        return appeal;
    }

    private WsDeliveryMedia media(String key, Long boundTaskId, int purpose, long ownerUserId) {
        WsDeliveryMedia media = new WsDeliveryMedia()
                .setMediaKey(key)
                .setOwnerUserId(ownerUserId)
                .setMediaPurpose(purpose)
                .setSizeBytes(1024L)
                .setMimeType("image/jpeg")
                .setBoundTaskId(boundTaskId);
        media.setCreateTime("20260724103900");
        return media;
    }

    private WsDomainEvent event(long id, int type, String key, String payload, String actorRole) {
        WsDomainEvent event = new WsDomainEvent();
        event.setId(id);
        event.setEventType(type);
        event.setEventKey(key);
        event.setEventPayload(payload);
        event.setActorRole(actorRole);
        event.setCreateTime("20260724104000");
        return event;
    }

    // ==================== 追溯装配 ====================

    @Test
    void okTraceProjectsMaskedEvidencePaymentAndAudit() {
        when(orderMapper.selectById(11L)).thenReturn(orderPo(4));
        when(taskMapper.selectOne(any())).thenReturn(signedTask());
        when(appealMapper.selectList(any())).thenReturn(List.of(decidedAppeal()));
        when(courierMapper.selectById(3L)).thenReturn(courier());
        when(walletFlowMapper.selectOne(any())).thenReturn(flow());
        when(mediaMapper.selectList(any())).thenReturn(List.of(
                media("DMA", 77L, 1, 66L), media("DMB", 77L, 1, 66L), media("DMC", 77L, 1, 66L)));
        WsMessage message = new WsMessage();
        message.setId(900L);
        message.setMsgTitle("订单已签收");
        message.setMsgContent("订单 WDPKGC001 已完成三照签收");
        message.setSendStatus(4);
        message.setSendTime("20260724104000");
        message.setObjectType("order");
        message.setObjectId("WDPKGC001");
        when(messageService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(message));
        when(domainEventService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(
                        event(1L, 1, "WDPKGC001", "{\"new\":\"配送创单成功\",\"time\":\"20260724100000\"}", null),
                        event(2L, 5, "DTPKGC001",
                                "{\"old\":\"4:已送达待确认\",\"new\":\"5:已签收\",\"time\":\"20260724104000\"}", "配送端")));

        AdminDeliveryOrderTraceVo trace = service.buildOrderTrace(adminOrder());

        assertEquals("ok", trace.getDelivery().getLinkStatus());
        // 脱敏唯一实现：三个号码全部打码，明文不出接口
        assertEquals("139****1111", trace.getDelivery().getUserMaskedPhone());
        assertEquals("138****2222", trace.getDelivery().getCourierMaskedPhone());
        assertEquals("137****3333", trace.getDelivery().getReceiveMaskedPhone());
        assertEquals(4200L, trace.getDelivery().getTotalAmountFen());
        assertEquals(5, trace.getDelivery().getTimeline().size());
        assertTrue(trace.getDelivery().getTimeline().stream().allMatch(n -> Boolean.TRUE.equals(n.getDone())));
        assertEquals(3, trace.getDelivery().getSignPhotos().size());
        assertEquals("ok", trace.getDelivery().getSignPhotos().get(0).getMediaStatus());
        assertEquals("image/jpeg", trace.getDelivery().getSignPhotos().get(0).getMimeType());
        assertEquals("ok", trace.getDelivery().getPayment().getFlowStatus());
        assertEquals(500L, trace.getDelivery().getPayment().getFlowId());
        assertEquals(1, trace.getDelivery().getNotifications().size());
        assertEquals(1, trace.getAppeals().size());
        assertEquals("ok", trace.getAppeals().get(0).getLinkStatus());
        assertEquals("数量不符", trace.getAppeals().get(0).getAppealReasonLabel());
        assertEquals(2, trace.getAuditEvents().size());
        assertEquals("订单状态变化", trace.getAuditEvents().get(0).getEventTypeLabel());
        assertEquals("配送创单成功", trace.getAuditEvents().get(0).getDetail());
        assertEquals("4:已送达待确认 → 5:已签收", trace.getAuditEvents().get(1).getDetail());
        assertEquals("配送端", trace.getAuditEvents().get(1).getActorLabel());
    }

    @Test
    void linkMismatchHidesPositiveEvidenceButKeepsAuditAndFlagsAppeals() {
        when(orderMapper.selectById(11L)).thenReturn(orderPo(4));
        // 收货用户与订单归属错位：履约链断裂
        when(taskMapper.selectOne(any())).thenReturn(signedTask().setUserId(7L));
        when(appealMapper.selectList(any())).thenReturn(List.of(decidedAppeal()));
        when(domainEventService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(event(1L, 5, "DTPKGC001", "{\"new\":\"x\"}", null)));

        AdminDeliveryOrderTraceVo trace = service.buildOrderTrace(adminOrder());

        assertEquals("mismatch", trace.getDelivery().getLinkStatus());
        assertNotNull(trace.getDelivery().getLinkReason());
        // fail-closed：正向证据全部不下发
        assertNull(trace.getDelivery().getCourierName());
        assertNull(trace.getDelivery().getTimeline());
        assertNull(trace.getDelivery().getSignPhotos());
        assertNull(trace.getDelivery().getPayment());
        assertNull(trace.getDelivery().getNotifications());
        assertNull(trace.getDelivery().getTotalAmountFen());
        // 申诉行同链 fail-closed：只留ID与原因
        assertEquals("mismatch", trace.getAppeals().get(0).getLinkStatus());
        assertNull(trace.getAppeals().get(0).getAppealDesc());
        // 审计是事实记录，异常时保留供排查
        assertEquals(1, trace.getAuditEvents().size());
        // 资金/媒体/消息在断链时不查询（零多余IO，也防拼凑）
        verifyNoInteractions(walletFlowMapper);
        verifyNoInteractions(mediaMapper);
        verifyNoInteractions(messageService);
    }

    @Test
    void missingTaskYieldsMismatchWithOrderKeyedAuditOnly() {
        when(orderMapper.selectById(11L)).thenReturn(orderPo(2));
        when(taskMapper.selectOne(any())).thenReturn(null);
        when(appealMapper.selectList(any())).thenReturn(List.of());
        when(domainEventService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of());

        AdminDeliveryOrderTraceVo trace = service.buildOrderTrace(adminOrder());

        assertEquals("mismatch", trace.getDelivery().getLinkStatus());
        assertTrue(trace.getDelivery().getLinkReason().contains("未关联任何配送任务"));
        assertNull(trace.getDelivery().getTaskId());
        assertTrue(trace.getAppeals().isEmpty());
    }

    @Test
    void fulfillmentOkButMissingFlowShowsPaymentMismatchOnly() {
        when(orderMapper.selectById(11L)).thenReturn(orderPo(4));
        when(taskMapper.selectOne(any())).thenReturn(signedTask());
        when(appealMapper.selectList(any())).thenReturn(List.of());
        when(courierMapper.selectById(3L)).thenReturn(courier());
        when(walletFlowMapper.selectOne(any())).thenReturn(null);
        when(mediaMapper.selectList(any())).thenReturn(List.of(
                media("DMA", 77L, 1, 66L), media("DMB", 77L, 1, 66L), media("DMC", 77L, 1, 66L)));
        when(messageService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of());
        when(domainEventService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of());

        AdminDeliveryOrderTraceVo trace = service.buildOrderTrace(adminOrder());

        // 履约链与资金链独立：履约证据成立，资金证据缺失只标记 payment
        assertEquals("ok", trace.getDelivery().getLinkStatus());
        assertNotNull(trace.getDelivery().getTimeline());
        assertEquals("mismatch", trace.getDelivery().getPayment().getFlowStatus());
        assertTrue(trace.getDelivery().getPayment().getFlowReason().contains("DELIVERY:WDPKGC001"));
        assertNull(trace.getDelivery().getPayment().getFlowId());
    }

    // ==================== 任务详情 ====================

    private AdminDeliveryTaskItemVo adminTaskItem() {
        AdminDeliveryTaskItemVo item = new AdminDeliveryTaskItemVo();
        item.setTaskId(77L);
        item.setTaskNo("DTPKGC001");
        item.setOrderId(11L);
        item.setOrderNo("WDPKGC001");
        item.setOrderStatus(4);
        item.setTaskStatus(5);
        item.setUserPhoneRaw("13900001111");
        item.setReceivePhoneRaw("13700003333");
        item.setCourierId(3L);
        item.setCourierName("李配送");
        item.setCourierPhoneRaw("13800002222");
        item.setWaterAmountFen(3600L);
        item.setDeliveryFeeFen(600L);
        return item;
    }

    @Test
    void taskDetailOkProjectsTimelinePhotosAndExceptions() {
        when(taskMapper.selectAdminTaskById(77L)).thenReturn(adminTaskItem());
        when(taskMapper.selectById(77L)).thenReturn(signedTask());
        when(orderMapper.selectById(11L)).thenReturn(orderPo(4));
        when(appealMapper.selectCount(any())).thenReturn(0L);
        when(courierMapper.selectById(3L)).thenReturn(courier());
        when(mediaMapper.selectList(any())).thenReturn(List.of(
                media("DMA", 77L, 1, 66L), media("DMB", 77L, 1, 66L), media("DMC", 77L, 1, 66L)));
        WsDeliveryException exception = new WsDeliveryException()
                .setTaskId(77L)
                .setCourierId(3L)
                .setExceptionReason(1)
                .setExceptionDesc("联系不上用户，已电话三次");
        exception.setId(600L);
        exception.setCreateTime("20260724102500");
        when(exceptionMapper.selectList(any())).thenReturn(List.of(exception));

        AdminDeliveryTaskDetailVo detail = service.taskDetail(77L);

        assertEquals("ok", detail.getLinkStatus());
        assertEquals("139****1111", detail.getUserMaskedPhone());
        assertNull(detail.getUserPhoneRaw());
        assertEquals(4200L, detail.getTotalAmountFen());
        assertEquals(5, detail.getTimeline().size());
        assertEquals(3, detail.getSignPhotos().size());
        assertEquals("门牌照", detail.getSignPhotos().get(0).getTypeLabel());
        assertEquals(1, detail.getExceptions().size());
        assertEquals("联系不上用户", detail.getExceptions().get(0).getReasonLabel());
    }

    @Test
    void taskDetailFailClosedKeepsIdentifiersOnly() {
        when(taskMapper.selectAdminTaskById(77L)).thenReturn(adminTaskItem());
        // 任务外键指向他单：共键断裂
        when(taskMapper.selectById(77L)).thenReturn(signedTask().setOrderId(12L));
        when(orderMapper.selectById(12L)).thenReturn(null);
        when(appealMapper.selectCount(any())).thenReturn(0L);

        AdminDeliveryTaskDetailVo detail = service.taskDetail(77L);

        assertEquals("mismatch", detail.getLinkStatus());
        assertNotNull(detail.getLinkReason());
        assertEquals(77L, detail.getTaskId());
        assertEquals("DTPKGC001", detail.getTaskNo());
        // 正向证据（配送员/费用/地址/时间线/三照）全部不下发
        assertNull(detail.getCourierName());
        assertNull(detail.getWaterAmountFen());
        assertNull(detail.getReceiveAddress());
        assertNull(detail.getTimeline());
        assertNull(detail.getSignPhotos());
    }

    // ==================== 申诉证据与裁决入口 ====================

    private AdminDeliveryAppealItemVo adminAppealItem() {
        AdminDeliveryAppealItemVo item = new AdminDeliveryAppealItemVo();
        item.setAppealId(300L);
        item.setTaskId(77L);
        item.setOrderId(11L);
        item.setUserId(6L);
        item.setUserPhoneRaw("13900001111");
        item.setAppealReason("QUANTITY");
        item.setAppealStatus(1);
        return item;
    }

    private WsDeliveryAppeal pendingAppealWithEvidence() {
        WsDeliveryAppeal appeal = new WsDeliveryAppeal()
                .setTaskId(77L)
                .setOrderId(11L)
                .setUserId(6L)
                .setAppealReason("QUANTITY")
                .setAppealDesc("实收两桶")
                .setReceivedCount(2)
                .setAppealStatus(1)
                .setAppealPhotos("[\"DMU1\"]")
                .setCourierEvidences("[{\"description\":\"三照齐全\",\"evidenceRefs\":[\"DMK1\"],"
                        + "\"time\":\"20260724113000\"}]");
        appeal.setId(300L);
        appeal.setCreateTime("20260724110000");
        return appeal;
    }

    @Test
    void appealEvidenceOkProjectsUserAndCourierMedia() {
        when(appealMapper.selectAdminAppealById(300L)).thenReturn(adminAppealItem());
        when(appealMapper.selectById(300L)).thenReturn(pendingAppealWithEvidence());
        WsDeliveryTask appealing = signedTask().setTaskStatus(7).setVersion(6);
        when(taskMapper.selectById(77L)).thenReturn(appealing);
        when(orderMapper.selectById(11L)).thenReturn(orderPo(4));
        when(courierMapper.selectById(3L)).thenReturn(courier());
        // 用户举证归属用户6；配送员举证归属配送员用户66；签收三照归属66
        when(mediaMapper.selectList(any())).thenReturn(List.of(
                media("DMU1", 77L, 2, 6L), media("DMK1", 77L, 2, 66L),
                media("DMA", 77L, 1, 66L), media("DMB", 77L, 1, 66L), media("DMC", 77L, 1, 66L)));
        // 嵌套 taskDetail 复用
        when(taskMapper.selectAdminTaskById(77L)).thenReturn(adminTaskItem());
        when(appealMapper.selectCount(any())).thenReturn(1L);
        when(exceptionMapper.selectList(any())).thenReturn(List.of());

        AdminDeliveryAppealEvidenceVo vo = service.appealEvidence(300L);

        assertEquals("ok", vo.getLinkStatus());
        assertEquals("139****1111", vo.getAppeal().getUserMaskedPhone());
        assertEquals("数量不符", vo.getAppeal().getAppealReasonLabel());
        assertEquals(1, vo.getAppealPhotos().size());
        assertEquals("ok", vo.getAppealPhotos().get(0).getMediaStatus());
        assertEquals(1, vo.getCourierEvidences().size());
        assertEquals("ok", vo.getCourierEvidences().get(0).getEvidenceRefs().get(0).getMediaStatus());
        assertNotNull(vo.getTask());
        assertEquals("ok", vo.getTask().getLinkStatus());
    }

    @Test
    void appealEvidenceMismatchHidesTaskAndMedia() {
        when(appealMapper.selectAdminAppealById(300L)).thenReturn(adminAppealItem());
        // 申诉用户与订单归属错位
        when(appealMapper.selectById(300L)).thenReturn(pendingAppealWithEvidence().setUserId(7L));
        when(taskMapper.selectById(77L)).thenReturn(signedTask().setTaskStatus(7).setVersion(6));
        when(orderMapper.selectById(11L)).thenReturn(orderPo(4));

        AdminDeliveryAppealEvidenceVo vo = service.appealEvidence(300L);

        assertEquals("mismatch", vo.getLinkStatus());
        assertNotNull(vo.getLinkReason());
        assertNull(vo.getTask());
        assertNull(vo.getAppealPhotos());
        assertNull(vo.getCourierEvidences());
        verifyNoInteractions(mediaMapper);
    }

    @Test
    void decideDelegatesToPackageATransactionWithSessionAdmin() {
        DeliveryAppealDecideBo bo = new DeliveryAppealDecideBo();
        bo.setAppealId("300");
        bo.setOutcome(5);
        bo.setHandleResult("核实少送一桶，登记补送");
        when(appealTxService.decideAppeal(eq(bo), eq(9L), any())).thenReturn(true);

        assertTrue(service.decideAppeal(bo, 9L));

        // 裁决规则全部由包A 事务收口：本层不做任何直接写库
        verify(appealTxService).decideAppeal(eq(bo), eq(9L), any());
        verifyNoInteractions(taskMapper, appealMapper, orderMapper, walletFlowMapper,
                mediaMapper, exceptionMapper, messageService, domainEventService);
    }
}

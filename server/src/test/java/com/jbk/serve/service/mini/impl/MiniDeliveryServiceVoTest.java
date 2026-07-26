package com.jbk.serve.service.mini.impl;

import com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.serve.service.delivery.IDeliveryAppealTxService;
import com.jbk.serve.service.delivery.IDeliveryMediaService;
import com.jbk.serve.service.delivery.IDeliveryOrderService;
import com.jbk.serve.service.delivery.IDeliveryTaskTxService;
import com.jbk.serve.service.delivery.impl.CourierAccess;
import com.jbk.serve.service.mini.IMiniOrderService;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.data.delivery.po.WsDeliveryAppeal;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.data.mini.bo.MiniDeliveryMediaUploadBo;
import com.jbk.tool.data.mini.vo.MiniCourierAdmissionVo;
import com.jbk.tool.data.mini.vo.MiniDeliveryTaskVo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.user.po.WsCourier;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * E2E-03 包B mini 配送读模型投影测试（纯 Mockito，不连库）。
 *
 * <p>钉住的口径：电话只出 PhoneMask 脱敏值；价格快照总额=水费+配送费；三照 JSON
 * 损坏时宁可空列表不拼残缺证据；媒体上传的类型白名单/坏 base64 在解码前拒绝；
 * 消费者视角任务查询的归属/共键 fail-closed；准入无记录返回 status=0。</p>
 */
class MiniDeliveryServiceVoTest {

    @Mock
    private IDeliveryOrderService deliveryOrderService;
    @Mock
    private IDeliveryTaskTxService taskTxService;
    @Mock
    private IDeliveryAppealTxService appealTxService;
    @Mock
    private IDeliveryMediaService mediaService;
    @Mock
    private IMiniOrderService miniOrderService;
    @Mock
    private CourierAccess courierAccess;
    @Mock
    private WsDeliveryTaskMapper taskMapper;
    @Mock
    private WsDeliveryAppealMapper appealMapper;
    @Mock
    private WsOrderMapper orderMapper;
    @Mock
    private WsStationMapper stationMapper;
    @Mock
    private WsCourierMapper courierMapper;

    @InjectMocks
    private MiniDeliveryServiceImpl service;

    private AutoCloseable mocks;

    @BeforeEach
    void setup() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    private WsOrder deliveryOrder(long id, long userId) {
        WsOrder order = new WsOrder()
                .setOrderNo("WDTESTORDER")
                .setOrderType(3)
                .setUserId(userId)
                .setOrderStatus(2);
        order.setId(id);
        order.setDataStatus(0);
        return order;
    }

    private WsDeliveryTask signedTask(long orderId, long userId) {
        WsDeliveryTask task = new WsDeliveryTask()
                .setTaskNo("DTTESTTASK")
                .setOrderId(orderId)
                .setUserId(userId)
                .setStationId(1L)
                .setWaterTypeId(2L)
                .setWaterType("纯净水（占位）")
                .setContainerSpec("10L桶")
                .setDeliveryCount(3)
                .setPlanReturnCount(1)
                .setActualDeliveryCount(3)
                .setActualReturnCount(1)
                .setWaterAmount(3600L)
                .setDeliveryFee(600L)
                .setReceiveAddress("光谷软件园 A1 栋")
                .setReceivePhone("13900001111")
                .setTaskStatus(5)
                .setVersion(5)
                .setSignTime("20260724120000")
                .setAppealDeadline("20260725120000")
                .setLocationStatus(2)
                .setSignPhotos("[{\"type\":1,\"mediaKey\":\"DMAAA\",\"time\":\"20260724120000\"},"
                        + "{\"type\":2,\"mediaKey\":\"DMBBB\",\"time\":\"20260724120000\"},"
                        + "{\"type\":3,\"mediaKey\":\"DMCCC\",\"time\":\"20260724120000\"}]");
        task.setId(77L);
        task.setDataStatus(0);
        return task;
    }

    @Test
    void myDeliveryTaskProjectsMaskedPhonePriceSumAndPhotos() {
        WsOrder order = deliveryOrder(11L, 6L);
        WsDeliveryTask task = signedTask(11L, 6L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(stationMapper.selectById(1L)).thenReturn(null);

        MiniDeliveryTaskVo vo = service.getMyDeliveryTask("WDTESTORDER", 6L);

        // 脱敏唯一实现：明文电话绝不出网
        assertEquals("139****1111", vo.getMaskedPhone());
        // 规则2：总额=水费+配送费，快照不重算价目
        assertEquals(3600L, vo.getPriceSnapshot().getWaterAmountFen());
        assertEquals(600L, vo.getPriceSnapshot().getDeliveryFeeFen());
        assertEquals(4200L, vo.getPriceSnapshot().getTotalAmountFen());
        assertEquals(3, vo.getSignPhotos().size());
        assertEquals("DMAAA", vo.getSignPhotos().get(0).getMediaKey());
        assertEquals("20260725120000", vo.getAppealDeadline());
        assertEquals(3, vo.getPlannedDeliveryCount());
        assertEquals(1, vo.getPlannedReturnCount());
    }

    @Test
    void myDeliveryTaskRejectsForeignOrderAsNotFound() {
        when(orderMapper.selectOne(any())).thenReturn(deliveryOrder(11L, 999L));
        JbkException denied = assertThrows(JbkException.class,
                () -> service.getMyDeliveryTask("WDTESTORDER", 6L));
        assertEquals("订单不存在或无权访问", denied.getMsg());
    }

    @Test
    void myDeliveryTaskFailsClosedOnLinkMismatch() {
        WsOrder order = deliveryOrder(11L, 6L);
        // 任务外键指向另一订单：共键错位必须阻断，绝不把错挂任务当本单证据
        WsDeliveryTask task = signedTask(12L, 6L);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(taskMapper.selectOne(any())).thenReturn(task);
        assertThrows(JbkException.class, () -> service.getMyDeliveryTask("WDTESTORDER", 6L));
    }

    @Test
    void nonDeliveryOrderYieldsNullTask() {
        WsOrder order = deliveryOrder(11L, 6L).setOrderType(1);
        when(orderMapper.selectOne(any())).thenReturn(order);
        assertNull(service.getMyDeliveryTask("WDTESTORDER", 6L));
    }

    @Test
    void malformedSignPhotosDegradeToEmptyListNotFakeEvidence() {
        WsOrder order = deliveryOrder(11L, 6L);
        WsDeliveryTask task = signedTask(11L, 6L).setSignPhotos("{broken json");
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(stationMapper.selectById(1L)).thenReturn(null);
        assertTrue(service.getMyDeliveryTask("WDTESTORDER", 6L).getSignPhotos().isEmpty());
    }

    @Test
    void admissionWithoutRecordIsStatusZero() {
        when(courierMapper.selectOne(any())).thenReturn(null);
        MiniCourierAdmissionVo vo = service.getAdmission(6L);
        assertEquals(0, vo.getStatus());
        assertEquals(List.of(), vo.getRequestedStationIds());
    }

    @Test
    void admissionProjectsScopeAndMaskedPhone() {
        WsCourier courier = new WsCourier()
                .setUserId(6L)
                .setCourierName("李四")
                .setCourierPhone("13800002222")
                .setStationIds("1,2")
                .setServiceRegion("光谷片区")
                .setCourierStatus(2);
        courier.setId(9L);
        courier.setCreateTime("20260720000000");
        when(courierMapper.selectOne(any())).thenReturn(courier);

        MiniCourierAdmissionVo vo = service.getAdmission(6L);
        assertEquals(2, vo.getStatus());
        assertEquals("138****2222", vo.getMaskedPhone());
        assertEquals(List.of(1L, 2L), vo.getRequestedStationIds());
        assertNull(vo.getRejectReason());
    }

    @Test
    void rejectedAdmissionExposesAuditRemarkAsRejectReason() {
        WsCourier courier = new WsCourier()
                .setUserId(6L)
                .setCourierPhone("13800002222")
                .setCourierStatus(4)
                .setAuditRemark("资料不全");
        courier.setId(9L);
        when(courierMapper.selectOne(any())).thenReturn(courier);
        assertEquals("资料不全", service.getAdmission(6L).getRejectReason());
    }

    @Test
    void uploadRejectsNonWhitelistedMimeBeforeDecoding() {
        MiniDeliveryMediaUploadBo bo = new MiniDeliveryMediaUploadBo();
        bo.setPurpose(1);
        bo.setMimeType("image/svg+xml");
        bo.setContentBase64(Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8)));
        assertThrows(JbkException.class, () -> service.uploadMedia(bo, 6L));
        verifyNoInteractions(mediaService);
    }

    @Test
    void uploadRejectsMalformedBase64() {
        MiniDeliveryMediaUploadBo bo = new MiniDeliveryMediaUploadBo();
        bo.setPurpose(2);
        bo.setMimeType("image/png");
        bo.setContentBase64("not-base64!!!");
        assertThrows(JbkException.class, () -> service.uploadMedia(bo, 6L));
        verifyNoInteractions(mediaService);
    }

    @Test
    void uploadRejectsUnknownPurpose() {
        MiniDeliveryMediaUploadBo bo = new MiniDeliveryMediaUploadBo();
        bo.setPurpose(9);
        bo.setMimeType("image/png");
        bo.setContentBase64("QQ==");
        assertThrows(JbkException.class, () -> service.uploadMedia(bo, 6L));
        verifyNoInteractions(mediaService);
    }

    @Test
    void uploadDelegatesDecodedBytesToControlledRegister() {
        byte[] content = "photo-bytes".getBytes(StandardCharsets.UTF_8);
        MiniDeliveryMediaUploadBo bo = new MiniDeliveryMediaUploadBo();
        bo.setPurpose(1);
        bo.setMimeType("image/jpeg");
        bo.setContentBase64(Base64.getEncoder().encodeToString(content));
        when(mediaService.register(eq(6L), eq(DeliveryEnum.MediaPurpose.SIGN_PHOTO),
                eq(content), eq("image/jpeg"), any())).thenReturn("DMKEY");

        assertEquals("DMKEY", service.uploadMedia(bo, 6L).getMediaKey());
        verify(mediaService).register(eq(6L), eq(DeliveryEnum.MediaPurpose.SIGN_PHOTO),
                eq(content), eq("image/jpeg"), any());
    }

    @Test
    void pageTasksRejectsUnknownView() {
        assertThrows(JbkException.class, () -> service.pageTasks("all", 6L));
        verifyNoInteractions(taskTxService);
    }

    @Test
    void myAppealRejectsForeignAppealAsNotFound() {
        WsDeliveryAppeal appeal = new WsDeliveryAppeal().setUserId(999L);
        appeal.setId(5L);
        when(appealMapper.selectById(5L)).thenReturn(appeal);
        JbkException denied = assertThrows(JbkException.class, () -> service.getMyAppeal("5", 6L));
        assertEquals("申诉不存在或无权访问", denied.getMsg());
    }

    @Test
    void myAppealRejectsNonDecimalId() {
        assertThrows(JbkException.class, () -> service.getMyAppeal("05", 6L));
        assertThrows(JbkException.class, () -> service.getMyAppeal("abc", 6L));
        verifyNoInteractions(appealMapper);
    }
}

package com.jbk.serve.device;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceMsgMapper;
import com.jbk.serve.mapper.device.WsDeviceTelemetryMapper;
import com.jbk.serve.mapper.device.WsFaultDictMapper;
import com.jbk.serve.service.device.IWsCommandService;
import com.jbk.serve.service.device.IWsDeviceService;
import com.jbk.serve.service.ops.IWsAlarmService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.po.WsDeviceMsg;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceUplinkHandlerImplTest {

    private IWsDeviceService deviceService;
    private WsDeviceMapper deviceMapper;
    private WsDeviceMsgMapper messageMapper;
    private WsDeviceTelemetryMapper telemetryMapper;
    private IWsAlarmService alarmService;
    private WsFaultDictMapper faultDictMapper;
    private DeviceUplinkHandlerImpl handler;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WsDevice.class);
    }

    @BeforeEach
    void setUp() {
        deviceService = mock(IWsDeviceService.class);
        deviceMapper = mock(WsDeviceMapper.class);
        messageMapper = mock(WsDeviceMsgMapper.class);
        telemetryMapper = mock(WsDeviceTelemetryMapper.class);
        alarmService = mock(IWsAlarmService.class);
        handler = new DeviceUplinkHandlerImpl();

        ReflectionTestUtils.setField(handler, "deviceService", deviceService);
        ReflectionTestUtils.setField(handler, "commandService", mock(IWsCommandService.class));
        ReflectionTestUtils.setField(handler, "alarmService", alarmService);
        ReflectionTestUtils.setField(handler, "domainEventService", mock(IWsDomainEventService.class));
        ReflectionTestUtils.setField(handler, "deviceMsgMapper", messageMapper);
        ReflectionTestUtils.setField(handler, "telemetryMapper", telemetryMapper);
        faultDictMapper = mock(WsFaultDictMapper.class);
        when(faultDictMapper.selectByCodeForShare(anyString())).thenReturn(java.util.List.of());
        ReflectionTestUtils.setField(handler, "faultDictMapper", faultDictMapper);

        WsDevice device = new WsDevice()
                .setId(9L)
                .setDeviceNo("DEV-9")
                .setRunStatus(DeviceEnum.RunStatus.IDLE.getValue());
        when(deviceService.getOne(any(Wrapper.class))).thenReturn(device);
        when(deviceService.getBaseMapper()).thenReturn(deviceMapper);
        when(messageMapper.insert(any(WsDeviceMsg.class))).thenReturn(1);
        when(messageMapper.updateById(any(WsDeviceMsg.class))).thenReturn(1);
    }

    @Test
    void nonFaultStateWithFaultCodeFailsWithoutChangingProjection() {
        handler.onUplink("DEV-9", "status",
                "{\"msgId\":\"M-1\",\"runStatus\":1,\"faultCode\":\"E003\",\"ts\":\"20260730120000\"}");

        verify(deviceMapper, never()).update(isNull(), any(Wrapper.class));
        verify(alarmService, never()).raise(any(), any(), anyInt(), anyString(), any());
        ArgumentCaptor<WsDeviceMsg> saved = ArgumentCaptor.forClass(WsDeviceMsg.class);
        verify(messageMapper).updateById(saved.capture());
        assertEquals(DeviceEnum.MsgHandleStatus.FAILED.getValue(), saved.getValue().getHandleStatus());
    }

    @Test
    void faultWithoutVendorCodeStillRaisesUnknownBlockingAlarm() {
        when(deviceMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        handler.onUplink("DEV-9", "status",
                "{\"msgId\":\"M-2\",\"runStatus\":3,\"ts\":\"20260730120001\"}");

        verify(alarmService).raise(eq(9L), eq(OpsEnum.AlarmType.FAULT_CODE), eq(3),
                anyString(), eq("UNKNOWN"));
    }

    // 同码多条有效配置：任选一条会把「一条严重阻断、一条提示不阻断」静默压成不告警，
    // 必须按最高风险 fail-closed（level 3 + 阻断），并在文案里点明冲突
    @Test
    void conflictingFaultDictRowsRaiseHighRiskBlockingAlarm() {
        when(deviceMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(faultDictMapper.selectByCodeForShare("E100")).thenReturn(java.util.List.of(
                new com.jbk.tool.data.device.po.WsFaultDict().setFaultCode("E100")
                        .setFaultName("轻微提示").setFaultLevel(1).setBlockOrderFlag(1),
                new com.jbk.tool.data.device.po.WsFaultDict().setFaultCode("E100")
                        .setFaultName("严重故障").setFaultLevel(3).setBlockOrderFlag(2)));

        handler.onUplink("DEV-9", "status",
                "{\"msgId\":\"M-CONFLICT\",\"runStatus\":3,\"faultCode\":\"E100\",\"ts\":\"20260803120000\"}");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(alarmService).raise(eq(9L), eq(OpsEnum.AlarmType.FAULT_CODE), eq(3),
                content.capture(), eq("E100"));
        assertTrue(content.getValue().contains("冲突"), "告警文案须点明字典冲突，实际=" + content.getValue());
        assertTrue(content.getValue().contains("阻断下单"), "冲突必须按阻断处理，实际=" + content.getValue());
    }

    // 单条不阻断配置：既有口径不得因本轮改动被误升级成告警
    @Test
    void singleNonBlockingFaultRowKeepsExistingBehaviour() {
        when(deviceMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(faultDictMapper.selectByCodeForShare("E100")).thenReturn(java.util.List.of(
                new com.jbk.tool.data.device.po.WsFaultDict().setFaultCode("E100")
                        .setFaultName("轻微提示").setFaultLevel(1).setBlockOrderFlag(1)));

        handler.onUplink("DEV-9", "status",
                "{\"msgId\":\"M-SINGLE\",\"runStatus\":3,\"faultCode\":\"E100\",\"ts\":\"20260803120001\"}");

        verify(alarmService, never()).raise(any(), eq(OpsEnum.AlarmType.FAULT_CODE),
                anyInt(), anyString(), any());
    }

    @Test
    void healthyFilterTelemetryRecoversPreviousExpiryAlarm() {
        when(deviceService.update(any(Wrapper.class))).thenReturn(true);

        handler.onUplink("DEV-9", "telemetry",
                "{\"ts\":\"20260730120002\",\"filterLife\":[{\"status\":1,\"remaining\":90}]}");

        verify(alarmService).autoRecover(9L, OpsEnum.AlarmType.FILTER_EXPIRE, null);
        verify(alarmService, never()).raise(any(), eq(OpsEnum.AlarmType.FILTER_EXPIRE),
                anyInt(), anyString(), any());
    }
}

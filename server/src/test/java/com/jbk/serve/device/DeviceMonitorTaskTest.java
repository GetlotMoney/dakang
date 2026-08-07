package com.jbk.serve.device;

import com.jbk.serve.service.device.IWsDeviceService;
import com.jbk.serve.service.ops.IWsAlarmService;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.device.po.WsDevice;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceMonitorTaskTest {

    @Test
    void simScanRaisesAbnormalArchivesAndRecoversHealthyOne() {
        IWsDeviceService deviceService = mock(IWsDeviceService.class);
        IWsAlarmService alarmService = mock(IWsAlarmService.class);
        DeviceMonitorTask task = new DeviceMonitorTask();
        ReflectionTestUtils.setField(task, "deviceService", deviceService);
        ReflectionTestUtils.setField(task, "alarmService", alarmService);

        WsDevice arrears = new WsDevice().setId(1L).setSimStatus(3);
        WsDevice expired = new WsDevice().setId(2L).setSimStatus(1).setSimExpireTime("20000101000000");
        WsDevice healthy = new WsDevice().setId(3L).setSimStatus(1).setSimExpireTime("29991231235959");
        when(deviceService.list()).thenReturn(List.of(arrears, expired, healthy));

        task.simAbnormalScan();

        verify(alarmService).raise(eq(1L), eq(OpsEnum.AlarmType.SIM_ABNORMAL), eq(2), anyString(), eq(null));
        verify(alarmService).raise(eq(2L), eq(OpsEnum.AlarmType.SIM_ABNORMAL), eq(2), anyString(), eq(null));
        verify(alarmService).autoRecover(3L, OpsEnum.AlarmType.SIM_ABNORMAL, null);
    }
}

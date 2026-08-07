package com.jbk.serve.service.device;

import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceAvailabilityTest {

    @Test
    void explicitAbnormalSimStatesBlockEvenWhenDeviceReportsOnlineIdle() {
        for (int status : new int[]{2, 3, 4, 99}) {
            WsDevice device = availableDevice().setSimStatus(status);
            DeviceAvailability.Verdict verdict = DeviceAvailability.judge(device, null, availableOutlet());
            assertFalse(verdict.available(), "SIM状态 " + status + " 不得放行");
            assertEquals("DEVICE_UNAVAILABLE", verdict.code());
        }
    }

    @Test
    void expiredOrMalformedSimTimeBlocksAndFutureExpiryPasses() {
        WsDevice expired = availableDevice().setSimStatus(1).setSimExpireTime("20000101000000");
        assertFalse(DeviceAvailability.judge(expired, null, availableOutlet()).available());

        WsDevice malformed = availableDevice().setSimStatus(1).setSimExpireTime("not-a-time");
        assertFalse(DeviceAvailability.judge(malformed, null, availableOutlet()).available());

        WsDevice future = availableDevice().setSimStatus(1).setSimExpireTime("29991231235959");
        assertTrue(DeviceAvailability.judge(future, null, availableOutlet()).available());
    }

    @Test
    void missingLegacySimArchiveDoesNotBreakOtherwiseAvailableDevice() {
        assertTrue(DeviceAvailability.judge(availableDevice(), null, availableOutlet()).available());
    }

    private WsDevice availableDevice() {
        return new WsDevice()
                .setId(1L)
                .setOnlineStatus(DeviceEnum.OnlineStatus.ONLINE.getValue())
                .setRunStatus(DeviceEnum.RunStatus.IDLE.getValue());
    }

    private WsDeviceOutlet availableOutlet() {
        return new WsDeviceOutlet().setOutletStatus(1);
    }
}

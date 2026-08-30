package com.jbk.serve.service.identity;

import com.jbk.serve.mapper.identity.WsDemoControlMapper;
import com.jbk.tool.data.identity.po.WsDemoControl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoScenarioServiceTest {

    private WsDemoControlMapper mapper;
    private DemoScenarioService service;

    @BeforeEach
    void setUp() {
        mapper = mock(WsDemoControlMapper.class);
        service = new DemoScenarioService(mapper);
        ReflectionTestUtils.setField(service, "demoEnabled", true);
    }

    @Test
    void nonDefaultPaymentOutcomeIsConsumedAndReset() {
        when(mapper.selectByUserIdForUpdate(6L)).thenReturn(new WsDemoControl()
                .setId(1L).setUserId(6L).setNextPayResult("TIMEOUT"));
        when(mapper.resetPayResult(1L, "TIMEOUT")).thenReturn(1);

        assertEquals("TIMEOUT", service.consumeNextPayResult(6L));
        verify(mapper).resetPayResult(1L, "TIMEOUT");
    }

    @Test
    void normalDeviceOutcomeStaysDefaultWithoutWrite() {
        when(mapper.selectByUserIdForUpdate(6L)).thenReturn(new WsDemoControl()
                .setId(1L).setUserId(6L).setNextDeviceResult("NORMAL"));

        assertEquals("NORMAL", service.consumeNextDeviceResult(6L));
        verify(mapper, never()).resetDeviceResult(1L, "NORMAL");
    }

    @Test
    void deliveryAutoDefaultsOnAndHonorsManualMode() {
        when(mapper.selectByUserId(6L)).thenReturn(null)
                .thenReturn(new WsDemoControl().setDeliveryAuto(2));

        assertTrue(service.deliveryAutoEnabled(6L));
        assertFalse(service.deliveryAutoEnabled(6L));
    }
}

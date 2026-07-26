package com.jbk.serve.service.mini.impl;

import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.tool.data.user.po.WsCourier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * E2E-03 包B 能力投影矩阵：COURIER_WORK 只随「最新记录=启用」出现；
 * 待审/停用/驳回/无记录一律不投影工作能力（投影只改善导航，绝不比接口更宽）。
 */
class MiniCapabilityServiceTest {

    private WsCourierMapper courierMapper;
    private MiniCapabilityServiceImpl service;

    @BeforeEach
    void setup() {
        courierMapper = Mockito.mock(WsCourierMapper.class);
        service = new MiniCapabilityServiceImpl(courierMapper);
    }

    private WsCourier courier(int status) {
        WsCourier courier = new WsCourier();
        courier.setId(9L);
        courier.setUserId(6L);
        courier.setCourierStatus(status);
        return courier;
    }

    @Test
    void baseProjectionAlwaysContainsUserBaseAndCourierApply() {
        when(courierMapper.selectOne(any())).thenReturn(null);
        List<String> capabilities = service.capabilitiesOf(6L);
        assertEquals(List.of("USER_BASE", "COURIER_APPLY"), capabilities);
    }

    @Test
    void enabledCourierGainsCourierWork() {
        when(courierMapper.selectOne(any())).thenReturn(courier(2));
        assertTrue(service.capabilitiesOf(6L).contains("COURIER_WORK"));
    }

    @Test
    void pendingDisabledAndRejectedNeverGainCourierWork() {
        for (int status : new int[] { 1, 3, 4 }) {
            when(courierMapper.selectOne(any())).thenReturn(courier(status));
            assertFalse(service.capabilitiesOf(6L).contains("COURIER_WORK"),
                    "状态 " + status + " 不得投影 COURIER_WORK");
        }
    }

    @Test
    void illegalSessionUserFallsBackToBaseProjectionWithoutQuery() {
        assertEquals(List.of("USER_BASE", "COURIER_APPLY"), service.capabilitiesOf(null));
        assertEquals(List.of("USER_BASE", "COURIER_APPLY"), service.capabilitiesOf(0L));
        Mockito.verifyNoInteractions(courierMapper);
    }
}

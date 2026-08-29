package com.jbk.serve.service.delivery.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.user.WsCourierMapper;
import com.jbk.serve.mapper.user.WsUserMapper;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.data.user.po.WsCourier;
import com.jbk.tool.data.user.po.WsUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryDemoProfileInitializerTest {

    @Mock
    private WsUserMapper userMapper;
    @Mock
    private WsCourierMapper courierMapper;
    @Mock
    private WsStationMapper stationMapper;
    @Mock
    private WsDeviceMapper deviceMapper;

    @Test
    void waitsForBossWechatAccountInsteadOfGuessingAnId() {
        DeliveryDemoProfileInitializer initializer = initializer();
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertNull(initializer.ensureReady("15300003971", "13800001111", "WS-WH-001", "20260827010000"));
        verify(stationMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    void resolvesEveryActorFromStableBusinessKeys() {
        DeliveryDemoProfileInitializer initializer = initializer();
        WsUser customer = new WsUser().setUserPhone("15300003971").setUserName("老板测试");
        customer.setId(61L);
        WsStation station = new WsStation().setStationCode("WS-WH-001").setStationRegion("武汉东湖高新区")
                .setOwnerUserId(61L);
        station.setId(101L);
        WsCourier virtual = new WsCourier().setUserId(22L).setCourierPhone("13800001111")
                .setStationIds("101").setCourierStatus(2);
        virtual.setId(202L);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(customer);
        when(stationMapper.selectOne(any(Wrapper.class))).thenReturn(station);
        when(courierMapper.selectOne(any(Wrapper.class))).thenReturn(virtual, null);
        when(courierMapper.insert(any(WsCourier.class))).thenReturn(1);

        DeliveryDemoProfileInitializer.DemoContext context = initializer.ensureReady(
                "15300003971", "13800001111", "WS-WH-001", "20260827010000");

        assertEquals(61L, context.customerUserId());
        assertEquals(22L, context.courierUserId());
        assertEquals(202L, context.courierId());
        assertEquals(101L, context.stationId());
        verify(deviceMapper).assignOwnerByStation(101L, 61L, 1L, "20260827010000");
    }

    private DeliveryDemoProfileInitializer initializer() {
        return new DeliveryDemoProfileInitializer(userMapper, courierMapper, stationMapper, deviceMapper);
    }
}

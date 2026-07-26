package com.jbk.serve.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.trade.ITradeOrderTxService;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WsCommandActualMlTest {

    private WsCommandMapper commandMapper;
    private ITradeOrderTxService tradeOrderTxService;
    private IWsDomainEventService eventService;
    private WsCommandServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WsCommand.class);
    }

    @BeforeEach
    void setUp() {
        commandMapper = mock(WsCommandMapper.class);
        tradeOrderTxService = mock(ITradeOrderTxService.class);
        eventService = mock(IWsDomainEventService.class);
        service = new WsCommandServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", commandMapper);
        ReflectionTestUtils.setField(service, "tradeOrderTxService", tradeOrderTxService);
        ReflectionTestUtils.setField(service, "domainEventService", eventService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"actualMl\":0}", "{\"actualMl\":\"0\"}"})
    void acceptsOnlyExplicitIntegerZeroAsZeroDispense(String payload) {
        Long actualMl = ReflectionTestUtils.invokeMethod(service, "parseActualMl", payload);
        assertEquals(0L, actualMl);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not-json",
            "{}",
            "{\"actualMl\":-1}",
            "{\"actualMl\":1.5}",
            "{\"actualMl\":\"01\"}",
            "{\"actualMl\":\"9223372036854775808\"}"
    })
    void rejectsMissingMalformedOrOutOfRangeActualMl(String payload) {
        assertThrows(JbkException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "parseActualMl", payload));
    }

    @Test
    void invalidActualMlDoesNotWriteCommandTerminalOrInvokeSettlement() {
        WsCommand command = new WsCommand()
                .setId(1L)
                .setCmdNo("CMD-1")
                .setDeviceId(2L)
                .setOrderId(3L)
                .setCmdStatus(DeviceEnum.CmdStatus.SENT.getValue());
        when(commandMapper.selectOne(any(Wrapper.class), eq(true))).thenReturn(command);

        assertThrows(JbkException.class,
                () -> service.onResult(2L, "CMD-1", true, "{}", null, "20260722120000"));

        verify(commandMapper, never()).update(isNull(), any(Wrapper.class));
        verify(tradeOrderTxService, never()).settleWaterOrder(any(), anyBoolean(), any());
        verify(eventService).recordReliable(eq(OpsEnum.EventType.ORDER_STATUS), eq("CMD-1"), isNull(), any());
    }
}

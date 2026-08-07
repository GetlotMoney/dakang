package com.jbk.serve.service.device.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.mapper.device.WsCommandBatchMapper;
import com.jbk.serve.service.device.IWaterCommandFailureTxService;
import com.jbk.serve.service.ops.IWsAlarmService;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.trade.ITradeOrderTxService;
import com.jbk.tool.config.mqtt.MqttConnectionManager;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.exception.JbkException;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WsCommandActualMlTest {

    private WsCommandMapper commandMapper;
    private ITradeOrderTxService tradeOrderTxService;
    private IWsDomainEventService eventService;
    private IWaterCommandFailureTxService waterCommandFailureTxService;
    private IWsAlarmService alarmService;
    private WsCommandBatchMapper commandBatchMapper;
    private com.jbk.serve.mapper.trade.WsOrderMapper ackOrderMapper;
    private WsCommandServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WsCommand.class);
        TableInfoHelper.initTableInfo(assistant, com.jbk.tool.data.trade.po.WsOrder.class);
    }

    @BeforeEach
    void setUp() {
        commandMapper = mock(WsCommandMapper.class);
        tradeOrderTxService = mock(ITradeOrderTxService.class);
        eventService = mock(IWsDomainEventService.class);
        waterCommandFailureTxService = mock(IWaterCommandFailureTxService.class);
        alarmService = mock(IWsAlarmService.class);
        commandBatchMapper = mock(WsCommandBatchMapper.class);
        service = new WsCommandServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", commandMapper);
        ReflectionTestUtils.setField(service, "tradeOrderTxService", tradeOrderTxService);
        ReflectionTestUtils.setField(service, "domainEventService", eventService);
        ReflectionTestUtils.setField(service, "waterCommandFailureTxService", waterCommandFailureTxService);
        ReflectionTestUtils.setField(service, "alarmService", alarmService);
        ReflectionTestUtils.setField(service, "commandBatchMapper", commandBatchMapper);
        ackOrderMapper = mock(com.jbk.serve.mapper.trade.WsOrderMapper.class);
        ReflectionTestUtils.setField(service, "ackTxService",
                new WsCommandAckTxServiceImpl(commandMapper, ackOrderMapper, eventService));
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
        // 结算契约只属出水指令（E2E-05 起紧急停止同样带 ORDER_ID 但不承载水量）：夹具显式声明 cmdType=1
        WsCommand command = new WsCommand()
                .setId(1L)
                .setCmdNo("CMD-1")
                .setDeviceId(2L)
                .setOrderId(3L)
                .setCmdType(DeviceEnum.CmdType.START_DISPENSE.getValue())
                .setCmdStatus(DeviceEnum.CmdStatus.SENT.getValue());
        when(commandMapper.selectOne(any(Wrapper.class), eq(true))).thenReturn(command);

        assertThrows(JbkException.class,
                () -> service.onResult(2L, "CMD-1", true, "{}", null, "20260722120000"));

        verify(commandMapper, never()).update(isNull(), any(Wrapper.class));
        verify(tradeOrderTxService, never()).settleWaterOrder(any(), anyBoolean(), any());
        verify(eventService).recordReliable(eq(OpsEnum.EventType.ORDER_STATUS), eq("CMD-1"), isNull(), any());
    }

    @Test
    void persistsSentBeforePublishingSoImmediateAckCannotBeLost() {
        MqttConnectionManager mqtt = mock(MqttConnectionManager.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<MqttConnectionManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mqtt);
        when(commandMapper.update(any(WsCommand.class), any(Wrapper.class))).thenReturn(1);
        ReflectionTestUtils.setField(service, "mqttProvider", provider);

        WsCommand command = new WsCommand()
                .setId(10L)
                .setCmdNo("CMD-FAST-ACK")
                .setDeviceId(20L)
                .setCmdType(DeviceEnum.CmdType.QUERY_STATUS.getValue())
                .setCmdPayload("{}")
                .setCmdStatus(DeviceEnum.CmdStatus.PENDING.getValue());
        WsDevice device = new WsDevice().setId(20L).setDeviceNo("DEV-FAST-ACK");

        ReflectionTestUtils.invokeMethod(service, "doSend", command, device.getDeviceNo());

        var order = inOrder(commandMapper, mqtt);
        order.verify(commandMapper).update(any(WsCommand.class), any(Wrapper.class));
        order.verify(mqtt).publishCommand(eq("DEV-FAST-ACK"), any());
    }

    @Test
    void startDispensePublishFailureUsesAtomicFailureTransaction() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MqttConnectionManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        when(commandMapper.update(any(WsCommand.class), any(Wrapper.class))).thenReturn(1);
        when(waterCommandFailureTxService.converge(eq("CMD-WATER-FAIL"),
                eq(DeviceEnum.CmdStatus.SENT.getValue()), eq(DeviceEnum.CmdStatus.FAILED),
                any(), any(), eq("出水指令下发失败，待补偿处理"), eq("下发失败"))).thenReturn(true);
        ReflectionTestUtils.setField(service, "mqttProvider", provider);

        WsCommand command = new WsCommand()
                .setId(30L).setCmdNo("CMD-WATER-FAIL").setDeviceId(20L).setOrderId(40L)
                .setCmdType(DeviceEnum.CmdType.START_DISPENSE.getValue())
                .setCmdPayload("{}")
                .setCmdStatus(DeviceEnum.CmdStatus.PENDING.getValue());

        ReflectionTestUtils.invokeMethod(service, "doSend", command, "DEV-WATER");

        verify(waterCommandFailureTxService).converge(eq("CMD-WATER-FAIL"),
                eq(DeviceEnum.CmdStatus.SENT.getValue()), eq(DeviceEnum.CmdStatus.FAILED),
                any(), any(), eq("出水指令下发失败，待补偿处理"), eq("下发失败"));
        assertEquals(DeviceEnum.CmdStatus.FAILED.getValue(), command.getCmdStatus());
    }

    @Test
    void waterTimeoutScannerUsesAtomicFailureTransaction() {
        WsCommand command = new WsCommand()
                .setId(31L).setCmdNo("CMD-WATER-TIMEOUT").setDeviceId(20L).setOrderId(41L)
                .setCmdType(DeviceEnum.CmdType.START_DISPENSE.getValue())
                .setCmdStatus(DeviceEnum.CmdStatus.SENT.getValue())
                .setSentTime("20000101000000");
        when(commandMapper.selectList(any(Wrapper.class))).thenReturn(List.of(command));
        when(waterCommandFailureTxService.converge(eq("CMD-WATER-TIMEOUT"),
                eq(DeviceEnum.CmdStatus.SENT.getValue()), eq(DeviceEnum.CmdStatus.TIMEOUT),
                any(), any(), any(), eq("ACK超时"))).thenReturn(true);

        assertEquals(1, service.scanTimeout());

        verify(waterCommandFailureTxService).converge(eq("CMD-WATER-TIMEOUT"),
                eq(DeviceEnum.CmdStatus.SENT.getValue()), eq(DeviceEnum.CmdStatus.TIMEOUT),
                any(), any(), any(), eq("ACK超时"));
        verify(commandMapper, never()).update(isNull(), any(Wrapper.class));
        assertEquals(DeviceEnum.CmdStatus.TIMEOUT.getValue(), command.getCmdStatus());
    }

    // 紧急停止（cmdType=2）同样携带 ORDER_ID 锚定活动出水单，但它下发失败只说明「停不下来」，
    // 不代表那笔取水没做成——把用户正在出水的订单推成异常待补偿既是错误结论也是错误文案
    @Test
    void stopDispenseDispatchFailureDoesNotTouchWaterOrder() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MqttConnectionManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ReflectionTestUtils.setField(service, "mqttProvider", provider);
        when(commandMapper.update(any(WsCommand.class), any(Wrapper.class))).thenReturn(1);

        WsCommand stop = new WsCommand()
                .setId(31L).setCmdNo("CMD-STOP-1").setDeviceId(20L).setOrderId(30L)
                .setCmdType(DeviceEnum.CmdType.STOP_DISPENSE.getValue())
                .setCmdPayload("{}")
                .setCmdStatus(DeviceEnum.CmdStatus.PENDING.getValue());

        ReflectionTestUtils.invokeMethod(service, "doSend", stop, "DEV-STOP");

        verify(waterCommandFailureTxService, never()).converge(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void lostAckCasDoesNotAdvanceLinkedOrder() {
        WsCommand command = new WsCommand()
                .setId(11L)
                .setCmdNo("CMD-ACK-RACE")
                .setDeviceId(20L)
                .setOrderId(30L)
                .setCmdStatus(DeviceEnum.CmdStatus.SENT.getValue());
        when(commandMapper.selectOne(any(Wrapper.class), eq(true))).thenReturn(command);
        when(commandMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertFalse(service.onAck(20L, "CMD-ACK-RACE", "20260730120000", "accepted"));

        // 前态 CAS 落空即整条 ACK 不生效：订单一行不改（联动与指令同在一个事务里）
        verify(ackOrderMapper, never()).update(isNull(), any(Wrapper.class));
    }
}

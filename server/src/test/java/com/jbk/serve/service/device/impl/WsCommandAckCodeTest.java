package com.jbk.serve.service.device.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.device.WsCommandMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.data.trade.po.WsOrder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B18 ACK 判定冻结规则：只有非空且忽略大小写等于 accepted 才是成功回执。
 *
 * <p>缺省即接受是 fail-open——设备回执少一个字段就能把订单推进到「出水中」，
 * 而设备侧可能根本没有开始执行。缺 ackCode 一律按失败收口，不保留兼容开关。</p>
 */
class WsCommandAckCodeTest {

    private WsCommandMapper commandMapper;
    private WsOrderMapper orderMapper;
    private IWsDomainEventService eventService;
    private WsCommandServiceImpl service;

    private static final long DEVICE_ID = 20L;
    private static final long ORDER_ID = 30L;
    private static final String CMD_NO = "CMD-ACK-GUARD";

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WsCommand.class);
        TableInfoHelper.initTableInfo(assistant, WsOrder.class);
    }

    @BeforeEach
    void setUp() {
        commandMapper = mock(WsCommandMapper.class);
        orderMapper = mock(WsOrderMapper.class);
        eventService = mock(IWsDomainEventService.class);
        service = new WsCommandServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", commandMapper);
        ReflectionTestUtils.setField(service, "domainEventService", eventService);
        // 真实事务服务覆在 Mock Mapper 上：指令与订单必须由同一个 applyAck 落地，
        // 换成 Mock 就测不出「指令推了、订单没推」这类断裂
        ReflectionTestUtils.setField(service, "ackTxService",
                new WsCommandAckTxServiceImpl(commandMapper, orderMapper, eventService));
        // 订单联动默认成功；影响 0 行的分支由专门用例覆盖
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
    }

    private WsOrder paidOrder() {
        WsOrder order = new WsOrder()
                .setOrderType(TradeEnum.OrderType.WATER.getValue())
                .setDeviceId(DEVICE_ID)
                .setCmdId(11L)
                .setOrderStatus(TradeEnum.OrderStatus.PAID.getValue());
        order.setId(ORDER_ID);
        return order;
    }

    private WsCommand sentDispense() {
        return new WsCommand()
                .setId(11L)
                .setCmdNo(CMD_NO)
                .setDeviceId(DEVICE_ID)
                .setOrderId(ORDER_ID)
                .setCmdType(DeviceEnum.CmdType.START_DISPENSE.getValue())
                .setCmdStatus(DeviceEnum.CmdStatus.SENT.getValue());
    }

    private void givenSentCommandTransitable() {
        when(commandMapper.selectByCmdNoForUpdate(CMD_NO)).thenReturn(sentDispense());
        when(commandMapper.update(any(WsCommand.class), any(Wrapper.class))).thenReturn(1);
        when(orderMapper.selectByIdForUpdate(ORDER_ID)).thenReturn(paidOrder());
    }

    private WsCommand captureCommandTerminal() {
        ArgumentCaptor<WsCommand> captor = ArgumentCaptor.forClass(WsCommand.class);
        verify(commandMapper).update(captor.capture(), any(Wrapper.class));
        return captor.getValue();
    }

    // 空/缺失 ackCode：指令必须落 5失败，订单转 6异常待补偿，绝不进 3已回执
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankAckCodeFailsCommandAndMovesOrderToAbnormal(String ackCode) {
        givenSentCommandTransitable();

        assertTrue(service.onAck(DEVICE_ID, CMD_NO, "20260730120000", ackCode));

        WsCommand persisted = captureCommandTerminal();
        assertEquals(DeviceEnum.CmdStatus.FAILED.getValue(), persisted.getCmdStatus(),
                "缺 ackCode 的回执不得推进为已回执");
        assertEquals("ACK 缺少 ackCode", persisted.getFailReason());
        assertEquals("20260730120000", persisted.getFinishTime(), "失败终态必须写 FINISH_TIME");
        // 关键状态事件必须走可靠入口（同事务 + 写失败抛出），不得退回静默写入
        verify(eventService).recordReliableOnceAs(eq(OpsEnum.ActorPortal.DEVICE), eq(DEVICE_ID),
                eq(OpsEnum.EventType.COMMAND_STATUS), eq(CMD_NO), eq("CMD_ACK:" + CMD_NO), any(), any());
        // 订单联动 6异常待补偿，且绝不推进出水中
        assertOrderMovedToAbnormal();
    }

    private void assertOrderMovedToAbnormal() {
        assertOrderSetContains("ORDER_STATUS=" + TradeEnum.OrderStatus.ABNORMAL.getValue());
    }

    @SuppressWarnings("unchecked")
    private void assertOrderSetContains(String expected) {
        ArgumentCaptor<LambdaUpdateWrapper<WsOrder>> captor =
                ArgumentCaptor.forClass((Class<LambdaUpdateWrapper<WsOrder>>) (Class<?>) LambdaUpdateWrapper.class);
        verify(orderMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<WsOrder> wrapper = captor.getValue();
        String sqlSet = wrapper.getSqlSet();
        for (Map.Entry<String, Object> e : wrapper.getParamNameValuePairs().entrySet()) {
            sqlSet = sqlSet.replace("#{ew.paramNameValuePairs." + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        assertTrue(sqlSet.replace(" ", "").contains(expected),
                "订单 SET 必须含 " + expected + "，实际 SET=" + sqlSet);
    }

    // accepted（含大小写变体）仍是唯一成功路径
    @ParameterizedTest
    @ValueSource(strings = {"accepted", "ACCEPTED", "Accepted"})
    void acceptedVariantsStillAdvanceCommandAndOrder(String ackCode) {
        givenSentCommandTransitable();

        assertTrue(service.onAck(DEVICE_ID, CMD_NO, "20260730120000", ackCode));

        assertEquals(DeviceEnum.CmdStatus.ACKED.getValue(), captureCommandTerminal().getCmdStatus());
        assertOrderSetContains("ORDER_STATUS=" + TradeEnum.OrderStatus.DISPENSING.getValue());
    }

    // rejected/busy/failed 继续走既有失败路径，拒因保留设备原值
    @ParameterizedTest
    @ValueSource(strings = {"rejected", "busy", "failed"})
    void nonAcceptedCodesKeepExistingFailurePath(String ackCode) {
        givenSentCommandTransitable();

        assertTrue(service.onAck(DEVICE_ID, CMD_NO, "20260730120000", ackCode));

        WsCommand persisted = captureCommandTerminal();
        assertEquals(DeviceEnum.CmdStatus.FAILED.getValue(), persisted.getCmdStatus());
        assertTrue(persisted.getFailReason().contains(ackCode), "拒因必须保留设备上报原值");
        assertOrderSetContains("ORDER_STATUS=" + TradeEnum.OrderStatus.ABNORMAL.getValue());
    }

    // 重放：指令已终态 → 只审计，不再改指令、不再动订单
    @Test
    void replayedBlankAckNeitherRewritesCommandNorOrder() {
        when(commandMapper.selectByCmdNoForUpdate(CMD_NO)).thenReturn(
                sentDispense().setCmdStatus(DeviceEnum.CmdStatus.FAILED.getValue()));

        assertFalse(service.onAck(DEVICE_ID, CMD_NO, "20260730120000", ""));

        verify(commandMapper, never()).update(any(WsCommand.class), any(Wrapper.class));
        verify(orderMapper, never()).update(isNull(), any(Wrapper.class));
    }

    // 错设备空 ACK：目标指令一字不改
    @Test
    void foreignDeviceBlankAckLeavesTargetCommandUntouched() {
        when(commandMapper.selectByCmdNoForUpdate(CMD_NO)).thenReturn(sentDispense());

        assertFalse(service.onAck(DEVICE_ID + 1, CMD_NO, "20260730120000", null));

        verify(commandMapper, never()).update(any(WsCommand.class), any(Wrapper.class));
        verify(orderMapper, never()).update(isNull(), any(Wrapper.class));
    }
}

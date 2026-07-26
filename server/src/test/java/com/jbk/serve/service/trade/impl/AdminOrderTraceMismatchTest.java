package com.jbk.serve.service.trade.impl;

import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.device.IWsCommandService;
import com.jbk.serve.service.mini.recharge.RechargeDetailVerifier;
import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.data.trade.vo.AdminOrderItemVo;
import com.jbk.tool.data.trade.vo.AdminOrderTraceVo;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 订单追溯组装层的共键 fail-closed 单元测试：
 * 关联不一致的指令必须以 linkStatus=mismatch 呈现，且不得携带报文与时间线。
 */
class AdminOrderTraceMismatchTest {

    private AdminOrderServiceImpl service(AdminOrderItemVo order, WsCommand cmd) {
        WsOrderMapper orderMapper = Mockito.mock(WsOrderMapper.class);
        WsWalletFlowMapper flowMapper = Mockito.mock(WsWalletFlowMapper.class);
        IWsCommandService commandService = Mockito.mock(IWsCommandService.class);
        RechargeIdentityMapper rechargeIdentityMapper = Mockito.mock(RechargeIdentityMapper.class);
        RechargeDetailVerifier rechargeDetailVerifier = Mockito.mock(RechargeDetailVerifier.class);
        Mockito.when(orderMapper.selectAdminOrderById(order.getId())).thenReturn(order);
        Mockito.when(commandService.getById(order.getCmdId())).thenReturn(cmd);
        Mockito.when(flowMapper.selectList(Mockito.any())).thenReturn(new ArrayList<>());
        return new AdminOrderServiceImpl(orderMapper, flowMapper, commandService,
                rechargeIdentityMapper, rechargeDetailVerifier,
                Mockito.mock(com.jbk.serve.service.delivery.IAdminDeliveryService.class));
    }

    private AdminOrderItemVo order(Long id, Long cmdId, Long deviceId) {
        AdminOrderItemVo order = new AdminOrderItemVo();
        order.setId(id);
        order.setOrderNo("WO-" + id);
        order.setOrderType(1);
        order.setCmdId(cmdId);
        order.setDeviceId(deviceId);
        order.setDeviceNo("DK-DEV-0001");
        order.setPlanMl(20000L);
        order.setOrderStatus(2);
        order.setOutletId(1L);
        order.setOutletNo(1);
        order.setOutletDeviceId(deviceId);
        return order;
    }

    @Test
    void orphanCommandRendersMismatchWithoutTimelineOrStatus() {
        // 历史坏种子形态：完成订单引用 ORDER_ID=NULL 的指令。
        WsCommand cmd = new WsCommand();
        cmd.setId(4L);
        cmd.setDataStatus(0);
        cmd.setOrderId(null);
        cmd.setDeviceId(1L);
        cmd.setCmdNo("CMD-20260710-0004");
        cmd.setCmdPayload("{\"planMl\":5000}");
        cmd.setSentTime("20260710123000");

        AdminOrderTraceVo trace = service(order(1L, 4L, 1L), cmd).getOrderTrace(1L);

        assertNotNull(trace.getCommand());
        assertEquals("mismatch", trace.getCommand().getLinkStatus());
        assertNotNull(trace.getCommand().getLinkReason());
        assertNull(trace.getCommand().getPayload());
        assertNull(trace.getCommand().getTimeline());
        assertNull(trace.getCommand().getCmdStatus());
        assertNull(trace.getCommand().getCmdType());
    }

    @Test
    void missingCommandRendersMismatchInsteadOfNull() {
        // 复审加固：CMD_ID 非空但指令不存在 → 必须呈现 mismatch，不能静默省略区块。
        AdminOrderTraceVo trace = service(order(1L, 99L, 1L), null).getOrderTrace(1L);

        assertNotNull(trace.getCommand());
        assertEquals("mismatch", trace.getCommand().getLinkStatus());
        assertNull(trace.getCommand().getTimeline());
    }

    @Test
    void fulfilledWaterOrderWithoutCmdIdRendersMismatch() {
        // 最终收口：已完成取水订单 CMD_ID 为空 → 履约证据缺失，呈现 mismatch 而非静默省略。
        AdminOrderItemVo noCmd = order(9L, null, 1L);
        noCmd.setOrderStatus(4);
        AdminOrderTraceVo trace = service(noCmd, null).getOrderTrace(9L);

        assertNotNull(trace.getCommand());
        assertEquals("mismatch", trace.getCommand().getLinkStatus());
        assertNull(trace.getCommand().getTimeline());
    }

    @Test
    void refundedWaterOrderWithoutCmdIdRendersMismatch() {
        // 收口复审：状态 7 已退款（源于真实出水结算）缺 CMD_ID 同样呈现证据缺失。
        AdminOrderItemVo refunded = order(11L, null, 1L);
        refunded.setOrderStatus(7);
        AdminOrderTraceVo trace = service(refunded, null).getOrderTrace(11L);
        assertNotNull(trace.getCommand());
        assertEquals("mismatch", trace.getCommand().getLinkStatus());
    }

    @Test
    void pendingWaterOrderWithoutCmdIdStaysSilent() {
        // 待支付取水订单尚未下发指令：CMD_ID 为空属正常，不呈现指令区块。
        AdminOrderItemVo pending = order(10L, null, 1L);
        pending.setOrderStatus(1);
        AdminOrderTraceVo trace = service(pending, null).getOrderTrace(10L);

        assertNull(trace.getCommand());
    }

    @Test
    void linkedCommandRendersOkWithTimeline() {
        WsCommand cmd = new WsCommand();
        cmd.setId(37L);
        cmd.setDataStatus(0);
        cmd.setOrderId(55L);
        cmd.setDeviceId(1L);
        cmd.setCmdType(1);
        cmd.setCmdNo("CMD20260720155305094633");
        cmd.setCmdStatus(4);
        cmd.setCmdPayload("{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}");
        cmd.setSentTime("20260720155305");
        cmd.setFinishTime("20260720155310");
        cmd.setResultPayload("{\"actualMl\":4980}");

        AdminOrderItemVo completed = order(55L, 37L, 1L);
        completed.setOrderStatus(4);
        completed.setActualMl(4980L);
        completed.setFinishTime("20260720155310");
        AdminOrderTraceVo trace = service(completed, cmd).getOrderTrace(55L);

        assertNotNull(trace.getCommand());
        assertEquals("ok", trace.getCommand().getLinkStatus());
        assertNotNull(trace.getCommand().getTimeline());
        assertEquals("{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}", trace.getCommand().getPayload());
    }
}

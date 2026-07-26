package com.jbk.serve.service.trade.impl;

import com.jbk.tool.data.device.po.WsCommand;
import com.jbk.tool.data.trade.vo.AdminOrderItemVo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单-指令共键校验矩阵（2026-07-20 最终收口轮）。
 * <p>纯函数测试。开始出水指令的报文 orderNo 为硬校验（空/畸形/缺失/为空串一律 mismatch，
 * 历史兼容已随迁移补写 orderNo 而退场）；DATA_STATUS 必须精确为 0；出水指令与取水订单双向绑定。</p>
 */
class AdminOrderCommandLinkTest {

    private AdminOrderItemVo order(Long id, String orderNo, Integer orderType, Long deviceId, Long cmdId) {
        AdminOrderItemVo order = new AdminOrderItemVo();
        order.setId(id);
        order.setOrderNo(orderNo);
        order.setOrderType(orderType);
        order.setDeviceId(deviceId);
        order.setCmdId(cmdId);
        order.setPlanMl(20000L);
        order.setOrderStatus(2);
        order.setOutletId(1L);
        order.setOutletNo(1);
        order.setOutletDeviceId(deviceId);
        return order;
    }

    private WsCommand cmd(Long orderId, Long deviceId, Integer cmdType, String payload) {
        WsCommand cmd = new WsCommand();
        cmd.setId(37L);
        cmd.setDataStatus(0);
        cmd.setOrderId(orderId);
        cmd.setDeviceId(deviceId);
        cmd.setCmdType(cmdType);
        cmd.setCmdPayload(payload);
        cmd.setCmdStatus(1);
        return cmd;
    }

    private final AdminOrderItemVo waterOrder = order(55L, "WO-55", 1, 1L, 37L);

    @Test
    void matchedKeysPass() {
        assertNull(AdminOrderServiceImpl.commandLinkMismatch(
                waterOrder, cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}")));
    }

    @Test
    void nonDispenseCommandOnAnyOrderIsMismatch() {
        // 收口复审：只要订单引用了指令，就必须 ORDER_TYPE=1 且 CMD_TYPE=1；查询类指令(3)一律拒绝。
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(
                order(70L, "WO-70", 2, 1L, 37L), cmd(70L, 1L, 3, "{}")));
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(
                waterOrder, cmd(55L, 1L, 3, "{\"orderNo\":\"WO-55\"}")));
    }

    @Test
    void payloadPlanMlMismatchIsMismatch() {
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(
                waterOrder, cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":99999,\"orderNo\":\"WO-55\"}")));
    }

    @Test
    void payloadOutletNoMismatchIsMismatch() {
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(
                waterOrder, cmd(55L, 1L, 1, "{\"outletNo\":9,\"planMl\":20000,\"orderNo\":\"WO-55\"}")));
    }

    @Test
    void payloadPlanMlAndOutletNoAreRequiredIntegerEvidence() {
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(
                waterOrder, cmd(55L, 1L, 1, "{\"outletNo\":1,\"orderNo\":\"WO-55\"}")));
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(
                waterOrder, cmd(55L, 1L, 1, "{\"planMl\":20000,\"orderNo\":\"WO-55\"}")));
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(
                waterOrder, cmd(55L, 1L, 1, "{\"outletNo\":\"1\",\"planMl\":\"20000\",\"orderNo\":\"WO-55\"}")));
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(
                waterOrder, cmd(55L, 1L, 1, "{\"outletNo\":1.0,\"planMl\":20000.0,\"orderNo\":\"WO-55\"}")));
    }

    @Test
    void orderPlanMlAndOutletNoAreRequiredEvidence() {
        AdminOrderItemVo missingPlan = order(55L, "WO-55", 1, 1L, 37L);
        missingPlan.setPlanMl(null);
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(
                missingPlan, cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}")));

        AdminOrderItemVo missingOutlet = order(55L, "WO-55", 1, 1L, 37L);
        missingOutlet.setOutletNo(null);
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(
                missingOutlet, cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}")));
    }

    @Test
    void planMlAndOutletNoMustBePositiveOnBothSides() {
        AdminOrderItemVo invalidPlan = order(55L, "WO-55", 1, 1L, 37L);
        invalidPlan.setPlanMl(0L);
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(
                invalidPlan, cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":0,\"orderNo\":\"WO-55\"}")));

        AdminOrderItemVo invalidOutlet = order(55L, "WO-55", 1, 1L, 37L);
        invalidOutlet.setOutletNo(0);
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(
                invalidOutlet, cmd(55L, 1L, 1, "{\"outletNo\":0,\"planMl\":20000,\"orderNo\":\"WO-55\"}")));
    }

    @Test
    void numericPayloadOrderNoIsMismatch() {
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(
                order(55L, "55", 1, 1L, 37L),
                cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":55}")));
    }

    @Test
    void finishedOrderRequiresSuccessCmdStatusAndMatchingActualMl() {
        AdminOrderItemVo finished = order(55L, "WO-55", 1, 1L, 37L);
        finished.setOrderStatus(4);
        finished.setActualMl(4980L);
        finished.setFinishTime("20260721090002");

        // 指令非成功终态 → mismatch
        WsCommand notSuccess = cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}");
        notSuccess.setCmdStatus(3);
        notSuccess.setResultPayload("{\"actualMl\":4980}");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(finished, notSuccess));

        // 水量不一致 → mismatch（防"正确单号+错误水量"）
        WsCommand wrongMl = cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}");
        wrongMl.setCmdStatus(4);
        wrongMl.setResultPayload("{\"actualMl\":9999}");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(finished, wrongMl));

        // 结果缺 actualMl → mismatch
        WsCommand noActual = cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}");
        noActual.setCmdStatus(4);
        noActual.setResultPayload("{}");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(finished, noActual));

        // 全对 → 通过
        WsCommand good = cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}");
        good.setCmdStatus(4);
        good.setSentTime("20260721090000");
        good.setFinishTime("20260721090002");
        good.setResultPayload("{\"actualMl\":4980}");
        assertNull(AdminOrderServiceImpl.commandLinkMismatch(finished, good));
    }

    @Test
    void finishedOrderRequiresActualMlOnBothSidesAsInteger() {
        AdminOrderItemVo missingOrderActual = order(55L, "WO-55", 1, 1L, 37L);
        missingOrderActual.setOrderStatus(4);
        missingOrderActual.setActualMl(null);
        missingOrderActual.setFinishTime("20260721090002");
        WsCommand numericResult = cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}");
        numericResult.setCmdStatus(4);
        numericResult.setResultPayload("{\"actualMl\":4980}");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(missingOrderActual, numericResult));

        AdminOrderItemVo finished = order(55L, "WO-55", 1, 1L, 37L);
        finished.setOrderStatus(4);
        finished.setActualMl(4980L);
        finished.setFinishTime("20260721090002");
        WsCommand stringResult = cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}");
        stringResult.setCmdStatus(4);
        stringResult.setResultPayload("{\"actualMl\":\"4980\"}");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(finished, stringResult));
    }

    @Test
    void finishedOrderActualMlMustBePositiveOnBothSides() {
        AdminOrderItemVo finished = order(55L, "WO-55", 1, 1L, 37L);
        finished.setOrderStatus(4);
        finished.setActualMl(0L);
        finished.setFinishTime("20260721090002");
        WsCommand zeroResult = cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}");
        zeroResult.setCmdStatus(4);
        zeroResult.setResultPayload("{\"actualMl\":0}");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(finished, zeroResult));
    }

    @Test
    void missingCommandIsMismatch() {
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, null));
    }

    @Test
    void nonZeroOrNullDataStatusIsMismatch() {
        WsCommand deleted = cmd(55L, 1L, 1, "{\"orderNo\":\"WO-55\"}");
        deleted.setDataStatus(1);
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, deleted));
        // 收口复审：DATA_STATUS 为空不得默认视为正常。
        WsCommand unknown = cmd(55L, 1L, 1, "{\"orderNo\":\"WO-55\"}");
        unknown.setDataStatus(null);
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, unknown));
    }

    @Test
    void nullCommandOrderIdIsMismatch() {
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, cmd(null, 1L, 1, "{\"orderNo\":\"WO-55\"}")));
    }

    @Test
    void foreignOrderIdIsMismatch() {
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, cmd(56L, 1L, 1, "{\"orderNo\":\"WO-55\"}")));
    }

    @Test
    void missingDeviceOnEitherSideIsMismatch() {
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(
                order(55L, "WO-55", 1, null, 37L), cmd(55L, 1L, 1, "{\"orderNo\":\"WO-55\"}")));
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, cmd(55L, null, 1, "{\"orderNo\":\"WO-55\"}")));
    }

    @Test
    void foreignDeviceIsMismatch() {
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, cmd(55L, 2L, 1, "{\"orderNo\":\"WO-55\"}")));
    }

    @Test
    void outletMustBelongToOrderDevice() {
        AdminOrderItemVo crossed = order(55L, "WO-55", 1, 1L, 37L);
        crossed.setOutletId(3L);
        crossed.setOutletNo(1);
        crossed.setOutletDeviceId(2L);
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(
                crossed, cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}")));
    }

    @Test
    void loadedCommandIdMustEqualOrderCmdId() {
        WsCommand other = cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}");
        other.setId(99L);
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, other));
    }

    @Test
    void fulfillmentStatusesRequireCompatibleCommandStateAndTimes() {
        AdminOrderItemVo dispensing = order(55L, "WO-55", 1, 1L, 37L);
        dispensing.setOrderStatus(3);
        WsCommand pending = cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(dispensing, pending));

        WsCommand acked = cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}");
        acked.setCmdStatus(3);
        acked.setSentTime("20260721090000");
        acked.setAckTime("20260721090001");
        assertNull(AdminOrderServiceImpl.commandLinkMismatch(dispensing, acked));
        acked.setFinishTime("20260721090002");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(dispensing, acked));
        acked.setFinishTime(null);
        acked.setResultPayload("{}");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(dispensing, acked));

        AdminOrderItemVo abnormal = order(55L, "WO-55", 1, 1L, 37L);
        abnormal.setOrderStatus(6);
        WsCommand failed = cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}");
        failed.setCmdStatus(5);
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(abnormal, failed));
        failed.setFinishTime("20260721090002");
        assertNull(AdminOrderServiceImpl.commandLinkMismatch(abnormal, failed));
    }

    @Test
    void zeroWaterRefundRequiresSuccessfulZeroResultAndTimes() {
        AdminOrderItemVo refunded = order(55L, "WO-55", 1, 1L, 37L);
        refunded.setOrderStatus(7);
        refunded.setActualMl(0L);
        refunded.setFinishTime("20260721090002");
        WsCommand success = cmd(55L, 1L, 1, "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}");
        success.setCmdStatus(4);
        success.setSentTime("20260721090000");
        success.setFinishTime("20260721090002");
        success.setResultPayload("{\"actualMl\":0}");
        assertNull(AdminOrderServiceImpl.commandLinkMismatch(refunded, success));
        success.setResultPayload("{\"actualMl\":1}");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(refunded, success));
    }

    @Test
    void abnormalActualMismatch() {
        AdminOrderItemVo abnormal = order(55L, "WO-55", 1, 1L, 37L);
        abnormal.setOrderStatus(6);
        WsCommand failed = cmd(55L, 1L, 1,
                "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}");
        failed.setCmdStatus(5);
        failed.setFinishTime("20260721090002");

        // 下发即失败/超时时允许双方均无实际量。
        assertNull(AdminOrderServiceImpl.commandLinkMismatch(abnormal, failed));
        failed.setCmdStatus(6);
        assertNull(AdminOrderServiceImpl.commandLinkMismatch(abnormal, failed));
        failed.setCmdStatus(5);

        abnormal.setActualMl(1L);
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(abnormal, failed));
        failed.setResultPayload("{\"actualMl\":9999}");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(abnormal, failed));
        failed.setResultPayload("{\"actualMl\":1}");
        assertNull(AdminOrderServiceImpl.commandLinkMismatch(abnormal, failed));

        abnormal.setActualMl(null);
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(abnormal, failed));
        abnormal.setActualMl(0L);
        failed.setResultPayload("{\"actualMl\":0}");
        assertNull(AdminOrderServiceImpl.commandLinkMismatch(abnormal, failed));
        abnormal.setActualMl(-1L);
        failed.setResultPayload("{\"actualMl\":-1}");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(abnormal, failed));
        abnormal.setActualMl(0L);
        failed.setResultPayload("{\"actualMl\":\"0\"}");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(abnormal, failed));
    }

    @Test
    void pendingWithTerminalEvidence() {
        WsCommand pending = cmd(55L, 1L, 1,
                "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}");
        pending.setFinishTime("20260721090002");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, pending));
        pending.setFinishTime(null);
        pending.setResultPayload("{}");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, pending));
        pending.setResultPayload(null);
        pending.setSentTime("20260721090000");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, pending));
        pending.setSentTime(null);
        pending.setAckTime("20260721090001");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, pending));

        WsCommand sent = cmd(55L, 1L, 1,
                "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}");
        sent.setCmdStatus(2);
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, sent));
        sent.setSentTime("20260721090000");
        assertNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, sent));
        sent.setAckTime("20260721090001");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, sent));
        sent.setAckTime(null);
        sent.setFinishTime("20260721090002");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, sent));
        sent.setFinishTime(null);
        sent.setResultPayload("{}");
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, sent));
    }

    @Test
    void completedOrderMissingFinishTime() {
        for (int orderStatus : new int[]{4, 7, 8}) {
            long actualMl = orderStatus == 7 ? 0L : 4980L;
            AdminOrderItemVo completed = order(55L, "WO-55", 1, 1L, 37L);
            completed.setOrderStatus(orderStatus);
            completed.setActualMl(actualMl);

            WsCommand success = cmd(55L, 1L, 1,
                    "{\"outletNo\":1,\"planMl\":20000,\"orderNo\":\"WO-55\"}");
            success.setCmdStatus(4);
            success.setSentTime("20260721090000");
            success.setFinishTime("20260721090002");
            success.setResultPayload("{\"actualMl\":" + actualMl + "}");

            assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(completed, success));
        }
    }

    @Test
    void waterOrderWithNonDispenseCommandIsMismatch() {
        String reason = AdminOrderServiceImpl.commandLinkMismatch(waterOrder, cmd(55L, 1L, 3, "{}"));
        assertNotNull(reason);
        assertTrue(reason.contains("开始出水"));
    }

    @Test
    void dispenseCommandOnNonWaterOrderIsMismatch() {
        // 收口复审：反向约束——出水指令只能被 ORDER_TYPE=1 订单绑定。
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(
                order(70L, "WO-70", 2, 1L, 37L), cmd(70L, 1L, 1, "{\"orderNo\":\"WO-70\"}")));
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(
                order(71L, "WO-71", null, 1L, 37L), cmd(71L, 1L, 1, "{\"orderNo\":\"WO-71\"}")));
    }

    @Test
    void dispensePayloadMissingOrBlankOrderNoIsMismatch() {
        // 收口复审：出水指令报文 orderNo 硬校验——空报文 / 无字段 / 空串全部拒绝。
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, cmd(55L, 1L, 1, null)));
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, cmd(55L, 1L, 1, "")));
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, cmd(55L, 1L, 1, "{\"planMl\":5000}")));
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(waterOrder, cmd(55L, 1L, 1, "{\"orderNo\":\"\"}")));
    }

    @Test
    void dispenseMalformedPayloadIsMismatch() {
        // 收口复审：畸形 JSON 不再兼容通过。
        String reason = AdminOrderServiceImpl.commandLinkMismatch(waterOrder, cmd(55L, 1L, 1, "not-json"));
        assertNotNull(reason);
        assertTrue(reason.contains("JSON"));
    }

    @Test
    void payloadOrderNoRebindIsMismatch() {
        assertNotNull(AdminOrderServiceImpl.commandLinkMismatch(
                waterOrder, cmd(55L, 1L, 1, "{\"planMl\":20000,\"orderNo\":\"WO-OTHER\"}")));
    }
}

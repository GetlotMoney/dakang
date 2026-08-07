package com.jbk.serve.service.trade;

import com.jbk.tool.data.device.po.WsCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单-出水指令核验矩阵单测（E2E-04 包A，判定已从 {@code AdminOrderServiceImpl} 抽成纯函数）。
 *
 * <p>抽取的意义是「读写两侧共用同一份矩阵」：PC 追溯手里是读模型 Vo，取水异常核账手里是
 * {@code WsOrder} PO。本类<b>只按原始值调用</b>，不经任何 Vo，覆盖抽出来的四个公开方法；
 * Vo 形状驱动的共键矩阵在 {@code AdminOrderCommandLinkTest} 已有，两者互不重复。</p>
 *
 * <p>重点是核账放宽那一格：{@code afterSaleConfirmed} 为真且订单落在 4/7 时才接受
 * 5/6 终态指令。{@link #stateMatrixIsExactWithAndWithoutReconciliation()} 用同一张矩阵跑两遍
 * （确认 / 未确认），逐格断言差集<b>恰好</b>是那四格——放宽一旦溢出到 8部分退款、
 * 或溢出到未确认订单，矩阵会立刻变红。</p>
 */
class OrderCommandVerifierTest {

    private static final String ORDER_FINISH = "20260721090002";
    private static final String SENT = "20260721090000";
    private static final String ACK = "20260721090001";
    private static final String FINISH = "20260721090002";
    private static final String RESULT = "{\"actualMl\":4980}";

    /**
     * 未经核账时合法的「订单状态&gt;指令状态」格。
     * 2已支付↔1待下发/2已下发；3出水中↔3已回执；4/7/8结算↔4执行成功；6异常↔5失败/6超时。
     */
    private static final Set<String> BASE_ALLOWED = Set.of(
            "2>1", "2>2",
            "3>3",
            "4>4", "7>4", "8>4",
            "6>5", "6>6");

    /** 核账确认后额外放行的四格（且仅此四格）：订单 4/7 × 指令 5失败/6超时。 */
    private static final Set<String> RECONCILED_EXTRA = Set.of("4>5", "4>6", "7>5", "7>6");

    // ==================== commandStateMismatch 全矩阵 ====================

    @Test
    void stateMatrixIsExactWithAndWithoutReconciliation() {
        for (int orderStatus = 1; orderStatus <= 8; orderStatus++) {
            for (int cmdStatus = 1; cmdStatus <= 7; cmdStatus++) {
                String cell = orderStatus + ">" + cmdStatus;
                String plain = OrderCommandVerifier.commandStateMismatch(
                        orderStatus, ORDER_FINISH, false, cmdOfStatus(cmdStatus));
                assertEquals(BASE_ALLOWED.contains(cell), plain == null,
                        "未核账 " + cell + " 判定错误：" + plain);

                String reconciled = OrderCommandVerifier.commandStateMismatch(
                        orderStatus, ORDER_FINISH, true, cmdOfStatus(cmdStatus));
                boolean expectedAfter = BASE_ALLOWED.contains(cell) || RECONCILED_EXTRA.contains(cell);
                assertEquals(expectedAfter, reconciled == null,
                        "已核账 " + cell + " 判定错误：" + reconciled);
            }
        }
    }

    /**
     * 8部分退款 不在核账产出范围内（核账只把 6 推进到 4 或 7），带标记也不放行 ——
     * 把 8 一并放开等于凭空承认一条状态机产生不了的组合。
     */
    @Test
    void partRefundedIsNeverRelaxedEvenWhenReconciled() {
        for (int cmdStatus : new int[]{5, 6}) {
            assertNotNull(OrderCommandVerifier.commandStateMismatch(
                    8, ORDER_FINISH, true, cmdOfStatus(cmdStatus)), "8>" + cmdStatus + " 不得放行");
        }
    }

    /** 放宽只限「指令终态 + 指令完成时间」这一格，其余证据要求一条不减。 */
    @Test
    void relaxationStillRequiresBothFinishTimes() {
        WsCommand failed = cmdOfStatus(5);
        assertNull(OrderCommandVerifier.commandStateMismatch(4, ORDER_FINISH, true, failed));

        WsCommand noCmdFinish = cmdOfStatus(5);
        noCmdFinish.setFinishTime(null);
        assertNotNull(OrderCommandVerifier.commandStateMismatch(4, ORDER_FINISH, true, noCmdFinish),
                "缺指令完成时间不得放行");

        assertNotNull(OrderCommandVerifier.commandStateMismatch(4, null, true, failed),
                "缺订单完成时间不得放行");
        assertNotNull(OrderCommandVerifier.commandStateMismatch(4, "   ", true, failed));
        // 正常成功路径同样要求订单完成时间，核账没有、也不应该改变这条
        assertNotNull(OrderCommandVerifier.commandStateMismatch(4, null, false, cmdOfStatus(4)));
    }

    /** 各指令状态的时间/结果形状：提前携带或缺失都必须报错，报文形状本身就是证据。 */
    @Test
    void commandShapeViolationsAreRejectedBeforeStatePairing() {
        WsCommand pendingWithSent = cmdOfStatus(1);
        pendingWithSent.setSentTime(SENT);
        assertTrue(nonNullMessage(2, pendingWithSent).contains("待下发指令"));

        WsCommand pendingWithResult = cmdOfStatus(1);
        pendingWithResult.setResultPayload(RESULT);
        assertNotNull(OrderCommandVerifier.commandStateMismatch(2, ORDER_FINISH, false, pendingWithResult));

        WsCommand sentWithoutTime = cmdOfStatus(2);
        sentWithoutTime.setSentTime(null);
        assertTrue(nonNullMessage(2, sentWithoutTime).contains("已下发指令缺少下发时间"));

        WsCommand sentWithAck = cmdOfStatus(2);
        sentWithAck.setAckTime(ACK);
        assertNotNull(OrderCommandVerifier.commandStateMismatch(2, ORDER_FINISH, false, sentWithAck));

        WsCommand ackedWithoutAckTime = cmdOfStatus(3);
        ackedWithoutAckTime.setAckTime(null);
        assertNotNull(OrderCommandVerifier.commandStateMismatch(3, ORDER_FINISH, false, ackedWithoutAckTime));

        WsCommand ackedWithFinish = cmdOfStatus(3);
        ackedWithFinish.setFinishTime(FINISH);
        assertNotNull(OrderCommandVerifier.commandStateMismatch(3, ORDER_FINISH, false, ackedWithFinish));
    }

    /** 设备协议允许未先上报 ACK 而直接返回 result：成功终态不强制 ACK_TIME，但下发/完成时间必备。 */
    @Test
    void successTerminalDoesNotRequireAckButRequiresSentAndFinish() {
        WsCommand noAck = cmdOfStatus(4);
        noAck.setAckTime(null);
        assertNull(OrderCommandVerifier.commandStateMismatch(4, ORDER_FINISH, false, noAck));

        WsCommand noSent = cmdOfStatus(4);
        noSent.setSentTime(null);
        assertNotNull(OrderCommandVerifier.commandStateMismatch(4, ORDER_FINISH, false, noSent));

        WsCommand noFinish = cmdOfStatus(4);
        noFinish.setFinishTime(null);
        assertNotNull(OrderCommandVerifier.commandStateMismatch(4, ORDER_FINISH, false, noFinish));
    }

    @Test
    void nullStatusesAreRejected() {
        assertTrue(nonNullMessage(null, cmdOfStatus(4)).contains("订单状态为空"));
        // 指令状态为空时没有任何一格成立：状态未知不得被当成"暂且相信"
        for (int orderStatus = 1; orderStatus <= 8; orderStatus++) {
            WsCommand unknown = cmdOfStatus(4);
            unknown.setCmdStatus(null);
            assertNotNull(OrderCommandVerifier.commandStateMismatch(
                    orderStatus, ORDER_FINISH, true, unknown), "订单 " + orderStatus + " 配空指令状态");
        }
    }

    // ==================== abnormalActualMismatch ====================

    @Test
    void abnormalActualRequiresBothSidesPresentOrBothAbsent() {
        WsCommand noResult = cmdOfStatus(5);
        noResult.setResultPayload(null);
        assertNull(OrderCommandVerifier.abnormalActualMismatch(null, noResult), "双侧皆无值允许");

        // 只有订单侧有值
        assertNotNull(OrderCommandVerifier.abnormalActualMismatch(100L, noResult));
        // 只有指令侧有值
        WsCommand withResult = cmdOfStatus(5);
        withResult.setResultPayload("{\"actualMl\":100}");
        assertNotNull(OrderCommandVerifier.abnormalActualMismatch(null, withResult));
        // 报文有但不含 actualMl 键 = 指令侧无值
        WsCommand emptyResult = cmdOfStatus(5);
        emptyResult.setResultPayload("{\"code\":0}");
        assertNull(OrderCommandVerifier.abnormalActualMismatch(null, emptyResult));
        assertNotNull(OrderCommandVerifier.abnormalActualMismatch(100L, emptyResult));
    }

    @Test
    void abnormalActualComparesValuesStrictly() {
        WsCommand cmd = cmdOfStatus(5);
        cmd.setResultPayload("{\"actualMl\":4980}");
        assertNull(OrderCommandVerifier.abnormalActualMismatch(4980L, cmd));
        assertNotNull(OrderCommandVerifier.abnormalActualMismatch(4979L, cmd),
                "数值不等必须报错，避免异常单拼接到其他执行结果");

        WsCommand zero = cmdOfStatus(5);
        zero.setResultPayload("{\"actualMl\":0}");
        assertNull(OrderCommandVerifier.abnormalActualMismatch(0L, zero), "零出水是合法的异常形态");

        WsCommand negative = cmdOfStatus(5);
        negative.setResultPayload("{\"actualMl\":-1}");
        assertNotNull(OrderCommandVerifier.abnormalActualMismatch(-1L, negative), "负水量非法");

        WsCommand stringMl = cmdOfStatus(5);
        stringMl.setResultPayload("{\"actualMl\":\"4980\"}");
        assertNotNull(OrderCommandVerifier.abnormalActualMismatch(4980L, stringMl), "字符串水量非法");

        WsCommand decimalMl = cmdOfStatus(5);
        decimalMl.setResultPayload("{\"actualMl\":4980.5}");
        assertNotNull(OrderCommandVerifier.abnormalActualMismatch(4980L, decimalMl), "小数水量非法");

        WsCommand broken = cmdOfStatus(5);
        broken.setResultPayload("{not json");
        assertTrue(OrderCommandVerifier.abnormalActualMismatch(4980L, broken).contains("合法 JSON"));
    }

    // ==================== strictJsonInteger ====================

    @Test
    void strictJsonIntegerAcceptsOnlyIntegralNumbers() {
        assertEquals(Long.valueOf(7L), OrderCommandVerifier.strictJsonInteger(7));
        assertEquals(Long.valueOf(7L), OrderCommandVerifier.strictJsonInteger(7L));
        assertEquals(Long.valueOf(7L), OrderCommandVerifier.strictJsonInteger((short) 7));
        assertEquals(Long.valueOf(7L), OrderCommandVerifier.strictJsonInteger((byte) 7));
        assertEquals(Long.valueOf(Long.MAX_VALUE),
                OrderCommandVerifier.strictJsonInteger(BigInteger.valueOf(Long.MAX_VALUE)));

        assertNull(OrderCommandVerifier.strictJsonInteger(null));
        assertNull(OrderCommandVerifier.strictJsonInteger("7"), "字符串不是履约量证据");
        assertNull(OrderCommandVerifier.strictJsonInteger(7.0d), "浮点不是整数证据");
        assertNull(OrderCommandVerifier.strictJsonInteger(7.5f));
        assertNull(OrderCommandVerifier.strictJsonInteger(new BigDecimal("7")), "BigDecimal 不在白名单");
        assertNull(OrderCommandVerifier.strictJsonInteger(Boolean.TRUE));
        assertNull(OrderCommandVerifier.strictJsonInteger(
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)), "越界整数必须判为证据类型错误");
    }

    // ==================== commandLinkMismatch（原始值签名，写路径形状） ====================

    @Test
    void linkPassesWithRawValuesFromWritePath() {
        assertNull(link(2, null, false, cmdOfStatus(1)));
    }

    @Test
    void linkFailsOnEveryBrokenCommonKey() {
        // 指令不存在
        assertNotNull(OrderCommandVerifier.commandLinkMismatch(55L, "WO-55", 1, 2, 1L, 1L, 1, 1L,
                20000L, null, 37L, null, false, null));
        // CMD_ID 与实际读到的指令主键不一致
        WsCommand otherId = base(payload("WO-55", 20000, 1));
        otherId.setId(38L);
        assertNotNull(link(2, null, false, otherId));
        // DATA_STATUS 必须精确为 0，为空同样 fail-closed
        WsCommand deleted = base(payload("WO-55", 20000, 1));
        deleted.setDataStatus(1);
        assertNotNull(link(2, null, false, deleted));
        WsCommand nullDataStatus = base(payload("WO-55", 20000, 1));
        nullDataStatus.setDataStatus(null);
        assertNotNull(link(2, null, false, nullDataStatus));
        // 指令归属别的订单
        WsCommand foreign = base(payload("WO-55", 20000, 1));
        foreign.setOrderId(56L);
        assertNotNull(link(2, null, false, foreign));
        // 指令目标设备与订单设备不一致
        WsCommand otherDevice = base(payload("WO-55", 20000, 1));
        otherDevice.setDeviceId(2L);
        assertNotNull(link(2, null, false, otherDevice));
        // 出水口不归属订单设备
        assertNotNull(OrderCommandVerifier.commandLinkMismatch(55L, "WO-55", 1, 2, 1L, 1L, 1, 2L,
                20000L, null, 37L, ORDER_FINISH, false, base(payload("WO-55", 20000, 1))));
        // 报文共键：订单号 / 计划水量 / 出水口
        assertNotNull(link(2, null, false, base(payload("WO-OTHER", 20000, 1))));
        assertNotNull(link(2, null, false, base(payload("WO-55", 19999, 1))));
        assertNotNull(link(2, null, false, base(payload("WO-55", 20000, 2))));
        assertNotNull(link(2, null, false, base("{\"planMl\":20000,\"outletNo\":1}")));
        assertNotNull(link(2, null, false, base("{not json")));
        assertNotNull(link(2, null, false, base("")));
    }

    /**
     * 端到端的核账放宽：同一张 4已完成 订单 + 5执行失败 指令，
     * 未确认必须 mismatch、已确认才通过 —— 放宽绝不能溢出到未核账订单。
     */
    @Test
    void reconciledRelaxationDoesNotLeakToUnconfirmedOrders() {
        WsCommand failed = cmdOfStatus(5);
        assertNotNull(OrderCommandVerifier.commandLinkMismatch(55L, "WO-55", 1, 4, 1L, 1L, 1, 1L,
                20000L, 4980L, 37L, ORDER_FINISH, false, failed), "未核账不得放行失败终态指令");
        assertNull(OrderCommandVerifier.commandLinkMismatch(55L, "WO-55", 1, 4, 1L, 1L, 1, 1L,
                20000L, 4980L, 37L, ORDER_FINISH, true, failed), "已核账应放行");

        // 放宽只动状态那一格：结算态的实际水量仍须与指令结果逐值相等
        failed.setResultPayload("{\"actualMl\":1}");
        assertNotNull(OrderCommandVerifier.commandLinkMismatch(55L, "WO-55", 1, 4, 1L, 1L, 1, 1L,
                20000L, 4980L, 37L, ORDER_FINISH, true, failed), "水量不符仍必须 mismatch");
    }

    // ==================== helpers ====================

    private static String nonNullMessage(Integer orderStatus, WsCommand cmd) {
        String mismatch = OrderCommandVerifier.commandStateMismatch(orderStatus, ORDER_FINISH, false, cmd);
        assertNotNull(mismatch, "预期不一致但返回了 null");
        return mismatch;
    }

    private static String link(int orderStatus, Long actualMl, boolean confirmed, WsCommand cmd) {
        return OrderCommandVerifier.commandLinkMismatch(55L, "WO-55", 1, orderStatus, 1L, 1L, 1, 1L,
                20000L, actualMl, 37L, ORDER_FINISH, confirmed, cmd);
    }

    private static String payload(String orderNo, int planMl, int outletNo) {
        return "{\"orderNo\":\"" + orderNo + "\",\"planMl\":" + planMl + ",\"outletNo\":" + outletNo + "}";
    }

    /** 共键齐备、状态停在 1待下发 的基线指令。 */
    private static WsCommand base(String payload) {
        WsCommand cmd = new WsCommand();
        cmd.setId(37L);
        cmd.setDataStatus(0);
        cmd.setOrderId(55L);
        cmd.setDeviceId(1L);
        cmd.setCmdType(1);
        cmd.setCmdPayload(payload);
        cmd.setCmdStatus(1);
        return cmd;
    }

    /** 各指令状态下「形状完全合规」的指令行；矩阵用它逐格试探状态配对本身。 */
    private static WsCommand cmdOfStatus(int cmdStatus) {
        WsCommand cmd = base(payload("WO-55", 20000, 1));
        cmd.setCmdStatus(cmdStatus);
        switch (cmdStatus) {
            case 1 -> { /* 待下发：不得携带任何时间与结果 */ }
            case 2 -> cmd.setSentTime(SENT);
            case 3 -> {
                cmd.setSentTime(SENT);
                cmd.setAckTime(ACK);
            }
            case 4 -> {
                cmd.setSentTime(SENT);
                cmd.setAckTime(ACK);
                cmd.setFinishTime(FINISH);
                cmd.setResultPayload(RESULT);
            }
            default -> {
                // 5失败 / 6超时 / 7部分完成：终态携带下发与完成时间
                cmd.setSentTime(SENT);
                cmd.setFinishTime(FINISH);
                cmd.setResultPayload(RESULT);
            }
        }
        return cmd;
    }
}

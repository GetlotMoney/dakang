package com.jbk.serve.service.trade;

import com.jbk.tool.data.trade.po.WsOrder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成员日限额纯计算口径（CARD-MEMBER）：状态→占用映射、窗口派生、边界与溢出。
 * 这些口径一旦漂移，事务闸与预检展示会同时算错，必须钉死。
 */
class MemberDayLimitMathTest {

    private WsOrder order(Integer status, Long planMl, Long actualMl) {
        return new WsOrder().setOrderStatus(status).setPlanMl(planMl).setActualMl(actualMl);
    }

    // 状态映射：2/3 按计划；4/8 有合法实际用实际否则计划；6 同上；7 归零；1/5 不计
    @Test
    void occupancyFollowsStatusMapping() {
        assertEquals(5_000L, MemberDayLimitMath.occupiedMl(List.of(order(2, 5_000L, null))));
        assertEquals(5_000L, MemberDayLimitMath.occupiedMl(List.of(order(3, 5_000L, null))));
        assertEquals(3_000L, MemberDayLimitMath.occupiedMl(List.of(order(4, 5_000L, 3_000L))));
        assertEquals(5_000L, MemberDayLimitMath.occupiedMl(List.of(order(4, 5_000L, null))), "缺实际量回退计划量");
        assertEquals(2_000L, MemberDayLimitMath.occupiedMl(List.of(order(8, 5_000L, 2_000L))));
        assertEquals(1_000L, MemberDayLimitMath.occupiedMl(List.of(order(6, 5_000L, 1_000L))));
        assertEquals(5_000L, MemberDayLimitMath.occupiedMl(List.of(order(6, 5_000L, null))), "异常单缺实际量按计划量保守占用");
        assertEquals(0L, MemberDayLimitMath.occupiedMl(List.of(order(7, 5_000L, 0L))));
        assertEquals(0L, MemberDayLimitMath.occupiedMl(List.of(order(1, 5_000L, null))));
        assertEquals(0L, MemberDayLimitMath.occupiedMl(List.of(order(5, 5_000L, null))));
    }

    // 未知状态与非法值：fail-closed——未知按计划量占用；负实际量视为非法回退计划量；负计划按 0
    @Test
    void unknownStatusAndIllegalValuesAreConservative() {
        assertEquals(5_000L, MemberDayLimitMath.occupiedMl(List.of(order(99, 5_000L, null))), "未知状态按计划量保守占用");
        assertEquals(5_000L, MemberDayLimitMath.occupiedMl(List.of(order(4, 5_000L, -1L))), "负实际量非法，回退计划量");
        assertEquals(0L, MemberDayLimitMath.occupiedMl(List.of(order(2, -5L, null))));
        assertEquals(0L, MemberDayLimitMath.occupiedMl(null));
    }

    // 混合汇总
    @Test
    void mixedOrdersSumUp() {
        long occupied = MemberDayLimitMath.occupiedMl(List.of(
                order(4, 5_000L, 3_000L),
                order(2, 2_000L, null),
                order(7, 9_000L, 0L),
                order(5, 8_000L, null)));
        assertEquals(5_000L, occupied);
    }

    // 边界：恰好用完允许；超 1 毫升拒绝
    @Test
    void allowsExactlyAtLimitAndRejectsOneMlOver() {
        assertTrue(MemberDayLimitMath.allows(7_000L, 3_000L, 10_000L));
        assertFalse(MemberDayLimitMath.allows(7_000L, 3_001L, 10_000L));
    }

    // 溢出：累加溢出抛出（由调用方按拒绝处理）；判定溢出直接不允许
    @Test
    void overflowFailsClosed() {
        assertThrows(ArithmeticException.class, () -> MemberDayLimitMath.occupiedMl(List.of(
                order(2, Long.MAX_VALUE, null), order(2, 1L, null))));
        assertFalse(MemberDayLimitMath.allows(Long.MAX_VALUE, 1L, Long.MAX_VALUE));
    }

    // 剩余额度展示口径：限额-占用，下限 0
    @Test
    void remainingIsFlooredAtZero() {
        assertEquals(4_000L, MemberDayLimitMath.remaining(10_000L, 6_000L));
        assertEquals(0L, MemberDayLimitMath.remaining(10_000L, 12_000L));
    }

    // 窗口派生：当天零点起、次日零点止；跨月/跨年正确进位
    @Test
    void dayWindowDerivation() {
        assertEquals("20260723000000", MemberDayLimitMath.dayStart("20260723235959"));
        assertEquals("20260724000000", MemberDayLimitMath.dayEnd("20260723235959"));
        assertEquals("20270101000000", MemberDayLimitMath.dayEnd("20261231120000"));
        assertEquals("20260301000000", MemberDayLimitMath.dayEnd("20260228080000"));
    }
}

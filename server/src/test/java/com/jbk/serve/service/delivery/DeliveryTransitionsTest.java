package com.jbk.serve.service.delivery;

import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机转换矩阵单测（E2E-03 规则10）：7×7 全枚举，白名单外一律非法。
 */
class DeliveryTransitionsTest {

    /** 合法转换全集：1→2→3→4→5、5↔7（申诉/裁决归档）、1→6（待接单取消，E2E-04 包A）。 */
    private static final Set<String> ALLOWED = Set.of("1>2", "2>3", "3>4", "4>5", "5>7", "7>5", "1>6");

    @Test
    void fullMatrixMatchesWhitelistExactly() {
        for (int from = 1; from <= 7; from++) {
            for (int to = 1; to <= 7; to++) {
                boolean expected = ALLOWED.contains(from + ">" + to);
                assertEquals(expected, DeliveryTransitions.allowed(from, to),
                        "转换 " + from + "→" + to + " 判定错误");
            }
        }
    }

    @Test
    void advanceSourceIsExactPredecessor() {
        assertEquals(2, DeliveryTransitions.requireAdvanceSource(3), "离站只能从已接单");
        assertEquals(3, DeliveryTransitions.requireAdvanceSource(4), "送达只能从配送中");
    }

    @Test
    void advanceRejectsNonAdvanceTargets() {
        // 接单/签收/取消/申诉不属于 advance 动作，必须走各自专用入口
        for (int target : new int[]{1, 2, 5, 6, 7}) {
            assertThrows(JbkException.class, () -> DeliveryTransitions.requireAdvanceSource(target),
                    "目标 " + target + " 不允许走 advance");
        }
    }

    @Test
    void skippingAndReversingAreIllegal() {
        assertFalse(DeliveryTransitions.allowed(1, 3), "不允许跳过接单直接配送");
        assertFalse(DeliveryTransitions.allowed(2, 4), "不允许跳过离站直接送达");
        assertFalse(DeliveryTransitions.allowed(3, 5), "不允许跳过送达直接签收");
        assertFalse(DeliveryTransitions.allowed(5, 4), "已签收不允许回退");
        assertFalse(DeliveryTransitions.allowed(4, 3), "已送达不允许回退");
        assertFalse(DeliveryTransitions.allowed(5, 5), "重复签收非法");
    }

    @Test
    void cancelOnlyFromPending() {
        assertTrue(DeliveryTransitions.allowed(1, 6), "待接单可自助取消");
        // 已接单之后配送员已投入履约成本，取消必须走异常/申诉链路；
        // 这条边一旦放宽，用户就能在配送员出发后单方面撤单并全额退款
        for (int from : new int[]{2, 3, 4, 5, 6, 7}) {
            assertFalse(DeliveryTransitions.allowed(from, 6), "状态 " + from + " 不允许自助取消");
        }
    }
}

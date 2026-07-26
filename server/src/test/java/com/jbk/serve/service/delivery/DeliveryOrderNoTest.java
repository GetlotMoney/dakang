package com.jbk.serve.service.delivery;

import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配送单号/任务号派生单测（E2E-03 规则3/4）：确定性、命名空间隔离、禁日期禁序列。
 */
class DeliveryOrderNoTest {

    private static final String UUID_A = "1f4a2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b";
    private static final String UUID_B = "2a4a2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b";

    @Test
    void deriveIsDeterministicAndPrefixed() {
        String first = DeliveryOrderNo.derive(9L, UUID_A);
        String second = DeliveryOrderNo.derive(9L, UUID_A);
        assertEquals(first, second, "同用户同 requestId 跨重放必须恒定同号");
        assertTrue(first.startsWith("WD"));
        assertEquals(32, first.length());
    }

    @Test
    void differentUserOrRequestYieldsDifferentNo() {
        assertNotEquals(DeliveryOrderNo.derive(9L, UUID_A), DeliveryOrderNo.derive(7L, UUID_A));
        assertNotEquals(DeliveryOrderNo.derive(9L, UUID_A), DeliveryOrderNo.derive(9L, UUID_B));
    }

    @Test
    void autoRefillNamespaceNeverCollidesWithUserRequests() {
        String auto = DeliveryOrderNo.deriveAutoRefill(9L, 5L, 1);
        assertTrue(auto.startsWith("WD"));
        assertEquals(32, auto.length());
        assertNotEquals(auto, DeliveryOrderNo.derive(9L, UUID_A));
        // 期序参与幂等键：不同期不同号，同期恒定同号（规则19）
        assertEquals(auto, DeliveryOrderNo.deriveAutoRefill(9L, 5L, 1));
        assertNotEquals(auto, DeliveryOrderNo.deriveAutoRefill(9L, 5L, 2));
        assertNotEquals(auto, DeliveryOrderNo.deriveAutoRefill(9L, 6L, 1));
    }

    @Test
    void taskNoDerivesFromOrderNoDeterministically() {
        String orderNo = DeliveryOrderNo.derive(9L, UUID_A);
        String taskNo = DeliveryOrderNo.deriveTaskNo(orderNo);
        assertEquals(taskNo, DeliveryOrderNo.deriveTaskNo(orderNo));
        assertTrue(taskNo.startsWith("DT"));
        assertEquals(32, taskNo.length());
        assertNotEquals(taskNo, DeliveryOrderNo.deriveTaskNo(DeliveryOrderNo.derive(7L, UUID_A)));
    }

    @Test
    void invalidInputsAreRejected() {
        assertThrows(JbkException.class, () -> DeliveryOrderNo.derive(null, UUID_A));
        assertThrows(JbkException.class, () -> DeliveryOrderNo.derive(0L, UUID_A));
        assertThrows(JbkException.class, () -> DeliveryOrderNo.derive(9L, "UPPER-CASE-UUID"));
        assertThrows(JbkException.class, () -> DeliveryOrderNo.derive(9L, null));
        assertThrows(JbkException.class, () -> DeliveryOrderNo.deriveAutoRefill(9L, null, 1));
        assertThrows(JbkException.class, () -> DeliveryOrderNo.deriveAutoRefill(9L, 5L, 0));
        assertThrows(JbkException.class, () -> DeliveryOrderNo.deriveTaskNo(" "));
    }
}

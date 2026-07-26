package com.jbk.serve.service.trade;

import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WaterBillingMathTest {

    @Test
    void calculatesCeilingAmountWithoutFloatingPoint() {
        assertEquals(0L, WaterBillingMath.ceilAmount(0L, 20));
        assertEquals(1L, WaterBillingMath.ceilAmount(1L, 20));
        assertEquals(100L, WaterBillingMath.ceilAmount(5_000L, 20));
    }

    @Test
    void rejectsPlanOutsideFrozenCreateRange() {
        assertEquals(99_000L, WaterBillingMath.requirePlanMl(99_000L));
        assertThrows(JbkException.class, () -> WaterBillingMath.requirePlanMl(null));
        assertThrows(JbkException.class, () -> WaterBillingMath.requirePlanMl(0L));
        assertThrows(JbkException.class, () -> WaterBillingMath.requirePlanMl(99_001L));
    }

    @Test
    void rejectsInvalidPriceAndCheckedArithmeticOverflow() {
        assertThrows(JbkException.class, () -> WaterBillingMath.requireUnitPrice(-1));
        assertThrows(JbkException.class, () -> WaterBillingMath.requireUnitPrice(100_001));
        assertThrows(JbkException.class,
                () -> WaterBillingMath.ceilAmount(Long.MAX_VALUE, WaterBillingMath.UNIT_PRICE_MAX_FEN_PER_LITER));
    }
}

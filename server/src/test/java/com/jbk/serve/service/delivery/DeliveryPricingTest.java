package com.jbk.serve.service.delivery;

import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 金额快照计算单测（E2E-03 规则1/2）：水费/配送费分开、总额恒等式、
 * 白名单规格、数量界、非法输入 fail-closed。价目与 Mock 契约
 * CONTAINER_WATER_PRICE_FEN / DELIVERY_FEE_PER_CONTAINER_FEN 逐值对齐。
 */
class DeliveryPricingTest {

    @Test
    void quoteSplitsWaterAndFeeAndTotalIsSum() {
        DeliveryPricing.Quote quote = DeliveryPricing.quote("20L桶", 2);
        assertEquals(2600L, quote.waterAmountFen(), "20L桶 1300/桶 × 2");
        assertEquals(400L, quote.deliveryFeeFen(), "配送费 200/桶 × 2");
        assertEquals(3000L, quote.totalFen(), "总额=水费+配送费（规则2）");
    }

    @Test
    void quoteMatchesMockPriceTablePerSpec() {
        assertEquals(300L, DeliveryPricing.quote("3L袋", 1).waterAmountFen());
        assertEquals(500L, DeliveryPricing.quote("5L桶", 1).waterAmountFen());
        assertEquals(1200L, DeliveryPricing.quote("10L桶", 1).waterAmountFen());
        assertEquals(1300L, DeliveryPricing.quote("20L桶", 1).waterAmountFen());
        assertEquals(200L, DeliveryPricing.quote("3L袋", 1).deliveryFeeFen());
    }

    @Test
    void unknownContainerSpecIsRejected() {
        assertThrows(JbkException.class, () -> DeliveryPricing.quote("50L桶", 1));
        assertThrows(JbkException.class, () -> DeliveryPricing.quote(null, 1));
        assertThrows(JbkException.class, () -> DeliveryPricing.quote("", 1));
    }

    @Test
    void countBoundsAreEnforcedBothSides() {
        assertThrows(JbkException.class, () -> DeliveryPricing.quote("20L桶", 0));
        assertThrows(JbkException.class, () -> DeliveryPricing.quote("20L桶", -1));
        assertThrows(JbkException.class, () -> DeliveryPricing.quote("20L桶", null));
        assertThrows(JbkException.class, () -> DeliveryPricing.quote("20L桶", 100));
        // 上界内最大值可计算且不溢出
        assertEquals(99L * 1300 + 99L * 200, DeliveryPricing.quote("20L桶", 99).totalFen());
    }

    @Test
    void returnCountAllowsZeroButRejectsNegativeAndOverflowAndNull() {
        assertEquals(0, DeliveryPricing.requireReturnCount(0));
        assertEquals(99, DeliveryPricing.requireReturnCount(99));
        assertThrows(JbkException.class, () -> DeliveryPricing.requireReturnCount(-1));
        assertThrows(JbkException.class, () -> DeliveryPricing.requireReturnCount(100));
        assertThrows(JbkException.class, () -> DeliveryPricing.requireReturnCount(null));
    }
}

package com.jbk.serve.service.mini.recharge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RechargeExpiryBoundaryTest {

    @Test
    void expireTimeEqualToProcessingTimeIsAlreadyExpired() {
        assertTrue(RechargeExpiry.naturallyExpired("20260722120000", "20260722120000"));
        assertTrue(RechargeExpiry.naturallyExpired("20260722115959", "20260722120000"));
        assertFalse(RechargeExpiry.naturallyExpired("20260722120001", "20260722120000"));
    }

    @Test
    void deductionSqlUsesStrictFutureExpiryAndKeepsLegacyBlankCompatibility() throws IOException {
        String resource = "/mapper/trade/TradeCardMapper.xml";
        String xml;
        try (var input = RechargeExpiryBoundaryTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("missing resource " + resource);
            }
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(xml.contains("EXPIRE_TIME &gt; #{now}"));
        assertFalse(xml.contains("EXPIRE_TIME &gt;= #{now}"));
        assertTrue(xml.contains("EXPIRE_TIME = ''"));
    }
}

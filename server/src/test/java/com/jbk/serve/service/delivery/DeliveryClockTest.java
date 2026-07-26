package com.jbk.serve.service.delivery;

import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 履约时钟单测：单调地板、申诉截止 +24h、自动补货期序。
 */
class DeliveryClockTest {

    @Test
    void floorPicksMaxOfNowAndFloors() {
        assertEquals("20260723120000", DeliveryClock.floor("20260723120000"));
        assertEquals("20260723120000", DeliveryClock.floor("20260723120000", (String) null));
        // 前置节点晚于当前时钟（回拨/同秒并发）：动作时间抬升到地板，保证单调
        assertEquals("20260723130000", DeliveryClock.floor("20260723120000", "20260723130000"));
        assertEquals("20260723140000",
                DeliveryClock.floor("20260723120000", "20260723130000", "20260723140000", null));
    }

    @Test
    void plusHoursComputesAppealDeadlineAcrossDays() {
        assertEquals("20260724120000", DeliveryClock.plusHours("20260723120000", 24));
        // 跨月边界
        assertEquals("20260801010000", DeliveryClock.plusHours("20260731010000", 24));
    }

    @Test
    void periodIndexFloorsByIntervalDays() {
        assertEquals(0, DeliveryClock.periodIndex("20260701000000", "20260701000000", 7), "锚点当刻=第0期");
        assertEquals(0, DeliveryClock.periodIndex("20260701000000", "20260707235959", 7), "不满一周期仍是第0期");
        assertEquals(1, DeliveryClock.periodIndex("20260701000000", "20260708000000", 7));
        assertEquals(2, DeliveryClock.periodIndex("20260701000000", "20260715120000", 7));
        assertEquals(0, DeliveryClock.periodIndex("20260701000000", "20260630000000", 7), "now 早于锚点=0");
    }

    @Test
    void malformedTimesAreRejected() {
        assertThrows(JbkException.class, () -> DeliveryClock.floor("2026-07-23"));
        assertThrows(JbkException.class, () -> DeliveryClock.floor(null));
        assertThrows(JbkException.class, () -> DeliveryClock.plusHours("20269999999999", 24));
        assertThrows(JbkException.class, () -> DeliveryClock.periodIndex("20260701000000", "20260708000000", 0));
    }
}

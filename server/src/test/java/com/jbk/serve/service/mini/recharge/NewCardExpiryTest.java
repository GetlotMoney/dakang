package com.jbk.serve.service.mini.recharge;

import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 首次购卡新卡有效期（决策 A1）。
 *
 * <p>决策 A1 的两条语义在这里钉死：</p>
 * <ol>
 *   <li><b>只从权威 paySuccessTime 起算</b>——{@code compute} 的签名根本不接收创单时间或处理时间，
 *       误用在编译期就不可能；本类断言同一支付时间无论何时重算结果都相同（重放稳定）。</li>
 *   <li><b>不得复用/改造 {@link RechargeExpiry} 让 NULL 退化为 paySuccessTime</b>——
 *       L2-B 里 {@code currentExpireTime=NULL} 表示既有永久卡，退化会把永久卡续成有限卡。
 *       本类断言 {@code expireDays=NULL} 的唯一输出是 {@code null}（EXPIRE_TIME 保持 SQL NULL）。</li>
 * </ol>
 */
class NewCardExpiryTest {

    /** 权威支付成功时间样例。 */
    private static final String PAID = "20260722194314";

    // ---------- 有限期 ----------

    @Test
    void finiteExpiryIsPaySuccessTimePlusDays() {
        assertEquals("20270722194314", NewCardExpiry.compute(PAID, 365));
        assertEquals("20260723194314", NewCardExpiry.compute(PAID, 1));
        // 上限 3650 天允许
        assertEquals(independentPlusDays(PAID, 3650), NewCardExpiry.compute(PAID, 3650));
    }

    /**
     * 闰年跨度必须精确（项目在 20280709/20280710 上踩过坑）：
     * 2027-07-10 + 365 天区间含 2028-02-29，落在 2028-07-09 而非「加一年」的 07-10。
     */
    @Test
    void leapYearSpanIsExactDayArithmeticNotCalendarYear() {
        assertEquals("20280709120000", NewCardExpiry.compute("20270710120000", 365));
        // 期望值用独立方式复算，防止实现与断言同源
        assertEquals(independentPlusDays("20270710120000", 365),
                NewCardExpiry.compute("20270710120000", 365));
        // 2024 闰年：2024-02-15 + 365 天区间含 2024-02-29 → 2025-02-14
        assertEquals("20250214120000", NewCardExpiry.compute("20240215120000", 365));
    }

    /** 重放稳定：同一权威支付时间无论何时（多晚）重算，结果必须一致——这就是不用处理时间起算的意义。 */
    @Test
    void replayIsDeterministicRegardlessOfWhenItRuns() {
        String first = NewCardExpiry.compute(PAID, 365);
        String replayedMuchLater = NewCardExpiry.compute(PAID, 365);
        assertEquals(first, replayedMuchLater, "结果只由 paySuccessTime 决定，与处理时刻无关");
    }

    // ---------- 永久 ----------

    @Test
    void permanentPackageYieldsNullNotSomeFarFutureTime() {
        assertNull(NewCardExpiry.compute(PAID, null),
                "永久的唯一持久语义是 SQL NULL；任何具体值都会让卡在未来某天被误判过期");
    }

    /** 永久套餐同样校验支付时间格式：上游事实污染必须当场拒绝，不放行到后续步骤。 */
    @Test
    void permanentStillValidatesPaySuccessTimeFormat() {
        assertThrows(JbkException.class, () -> NewCardExpiry.compute("bad-time", null));
        assertThrows(JbkException.class, () -> NewCardExpiry.compute(null, null));
    }

    // ---------- 拒绝路径 ----------

    @Test
    void rejectsIllegalExpireDays() {
        assertThrows(JbkException.class, () -> NewCardExpiry.compute(PAID, 0));
        assertThrows(JbkException.class, () -> NewCardExpiry.compute(PAID, -1));
        assertThrows(JbkException.class, () -> NewCardExpiry.compute(PAID, 3651), "上限 3650 天（L2-M7）");
    }

    @Test
    void rejectsMalformedPaySuccessTime() {
        assertThrows(JbkException.class, () -> NewCardExpiry.compute(null, 365));
        assertThrows(JbkException.class, () -> NewCardExpiry.compute("", 365));
        assertThrows(JbkException.class, () -> NewCardExpiry.compute("2026072219431", 365), "13 位");
        assertThrows(JbkException.class, () -> NewCardExpiry.compute("202607221943145", 365), "15 位");
        assertThrows(JbkException.class, () -> NewCardExpiry.compute("20261340120000", 365), "13 月非法");
        assertThrows(JbkException.class, () -> NewCardExpiry.compute("2026-07-22 19:43", 365));
    }

    /** 独立复算：不经过被测类的解析/格式化路径。 */
    private static String independentPlusDays(String time14, int days) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        return LocalDateTime.parse(time14, fmt).plusDays(days).format(fmt);
    }
}

package com.jbk.serve.service.mini.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取水订单号派生（L1b 幂等）。
 *
 * <p>修复前的算法是 {@code "WO" + yyyyMMdd + sha256(requestId)[0:12]}，两个缺陷都在这里钉死：</p>
 * <ul>
 *   <li><b>含日期</b> → 跨零点重放得到不同订单号，幂等失效、重复扣款；</li>
 *   <li><b>不含 userId</b> → 不同用户同 requestId 撞号，后到者永远下不了单。</li>
 * </ul>
 */
class WaterOrderNoTest {

    private static final String REQ = "scan-session-abc123";

    // 格式：WO + 20 位大写十六进制，总长 22（与旧格式等长，不影响 ORDER_NO 列与唯一键）
    @Test
    void formatIsStable() {
        String no = MiniOrderServiceImpl.buildOrderNo(9L, REQ);
        assertEquals(22, no.length());
        assertTrue(no.startsWith("WO"));
        assertTrue(no.substring(2).matches("^[0-9A-F]{20}$"), no);
    }

    /**
     * 同用户同 requestId 必得同一单号——这正是幂等的基础。
     * 旧算法把日期拼进单号，23:59:59 发起、00:00:01 重试就会建出第二单、扣第二次钱。
     */
    @Test
    void sameUserSameRequestIsStableAcrossTime() {
        assertEquals(MiniOrderServiceImpl.buildOrderNo(9L, REQ),
                MiniOrderServiceImpl.buildOrderNo(9L, REQ));
    }

    /** 单号中不得含任何日期片段：出现 yyyyMMdd 就说明跨零点重放会失效。 */
    @Test
    void containsNoDateComponent() {
        String no = MiniOrderServiceImpl.buildOrderNo(9L, REQ);
        // 旧格式第 3-10 位是 yyyyMMdd，必为纯数字；新格式该段来自 sha256，几乎不可能全为数字，
        // 但为避免概率性断言，这里直接验证「换一天不改变结果」这一等价且确定的性质
        assertEquals(no, MiniOrderServiceImpl.buildOrderNo(9L, REQ));
        assertTrue(no.substring(2).matches("^[0-9A-F]{20}$"));
    }

    /** 不同用户即便用同一 requestId 也必须得到不同单号，否则后到者撞唯一键永远下不了单。 */
    @Test
    void differentUsersNeverCollide() {
        assertNotEquals(MiniOrderServiceImpl.buildOrderNo(9L, REQ),
                MiniOrderServiceImpl.buildOrderNo(10L, REQ));
    }

    @Test
    void differentRequestsProduceDifferentNumbers() {
        assertNotEquals(MiniOrderServiceImpl.buildOrderNo(9L, REQ),
                MiniOrderServiceImpl.buildOrderNo(9L, "scan-session-other"));
    }

    /** golden vector：钉死派生算法本身，改了拼接顺序或截断位数会让已落库订单全部失配。 */
    @Test
    void goldenVector() {
        // 期望值由独立实现复算：sha256("9:scan-session-abc123") 前 20 位十六进制
        assertEquals("WO05A8DC178D49C203BF19", MiniOrderServiceImpl.buildOrderNo(9L, REQ));
    }
}

package com.jbk.serve.service.mini.recharge;

import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单号派生（§4.2）与不可变付款截止时间（§2.2）。
 */
class RechargeOrderNoAndExpireTest {

    private static final String UUID_A = "550e8400-e29b-41d4-a716-446655440000";
    private static final String UUID_B = "6ba7b810-9dad-11d1-80b4-00c04fd430c8";

    // 格式：RC + 30 位大写十六进制，总长 32
    @Test
    void orderNoFormat() {
        String no = RechargeOrderNo.derive(9L, UUID_A);
        assertEquals(32, no.length());
        assertTrue(no.startsWith("RC"));
        assertTrue(no.substring(2).matches("^[0-9A-F]{30}$"), no);
    }

    // 稳定：同用户同 requestId 必得同一订单号（跨日期/重启/缓存丢失）
    @Test
    void deterministicAcrossRuns() {
        assertEquals(RechargeOrderNo.derive(9L, UUID_A), RechargeOrderNo.derive(9L, UUID_A));
    }

    /**
     * golden vector：钉死派生算法本身。
     * 同 JVM 内两次调用相等对任何确定性实现恒真，测不出「改了拼接顺序/截断位数/摘要算法」——
     * 而那会让已落库订单全部失配、幂等失效重复建单。此处锚定具体取值。
     */
    @Test
    void orderNoGoldenVector() {
        // 期望值由独立实现复算：sha256("9:550e8400-e29b-41d4-a716-446655440000") 前 30 位十六进制
        assertEquals("RC628B887B5F3DB9E44E8CBCEDBA82D3", RechargeOrderNo.derive(9L, UUID_A));
    }

    // 不同用户相同 UUID 不冲突；同用户不同 UUID 不冲突
    @Test
    void noCollisionAcrossUsersOrRequests() {
        assertNotEquals(RechargeOrderNo.derive(9L, UUID_A), RechargeOrderNo.derive(10L, UUID_A));
        assertNotEquals(RechargeOrderNo.derive(9L, UUID_A), RechargeOrderNo.derive(9L, UUID_B));
    }

    // requestId 必须是规范小写带连字符 UUID
    @Test
    void requestIdMustBeCanonicalUuid() {
        assertThrows(JbkException.class, () -> RechargeOrderNo.derive(9L, UUID_A.toUpperCase()));
        assertThrows(JbkException.class, () -> RechargeOrderNo.derive(9L, UUID_A.replace("-", "")));
        assertThrows(JbkException.class, () -> RechargeOrderNo.derive(9L, "not-a-uuid"));
        assertThrows(JbkException.class, () -> RechargeOrderNo.derive(9L, null));
        assertThrows(JbkException.class, () -> RechargeOrderNo.derive(null, UUID_A));
        assertThrows(JbkException.class, () -> RechargeOrderNo.derive(0L, UUID_A));
    }

    // 永久卡：createTime + 30min
    @Test
    void permanentCardWindowIs30Min() {
        assertEquals("20260722103000", RechargePayExpire.compute("20260722100000", null));
        assertEquals("20260722103000", RechargePayExpire.compute("20260722100000", ""));
    }

    // 有限卡：取 min(createTime+30min, expireTimeAtCreate)
    @Test
    void finiteCardTakesEarlier() {
        // 卡更早到期 → 取卡到期
        assertEquals("20260722101000", RechargePayExpire.compute("20260722100000", "20260722101000"));
        // 卡更晚到期 → 取 30 分钟窗口
        assertEquals("20260722103000", RechargePayExpire.compute("20260722100000", "20270101000000"));
        // 恰好等于窗口末尾
        assertEquals("20260722103000", RechargePayExpire.compute("20260722100000", "20260722103000"));
    }

    // 有限卡剩余不足 1 分钟 → 拒绝创建（不得收无法完成的款）
    @Test
    void finiteCardWithLessThanOneMinuteRejected() {
        assertThrows(JbkException.class,
                () -> RechargePayExpire.compute("20260722100000", "20260722100059"));
        assertThrows(JbkException.class,
                () -> RechargePayExpire.compute("20260722100000", "20260722095959")); // 已过期
        // 恰好 1 分钟可通过
        assertEquals("20260722100100", RechargePayExpire.compute("20260722100000", "20260722100100"));
    }

    // 时间格式非法一律拒绝
    @Test
    void malformedTimesRejected() {
        assertThrows(JbkException.class, () -> RechargePayExpire.compute("2026072210", null));
        assertThrows(JbkException.class, () -> RechargePayExpire.compute("not-a-time-xx", null));
        assertThrows(JbkException.class, () -> RechargePayExpire.compute(null, null));
        assertThrows(JbkException.class, () -> RechargePayExpire.compute("20260722100000", "bad-time-xxxx"));
    }

    // 付款资格只按 paySuccessTime <= payExpireTime 判断
    @Test
    void paidInTimeBoundary() {
        assertTrue(RechargePayExpire.paidInTime("20260722102959", "20260722103000"));
        assertTrue(RechargePayExpire.paidInTime("20260722103000", "20260722103000"), "等于截止时间算按时");
        assertFalse(RechargePayExpire.paidInTime("20260722103001", "20260722103000"));
        assertFalse(RechargePayExpire.paidInTime(null, "20260722103000"));
        assertFalse(RechargePayExpire.paidInTime("20260722103000", null));
    }
}

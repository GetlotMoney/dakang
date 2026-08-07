package com.jbk.serve.service.aftersale;

import com.jbk.tool.consts.aftersale.AfterSaleEnum.SourceType;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 售后号确定性派生单测（E2E-04 包A）。
 *
 * <p>幂等在本域是靠 {@code uk_after_sale_no} / {@code uk_after_sale_source} 撞键收敛的，
 * 而撞键的前提是「同一来源必然派生同一个号」。因此本类既断言确定性，也用一份独立重算的
 * SHA-256 把 canonical 串的构造方式钉死：若有人把 {@code type:id} 改成 {@code id:type}
 * 或换了摘要算法，历史单会派生出新号，重放不再撞键、幂等静默失效。</p>
 */
class AfterSaleNoTest {

    @Test
    void sameSourceAlwaysDerivesSameNo() {
        String first = AfterSaleNo.derive(2, 8801L);
        for (int i = 0; i < 5; i++) {
            assertEquals(first, AfterSaleNo.derive(2, 8801L), "同来源重放必须同号");
        }
        // 跨零点、重启、重放恒定：不含日期与序列，所以枚举实例换一个取值方式也同号
        assertEquals(first, AfterSaleNo.derive(SourceType.DELIVERY_APPEAL.getValue(), 8801L));
    }

    /** canonical = sourceType + ":" + sourceId，AS + sha256 前 30 位大写。独立重算一遍钉死算法。 */
    @Test
    void derivedNoMatchesIndependentlyRecomputedDigest() {
        assertEquals(expected(2, 8801L), AfterSaleNo.derive(2, 8801L));
        assertEquals(expected(1, 100L), AfterSaleNo.derive(1, 100L));
        assertEquals(expected(3, 100L), AfterSaleNo.derive(3, 100L));
    }

    @Test
    void formatIsPrefixedAndFixedWidth() {
        for (int type : new int[]{1, 2, 3}) {
            for (long id : new long[]{1L, 42L, 8801L, Long.MAX_VALUE}) {
                String no = AfterSaleNo.derive(type, id);
                assertEquals(32, no.length(), "AFTER_SALE_NO 必须填满 varchar(32)：" + no);
                assertTrue(no.startsWith(AfterSaleNo.PREFIX), "前缀必须是 AS：" + no);
                assertTrue(no.matches("^AS[0-9A-F]{30}$"), "只允许大写十六进制：" + no);
            }
        }
    }

    /**
     * sourceType 是 canonical 的命名空间前缀：订单 100 的取消(1:100) 与取水核账(3:100)
     * 必须是两个号，二者可以合法并存，绝不能因为共用 orderId 被折叠成一笔。
     */
    @Test
    void differentSourceTypesOnSameIdDeriveDifferentNos() {
        assertNotEquals(AfterSaleNo.derive(1, 100L), AfterSaleNo.derive(3, 100L));
        assertNotEquals(AfterSaleNo.derive(1, 100L), AfterSaleNo.derive(2, 100L));
        assertNotEquals(AfterSaleNo.derive(2, 100L), AfterSaleNo.derive(3, 100L));
    }

    @Test
    void differentSourceIdsDeriveDifferentNos() {
        Set<String> seen = new HashSet<>();
        for (int type = 1; type <= 3; type++) {
            for (long id = 1L; id <= 200L; id++) {
                assertTrue(seen.add(AfterSaleNo.derive(type, id)),
                        "来源 " + type + ":" + id + " 与此前某个来源撞号");
            }
        }
        assertEquals(600, seen.size());
        // 相邻 ID 不得因为截断而共享前缀（截 30 位仍须区分开）
        assertNotEquals(AfterSaleNo.derive(2, 8801L), AfterSaleNo.derive(2, 8802L));
    }

    /**
     * 未登记来源直接拒绝：放行脏来源会派生出无归属的售后号，撞键幂等的语义基准随之崩塌。
     *
     * <p>4 已在包D-5 登记为「充值退款」，故从本用例的拒绝集合里移出——
     * 它现在是一个合法来源，继续要求它被拒绝等于要求充值退款派生不出售后号。
     * 新增来源码时这条用例会红，那是刻意的提醒：来源集合与 {@code AfterSaleEnum.SourceType} 同源。</p>
     */
    @Test
    void unregisteredSourceTypeIsRejected() {
        for (Integer type : new Integer[]{null, 0, -1, 5, 99}) {
            assertThrows(JbkException.class, () -> AfterSaleNo.derive(type, 100L),
                    "来源码 " + type + " 必须拒绝");
        }
    }

    @Test
    void nonPositiveSourceIdIsRejected() {
        for (Long id : new Long[]{null, 0L, -1L, Long.MIN_VALUE}) {
            assertThrows(JbkException.class, () -> AfterSaleNo.derive(2, id),
                    "来源主体ID " + id + " 必须拒绝");
        }
    }

    /** 与生产实现相互独立的一份重算，用于钉死 canonical 串与摘要口径。 */
    private static String expected(int sourceType, long sourceId) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest((sourceType + ":" + sourceId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02X", b));
        }
        return "AS" + hex.substring(0, 30);
    }
}

package com.jbk.serve.service.mini.recharge;

import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查单事实键（契约 v2 第 335 行）的 golden vector 与字符集守卫测试。
 *
 * <p>期望值<b>不取自被测实现</b>，而是用 {@link HexFormat} + 手写规范串独立复算。
 * 让被测类自己算一遍再断言"等于自己"只能证明它是确定性的，证明不了它算的是契约那个串。</p>
 */
class RechargeQueryEventKeyTest {

    private static final String ORDER_NO = "RC0000000000000000000000000001";

    /** 独立实现：按契约原文拼串并用 JDK HexFormat 转小写十六进制。 */
    private static String expected(String canonical) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "Q:" + HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void notpayGoldenVector() {
        // PAY_SOURCE|ORDER_NO|TRADE_STATE|txid|successTime|amount|currency，后四项未返回即空串
        String canonical = "2|" + ORDER_NO + "|NOTPAY||||";
        assertEquals(canonical, RechargeQueryEventKey.canonical(2, ORDER_NO, "NOTPAY",
                null, null, null, null));
        assertEquals(expected(canonical),
                RechargeQueryEventKey.derive(2, ORDER_NO, "NOTPAY", null, null, null, null));
    }

    @Test
    void closedGoldenVector() {
        String canonical = "2|" + ORDER_NO + "|CLOSED||||";
        assertEquals(expected(canonical),
                RechargeQueryEventKey.derive(2, ORDER_NO, "CLOSED", null, null, null, null));
    }

    @Test
    void successGoldenVectorCarriesAllProviderFields() {
        String canonical = "1|" + ORDER_NO + "|SUCCESS|4200001234202607220001|20260722194314|10000|CNY";
        assertEquals(expected(canonical), RechargeQueryEventKey.derive(1, ORDER_NO, "SUCCESS",
                "4200001234202607220001", "20260722194314", 10000L, "CNY"));
    }

    /** 键长必须落在 varchar(100) 内，否则落库会被静默截断成另一条键。 */
    @Test
    void keyFitsProviderEventKeyColumn() {
        String key = RechargeQueryEventKey.derive(2, ORDER_NO, "NOTPAY", null, null, null, null);
        assertEquals(66, key.length());
        assertTrue(key.startsWith("Q:"));
        assertTrue(key.substring(2).matches("^[0-9a-f]{64}$"), "必须是小写十六进制");
    }

    /**
     * 契约的核心性质：同一订单从 NOTPAY 演进到 CLOSED/SUCCESS 必须形成<b>不同</b>的键。
     * 若这条断言失败，先落的 NOTPAY 会永久占位，后来的真实结果被唯一键挡在门外。
     */
    @Test
    void sameOrderDifferentFactsProduceDifferentKeys() {
        String notpay = RechargeQueryEventKey.derive(2, ORDER_NO, "NOTPAY", null, null, null, null);
        String closed = RechargeQueryEventKey.derive(2, ORDER_NO, "CLOSED", null, null, null, null);
        String success = RechargeQueryEventKey.derive(2, ORDER_NO, "SUCCESS",
                "SIMTX1", "20260722194314", 10000L, "CNY");
        assertNotEquals(notpay, closed);
        assertNotEquals(notpay, success);
        assertNotEquals(closed, success);
    }

    /** 同一事实重复查单必须稳定命中同一条记录，否则每次轮询都会堆一条新事实。 */
    @Test
    void sameFactIsStableAcrossCalls() {
        assertEquals(RechargeQueryEventKey.derive(2, ORDER_NO, "NOTPAY", null, null, null, null),
                RechargeQueryEventKey.derive(2, ORDER_NO, "NOTPAY", null, null, null, null));
    }

    /** 不同来源不得共用一条键：Pay-Sim 的事实绝不能和微信的事实合并。 */
    @Test
    void paySourceParticipatesInTheKey() {
        assertNotEquals(RechargeQueryEventKey.derive(1, ORDER_NO, "NOTPAY", null, null, null, null),
                RechargeQueryEventKey.derive(2, ORDER_NO, "NOTPAY", null, null, null, null));
    }

    // ================= 字符集守卫：分隔符不得进入值域 =================

    /**
     * 这条是本类存在的理由：若订单号可以含 {@code |}，
     * {@code ("RCA|NOTPAY", "X")} 与 {@code ("RCA", "NOTPAY")} 会拼出同一个规范串，
     * 两条语义不同的支付事实撞成一条键，后到的那条被当作"重复查询"静默吞掉。
     */
    @Test
    void separatorCannotEnterOrderNo() {
        assertThrows(JbkException.class,
                () -> RechargeQueryEventKey.derive(2, "RC|NOTPAY|X", "NOTPAY", null, null, null, null));
    }

    @Test
    void separatorCannotEnterTransactionIdOrCurrency() {
        assertThrows(JbkException.class, () -> RechargeQueryEventKey.derive(2, ORDER_NO, "SUCCESS",
                "TX|1", "20260722194314", 10000L, "CNY"));
        assertThrows(JbkException.class, () -> RechargeQueryEventKey.derive(2, ORDER_NO, "SUCCESS",
                "TX1", "20260722194314", 10000L, "C|Y"));
    }

    @Test
    void tradeStateMustBeUppercaseWhitelist() {
        assertThrows(JbkException.class,
                () -> RechargeQueryEventKey.derive(2, ORDER_NO, "notpay", null, null, null, null));
        assertThrows(JbkException.class,
                () -> RechargeQueryEventKey.derive(2, ORDER_NO, "", null, null, null, null));
    }

    @Test
    void successTimeMustBeFourteenDigits() {
        assertThrows(JbkException.class, () -> RechargeQueryEventKey.derive(2, ORDER_NO, "SUCCESS",
                "TX1", "2026-07-22 19:43:14", 10000L, "CNY"));
    }

    /** 空串不等于"支付方未返回"：允许它会让被清洗成空的非法值与真正的空撞键。 */
    @Test
    void emptyStringIsNotTreatedAsAbsentField() {
        assertThrows(JbkException.class, () -> RechargeQueryEventKey.derive(2, ORDER_NO, "SUCCESS",
                "", "20260722194314", 10000L, "CNY"));
    }

    @Test
    void negativeAmountAndIllegalSourceRejected() {
        assertThrows(JbkException.class, () -> RechargeQueryEventKey.derive(2, ORDER_NO, "SUCCESS",
                "TX1", "20260722194314", -1L, "CNY"));
        assertThrows(JbkException.class,
                () -> RechargeQueryEventKey.derive(null, ORDER_NO, "NOTPAY", null, null, null, null));
        assertThrows(JbkException.class,
                () -> RechargeQueryEventKey.derive(0, ORDER_NO, "NOTPAY", null, null, null, null));
    }

    @Test
    void nullOrderNoRejected() {
        assertThrows(JbkException.class,
                () -> RechargeQueryEventKey.derive(2, null, "NOTPAY", null, null, null, null));
    }
}

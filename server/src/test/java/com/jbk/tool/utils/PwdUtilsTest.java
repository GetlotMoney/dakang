package com.jbk.tool.utils;

import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-201 口令工具：哈希/校验的 fail-closed 边界、初始口令生成契约与强度底线。
 */
class PwdUtilsTest {

    // ==================== hash / verify ====================

    @Test
    void hashProducesBcryptAndVerifies() {
        String hash = PwdUtils.hash("abc12345");
        assertTrue(hash.startsWith("$2"), "存储格式必须是 BCrypt");
        assertTrue(PwdUtils.verify("abc12345", hash));
        assertFalse(PwdUtils.verify("abc12346", hash));
    }

    @Test
    void sameplainHashesDifferButBothVerify() {
        String h1 = PwdUtils.hash("abc12345");
        String h2 = PwdUtils.hash("abc12345");
        assertNotEquals(h1, h2, "随机盐下同一明文两次哈希必须不同");
        assertTrue(PwdUtils.verify("abc12345", h1));
        assertTrue(PwdUtils.verify("abc12345", h2));
    }

    @Test
    void hashRejectsBlank() {
        assertThrows(JbkException.class, () -> PwdUtils.hash(""));
        assertThrows(JbkException.class, () -> PwdUtils.hash("   "));
        assertThrows(JbkException.class, () -> PwdUtils.hash(null));
    }

    /**
     * 历史 RSA 密文（Base64 形态）必须整体判不匹配且不抛异常：
     * 迁移前的存量行在新代码下 fail-closed，而不是 500。
     */
    @Test
    void legacyRsaCipherStoredValueFailsClosed() {
        String legacyRsa = "B51yw4neAThtpTnUhsmvp+Ikho2Cu6ToTXw3c3iDtbTR7HSOfPIk6vY0cHjGRQzy"
                + "6UdINaYqQbKpKeBOYSYAw/d1oQM2OmevVJ2UgCUTi3eVopeNXL5YHk+Uyx6JDV61M0M3kymmNn5Z";
        assertFalse(PwdUtils.verify("123456", legacyRsa));
    }

    @Test
    void malformedOrBlankStoredValueFailsClosed() {
        assertFalse(PwdUtils.verify("abc12345", null));
        assertFalse(PwdUtils.verify("abc12345", ""));
        assertFalse(PwdUtils.verify("abc12345", "$2"));
        assertFalse(PwdUtils.verify("abc12345", "$2a$10$broken"));
        assertFalse(PwdUtils.verify("abc12345", "plaintext"));
    }

    @Test
    void blankPlainNeverMatches() {
        String hash = PwdUtils.hash("abc12345");
        assertFalse(PwdUtils.verify(null, hash));
        assertFalse(PwdUtils.verify("", hash));
    }

    // ==================== generateInitialPwd ====================

    @Test
    void initialPwdMeetsCharsetAndCompositionContract() {
        for (int i = 0; i < 20; i++) {
            String pwd = PwdUtils.generateInitialPwd();
            assertEquals(10, pwd.length(), "初始口令固定 10 位");
            assertTrue(pwd.chars().anyMatch(Character::isLetter), "必须含字母");
            assertTrue(pwd.chars().anyMatch(Character::isDigit), "必须含数字");
            // 剔除易混字符：0 O 1 l I i o L 不得出现
            assertTrue(pwd.chars().noneMatch(ch -> "0O1lIiLo".indexOf(ch) >= 0),
                    "初始口令不得包含易混字符：" + pwd);
            // 生成的初始口令必须直接满足自设口令强度底线
            assertDoesNotThrow(() -> PwdUtils.checkStrength(pwd));
        }
    }

    @Test
    void initialPwdIsNotConstant() {
        assertNotEquals(PwdUtils.generateInitialPwd(), PwdUtils.generateInitialPwd(),
                "两次生成不得相同（强随机）");
    }

    // ==================== checkStrength ====================

    @Test
    void strengthAcceptsLetterDigitWithinLength() {
        assertDoesNotThrow(() -> PwdUtils.checkStrength("abc12345"));
        assertDoesNotThrow(() -> PwdUtils.checkStrength("A1".repeat(16)));
        assertDoesNotThrow(() -> PwdUtils.checkStrength("abc12345!@#"));
    }

    @Test
    void strengthRejectsBadLength() {
        assertThrows(JbkException.class, () -> PwdUtils.checkStrength("abc1234"));
        assertThrows(JbkException.class, () -> PwdUtils.checkStrength("a1" + "x".repeat(31)));
    }

    /**
     * BCrypt 只吃前 72 字节：32 个汉字 = 96 字节，超出部分被静默丢弃，
     * 前 24 字符相同的两个不同口令会互相登录成功。必须在入口拒绝而不是交给算法截断。
     */
    @Test
    void strengthRejectsOverlongUtf8() {
        String cjk32 = "密".repeat(31) + "1";
        assertEquals(32, cjk32.length(), "长度校验应通过，触发的是字节数校验");
        assertThrows(JbkException.class, () -> PwdUtils.checkStrength(cjk32));
        // 24 个汉字（72 字节）加 1 位数字会超 72 字节；23 个汉字 + 数字 = 70 字节，应通过
        assertDoesNotThrow(() -> PwdUtils.checkStrength("密".repeat(23) + "1"));
    }

    @Test
    void strengthRejectsMissingClass() {
        assertThrows(JbkException.class, () -> PwdUtils.checkStrength("abcdefgh"));
        assertThrows(JbkException.class, () -> PwdUtils.checkStrength("12345678"));
        assertThrows(JbkException.class, () -> PwdUtils.checkStrength("!@#$%^&*"));
        assertThrows(JbkException.class, () -> PwdUtils.checkStrength(""));
        assertThrows(JbkException.class, () -> PwdUtils.checkStrength(null));
    }
}

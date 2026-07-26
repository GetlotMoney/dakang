package com.jbk.serve.service.mini.recharge;

import com.jbk.tool.exception.JbkException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * 充值订单号派生（L2 契约 §4.2）。
 *
 * <pre>
 * canonical = decimalUserId + ":" + lowercaseCanonicalUuid
 * digest    = SHA-256(canonical)
 * ORDER_NO  = "RC" + digest 前 30 位大写十六进制      // 总长 32
 * </pre>
 *
 * <p>不含日期、进程序列、缓存状态等不稳定因素：同用户同 requestId 在跨日期、重启、
 * Redis 丢失后仍得到同一订单号；不同用户相同 requestId 不冲突。</p>
 */
public final class RechargeOrderNo {

    /** 规范 RFC 4122 UUID：小写、带连字符。 */
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    public static final String PREFIX = "RC";
    /** 摘要截取位数（十六进制字符数）。 */
    private static final int DIGEST_HEX_LEN = 30;

    private RechargeOrderNo() {
    }

    /** 校验 requestId 必须是规范小写带连字符 UUID；大写/无连字符/畸形一律拒绝。 */
    public static String requireCanonicalUuid(String requestId) {
        if (requestId == null || !CANONICAL_UUID.matcher(requestId).matches()) {
            throw new JbkException("requestId 必须是小写带连字符的 UUID");
        }
        return requestId;
    }

    /**
     * 派生订单号。
     *
     * @param userId    会话登录人（十进制）
     * @param requestId 已校验的规范 UUID
     */
    public static String derive(Long userId, String requestId) {
        if (userId == null || userId <= 0) {
            throw new JbkException("会话用户非法，无法创建订单");
        }
        requireCanonicalUuid(requestId);
        String canonical = userId + ":" + requestId;
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new JbkException("订单号派生失败");
        }
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return PREFIX + hex.substring(0, DIGEST_HEX_LEN).toUpperCase();
    }
}

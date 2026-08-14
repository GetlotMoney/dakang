package com.jbk.serve.service.mall.impl;

import com.jbk.tool.exception.JbkException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * 商城售后单号派生（E2E-09 S2）。与一期 RechargeOrderNo 同构，仅前缀不同。
 *
 * <pre>
 * canonical = decimalUserId + ":" + lowercaseCanonicalUuid
 * digest    = SHA-256(canonical)
 * ORDER_NO  = "MO" + digest 前 30 位大写十六进制      // 总长 32
 * </pre>
 *
 * <p>不含日期、进程序列或缓存状态：同用户同 requestId 在跨日期、重启、缓存丢失后
 * 仍得到同一订单号，不同用户用同一 requestId 也不会撞号。这让"创单幂等"在唯一键
 * 之外还有一层可推导的确定性——排障时能从 (userId, requestId) 直接算出订单号。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
public final class MallAfterSaleNo {

    /** 规范 RFC 4122 UUID：小写、带连字符。 */
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    public static final String PREFIX = "MA";

    private static final int DIGEST_HEX_LEN = 30;

    private MallAfterSaleNo() {
    }

    /** 校验 requestId 必须是规范小写带连字符 UUID；大写/无连字符/畸形一律拒绝。 */
    public static String requireCanonicalUuid(String requestId) {
        if (requestId == null || !CANONICAL_UUID.matcher(requestId).matches()) {
            throw new JbkException("requestId 必须是小写带连字符的 UUID");
        }
        return requestId;
    }

    /** 派生订单号。 */
    public static String derive(Long userId, String requestId) {
        if (userId == null || userId <= 0) {
            throw new JbkException("会话用户非法，无法创建售后单");
        }
        requireCanonicalUuid(requestId);
        String canonical = userId + ":" + requestId;
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        }
        catch (NoSuchAlgorithmException e) {
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

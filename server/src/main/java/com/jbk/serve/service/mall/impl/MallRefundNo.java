package com.jbk.serve.service.mall.impl;

import com.jbk.tool.exception.JbkException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 商城退款单号派生（E2E-09 S4）：MR + sha256(afterSaleNo) 前 30 位大写十六进制。
 *
 * <p>由售后单号确定性派生，不含日期与序列：同一张售后单无论重放多少次都得到同一个退款单号，
 * 于是「一售后一退款单」既有库层唯一键兜底，也有单号本身兜底。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
public final class MallRefundNo {

    public static final String PREFIX = "MR";

    private static final int DIGEST_HEX_LEN = 30;

    private MallRefundNo() {
    }

    public static String derive(String afterSaleNo) {
        if (afterSaleNo == null || afterSaleNo.isBlank()) {
            throw new JbkException("售后单号缺失，无法创建退款单");
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(afterSaleNo.getBytes(StandardCharsets.UTF_8));
        }
        catch (NoSuchAlgorithmException unreachable) {
            throw new JbkException("退款单号派生失败");
        }
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return PREFIX + hex.substring(0, DIGEST_HEX_LEN).toUpperCase();
    }
}

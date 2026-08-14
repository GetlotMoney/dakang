package com.jbk.serve.service.aftersale.refund;

import com.jbk.tool.exception.JbkException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 商户退款单号（out_refund_no）确定性派生（E2E-04 包B）。
 *
 * <pre>
 * canonical = afterSaleId
 * REFUND_NO = "RF" + sha256(canonical) 前30位大写   // 总长 32，对齐 varchar(32)
 * </pre>
 *
 * <p>以 afterSaleId 而非 orderId 为基准：「一个售后动作至多一张退款单」是 R0-7 与
 * {@code uk_refund_after_sale} 的粒度。不含日期/序列/缓存，重放恒同号、撞唯一键幂等（铁律②）；
 * 确定性还防双退——含随机成分时「写库成功但发送超时」的重试会用新号再发，
 * 支付机构按 out_refund_no 幂等，两个号即两笔退款。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public final class RefundNo {

    public static final String PREFIX = "RF";

    /** 摘要截取位数（十六进制字符数）：2 + 30 = 32，正好填满 REFUND_NO 列宽。 */
    private static final int DIGEST_HEX_LEN = 30;

    private RefundNo() {
    }

    /**
     * 由售后动作 ID 派生商户退款单号。
     *
     * @param afterSaleId {@code ws_after_sale_action.ID}
     */
    public static String derive(Long afterSaleId) {
        if (afterSaleId == null || afterSaleId <= 0) {
            throw new JbkException("售后动作ID非法，无法派生退款单号");
        }
        return PREFIX + digestHex(String.valueOf(afterSaleId));
    }

    private static String digestHex(String canonical) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new JbkException("退款单号派生失败");
        }
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.substring(0, DIGEST_HEX_LEN).toUpperCase();
    }
}

package com.jbk.serve.service.aftersale;

import com.jbk.tool.consts.aftersale.AfterSaleEnum.SourceType;
import com.jbk.tool.exception.JbkException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 售后号确定性派生（E2E-04 包A；不 import {@code RechargeOrderNo}/{@code DeliveryOrderNo}：
 * 售后域对配送/充值域保持零编译期依赖）。
 *
 * <pre>
 * canonical    = sourceType + ":" + sourceId          // 例：2:8801
 * AFTER_SALE_NO = "AS" + sha256(canonical) 前30位大写   // 总长 32，对齐 varchar(32)
 * </pre>
 *
 * <p>不含日期/序列/缓存，重放恒同号，撞唯一键收敛为幂等命中（铁律②）。
 * sourceType 是命名空间前缀：同一 orderId 在不同来源下是不同售后号，可合法并存。</p>
 */
public final class AfterSaleNo {

    public static final String PREFIX = "AS";
    /** 摘要截取位数（十六进制字符数）：2 + 30 = 32，正好填满 AFTER_SALE_NO 列宽。 */
    private static final int DIGEST_HEX_LEN = 30;

    private AfterSaleNo() {
    }

    /**
     * 由来源派生售后号。
     *
     * @param sourceType 售后来源(1370)，必须是已登记值——脏来源会派生出无归属的售后号
     * @param sourceId   来源主体 ID，语义随来源变化，见 {@link SourceType#sourceIdMeaning()}
     */
    public static String derive(Integer sourceType, Long sourceId) {
        // fail-closed：未登记来源直接拒绝，否则「同来源同号」的幂等基准不成立
        SourceType type = SourceType.getByValue(sourceType);
        if (sourceId == null || sourceId <= 0) {
            throw new JbkException("售后来源主体ID非法，无法派生售后号");
        }
        return PREFIX + digestHex(type.getValue() + ":" + sourceId);
    }

    private static String digestHex(String canonical) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new JbkException("售后号派生失败");
        }
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.substring(0, DIGEST_HEX_LEN).toUpperCase();
    }
}

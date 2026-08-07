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
 * <p><b>为什么以 afterSaleId 而不是 orderId 为基准</b>：一张订单在整个生命周期里可能有
 * 多个售后动作（取消、申诉补偿、核账），而「一个售后动作至多一张退款单」才是 R0-7 的口径，
 * 也正是 {@code uk_refund_after_sale} 所约束的粒度。以 orderId 派生会让同一订单的
 * 第二个售后动作撞上第一个的退款单号，表现为「退款单号已存在」而运营看不懂为什么。</p>
 *
 * <p>不含日期、不含序列、不依赖缓存：跨零点、重启、重放恒定同号。同一售后动作重放
 * 必然撞 {@code uk_refund_no}（也必然撞 {@code uk_refund_after_sale}），
 * 由数据库唯一键收敛为幂等命中（铁律②：幂等靠键，不靠应用层查重）。</p>
 *
 * <p>确定性还有第二重意义：退款单号是发给支付机构的 out_refund_no。若它含随机成分，
 * 一次「本地写库成功但请求发送超时」的重试就会用<b>新号</b>再发一次，
 * 而支付机构那边按 out_refund_no 幂等——两个号即两笔退款，用户收到双份钱。</p>
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

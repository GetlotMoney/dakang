package com.jbk.serve.service.delivery;

import com.jbk.serve.service.mini.recharge.RechargeOrderNo;
import com.jbk.tool.exception.JbkException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 配送订单号/任务号确定性派生（E2E-03 规则3/4；派生思想与 {@link RechargeOrderNo} 同源）。
 *
 * <pre>
 * 用户单：ORDER_NO = "WD" + sha256(userId + ":" + requestId) 前30位大写      // 总长32
 * 补货单：ORDER_NO = "WD" + sha256(userId + ":AR:" + ruleId + ":" + period) 前30位大写
 * 任务号：TASK_NO  = "DT" + sha256(orderNo) 前30位大写                      // 一单一任务的派生投影
 * </pre>
 *
 * <p>不含日期、不含序列、不依赖缓存：跨零点/重启/重放恒定同号，幂等由
 * uk_order_no / uk_dtask_task_no / uk_dtask_order 在数据库层收敛。
 * 补货单 canonical 串带 ":AR:" 命名空间，与 requestId（UUID，不含冒号）不可能相撞。</p>
 */
public final class DeliveryOrderNo {

    public static final String ORDER_PREFIX = "WD";
    public static final String TASK_PREFIX = "DT";
    private static final int DIGEST_HEX_LEN = 30;

    private DeliveryOrderNo() {
    }

    /** 用户直接下单：同用户同 requestId 恒定同号（重复创单不重复扣款的第一道键）。 */
    public static String derive(Long userId, String requestId) {
        requireUserId(userId);
        RechargeOrderNo.requireCanonicalUuid(requestId);
        return ORDER_PREFIX + digestHex(userId + ":" + requestId);
    }

    /** 自动补货第 n 期：幂等键含规则与期序（E2E-03 规则19「幂等键含规则周期」）。 */
    public static String deriveAutoRefill(Long userId, Long ruleId, long periodIndex) {
        requireUserId(userId);
        if (ruleId == null || ruleId <= 0 || periodIndex <= 0) {
            throw new JbkException("自动补货期序参数非法");
        }
        return ORDER_PREFIX + digestHex(userId + ":AR:" + ruleId + ":" + periodIndex);
    }

    /**
     * 补送子订单号（E2E-04 包C）：幂等键 {@code RESEND:APPEAL:<appealId>}。
     *
     * <p>与 {@link #deriveAutoRefill} 同一条思路——凡是「由系统而非用户请求触发」的建单，
     * 幂等键都必须由业务主体确定性构成，而不是走 {@link #derive} 那条要求 UUID requestId 的路：
     * 那条路上的随机性来自用户端，系统触发时没有这样的随机源，硬造一个就等于放弃幂等。</p>
     *
     * <p>同一条申诉重复生成补送必然派生出同一个订单号，撞 {@code uk_order_no} —— 这是
     * 三道防重复补送闸里的第一道（另两道是 RESULT_ORDER_ID 的 CAS 与新订单上的 uk_dtask_order）。</p>
     */
    public static String deriveResend(Long userId, Long appealId) {
        requireUserId(userId);
        if (appealId == null || appealId <= 0) {
            throw new JbkException("补送申诉ID非法，无法派生补送单号");
        }
        return ORDER_PREFIX + digestHex(userId + ":RESEND:APPEAL:" + appealId);
    }

    /** 任务号由订单号派生：订单唯一 ⇒ 任务号唯一；无独立随机性可供伪造。 */
    public static String deriveTaskNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new JbkException("订单号为空，无法派生任务号");
        }
        return TASK_PREFIX + digestHex(orderNo);
    }

    private static void requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new JbkException("会话用户非法，无法创建配送订单");
        }
    }

    private static String digestHex(String canonical) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new JbkException("配送单号派生失败");
        }
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.substring(0, DIGEST_HEX_LEN).toUpperCase();
    }
}

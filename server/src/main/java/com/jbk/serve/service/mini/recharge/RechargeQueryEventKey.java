package com.jbk.serve.service.mini.recharge;

import com.jbk.tool.exception.JbkException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * 主动查单事实键派生（L2 契约 v2 §5.3，第 335 行）。
 *
 * <pre>
 * canonical = PAY_SOURCE|ORDER_NO|TRADE_STATE|TRANSACTION_ID-or-empty
 *             |PAY_SUCCESS_TIME-or-empty|PAY_AMOUNT-or-empty|CURRENCY-or-empty
 * PROVIDER_EVENT_KEY = "Q:" + SHA-256(canonical) 的小写十六进制      // 总长 66，≤ varchar(100)
 * </pre>
 *
 * <p>键<b>由事实内容派生</b>而非由订单号派生，这一点是契约的要害：同一事实反复查单稳定命中同一条
 * 记录（不会为每次轮询堆一条新事实），而同一订单从 NOTPAY 演进到 SUCCESS 或 CLOSED 时规范串不同、
 * 键必然不同——首个查询结果因此不可能永久占位，把后来的真实成功挡在唯一键外面。</p>
 *
 * <p><b>字符集必须先行校验</b>：分隔符 {@code |} 一旦能进入任一字段值域，
 * {@code A|B} 与 {@code A} + {@code B} 就会算出同一个规范串，两条语义不同的支付事实合并成一条键，
 * 后到的那条会被当成"重复查询"静默吞掉。所以下面每个字段都用白名单正则约束，
 * 不做"遇到非法字符就替换/截断"的补救——补救等于默许伪造者构造碰撞。</p>
 *
 * <p>命名空间隔离（§335）：本类只产出 {@code Q:} 前缀。NOTIFY 用支付方通知 id、
 * PAY_SIM 用规范 UUID，三者不得互相复用。</p>
 */
public final class RechargeQueryEventKey {

    /** 查单事实键前缀，与 NOTIFY / PAY_SIM 命名空间互斥。 */
    public static final String PREFIX = "Q:";

    private static final String SEPARATOR = "|";

    /** 商户订单号：本项目为 {@code RC} + 30 位大写十六进制，统一按大小写字母数字白名单约束。 */
    private static final Pattern ORDER_NO = Pattern.compile("^[0-9A-Za-z]{1,32}$");
    /** 规范化支付事实状态：大写字母与下划线。 */
    private static final Pattern TRADE_STATE = Pattern.compile("^[A-Z_]{1,32}$");
    /** 支付方交易号：微信为字母数字，Pay-Sim 用 SIM 命名空间，允许连字符/下划线。 */
    private static final Pattern TRANSACTION_ID = Pattern.compile("^[0-9A-Za-z_-]{1,64}$");
    /** 成功时间统一为 Asia/Shanghai 的 yyyyMMddHHmmss。 */
    private static final Pattern TIME14 = Pattern.compile("^[0-9]{14}$");
    /** 币种：ISO 4217 三位大写。 */
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private RechargeQueryEventKey() {
    }

    /**
     * 派生查单事实键。
     *
     * <p>非成功事实（NOTPAY/CLOSED）的外部字段必须原样保持 {@code null}——
     * 契约禁止用内部订单金额补造外部事实，这里传 null 即在规范串中留空串。</p>
     *
     * @param paySource      服务端适配器常量：1 微信 / 2 Pay-Sim
     * @param orderNo        已验证的外部商户订单号
     * @param tradeState     规范化支付事实状态
     * @param transactionId  支付方交易号，未返回传 {@code null}
     * @param paySuccessTime 支付方成功时间，未返回传 {@code null}
     * @param payAmount      支付方返回金额（分），未返回传 {@code null}
     * @param currency       支付方返回币种，未返回传 {@code null}
     */
    public static String derive(Integer paySource, String orderNo, String tradeState,
                                String transactionId, String paySuccessTime,
                                Long payAmount, String currency) {
        String canonical = canonical(paySource, orderNo, tradeState,
                transactionId, paySuccessTime, payAmount, currency);
        return PREFIX + sha256Hex(canonical);
    }

    /** 规范串（校验后拼接）。单独暴露只为让测试可以逐字段复算，不供业务旁路使用。 */
    public static String canonical(Integer paySource, String orderNo, String tradeState,
                                   String transactionId, String paySuccessTime,
                                   Long payAmount, String currency) {
        if (paySource == null || paySource <= 0) {
            throw new JbkException("查单事实键：支付来源非法");
        }
        return String.valueOf(paySource)
                + SEPARATOR + require(orderNo, ORDER_NO, "订单号")
                + SEPARATOR + require(tradeState, TRADE_STATE, "支付事实状态")
                + SEPARATOR + optional(transactionId, TRANSACTION_ID, "交易号")
                + SEPARATOR + optional(paySuccessTime, TIME14, "支付成功时间")
                + SEPARATOR + optionalAmount(payAmount)
                + SEPARATOR + optional(currency, CURRENCY, "币种");
    }

    /** 必填字段：null / 空串 / 不匹配白名单一律拒绝，绝不"尽力清洗"。 */
    private static String require(String value, Pattern pattern, String label) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new JbkException("查单事实键：" + label + "字符集非法");
        }
        return value;
    }

    /**
     * 可选字段：只有 {@code null} 表示"支付方未返回"，落成空串。
     *
     * <p>空串与 null 不做等同处理——若允许调用方传空串代表未返回，
     * 一个被清洗成空的非法值就会与真正的"未返回"撞出同一个键。</p>
     */
    private static String optional(String value, Pattern pattern, String label) {
        if (value == null) {
            return "";
        }
        return require(value, pattern, label);
    }

    /** 金额按十进制渲染；负数不是合法支付事实，直接拒绝而不是渲染成带负号的值。 */
    private static String optionalAmount(Long payAmount) {
        if (payAmount == null) {
            return "";
        }
        if (payAmount < 0) {
            throw new JbkException("查单事实键：金额非法");
        }
        return String.valueOf(payAmount);
    }

    private static String sha256Hex(String canonical) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new JbkException("查单事实键派生失败");
        }
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}

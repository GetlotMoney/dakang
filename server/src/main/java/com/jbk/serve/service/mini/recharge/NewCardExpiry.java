package com.jbk.serve.service.mini.recharge;

import com.jbk.tool.exception.JbkException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 首次购卡新卡有效期（L2-A，决策 A1 已冻结）。
 *
 * <pre>
 * 有限期套餐：newExpireTime = paySuccessTime + expireDays
 * 永久套餐（expireDays = NULL）：返回 null（EXPIRE_TIME 保持 SQL NULL = 永久）
 * </pre>
 *
 * <p><b>为什么不复用/改造 {@link RechargeExpiry}（决策 A1 明令）：</b>
 * {@code RechargeExpiry.extend(currentExpireTime, ...)} 是 L2-B 充值续期公式
 * {@code max(currentExpireTime, paySuccessTime) + expireDays}，其中 {@code currentExpireTime=NULL}
 * 在 L2-B 语义里表示「既有永久卡」——若为了发卡而让 NULL 退化为从 paySuccessTime 起算，
 * 同一个入口就会把一张既有永久卡的充值续成有限卡，用户的永久权益被静默没收。
 * 两个类各管各的：L2-B 续期永远走 {@code RechargeExpiry}，L2-A 发卡永远走本类，互不引用对方公式。</p>
 *
 * <p><b>起算基准必须是权威 {@code paySuccessTime}，不得用创单时间或处理时间</b>（决策 A1）：
 * 用创单时间会把用户没付款的等待时间从有效期里吃掉；用异步 Worker 的处理时间则会在延迟重放时
 * 凭空多送有效期，且同一笔支付重放结果不稳定。本方法签名刻意不接收处理时间参数，编译期即杜绝误用。</p>
 *
 * <p>业务时区固定 Asia/Shanghai；时间一律 {@code yyyyMMddHHmmss} 字符串，
 * 校验严格程度与 {@link RechargeExpiry} 完全一致（长度 14 + 可解析，否则拒绝）。</p>
 */
public final class NewCardExpiry {

    private static final DateTimeFormatter FMT = com.jbk.tool.utils.DateUtils.COMPACT_FORMATTER;

    private NewCardExpiry() {
    }

    /**
     * 计算新卡有效期。
     *
     * @param paySuccessTime 权威支付成功时间（payment.PAY_SUCCESS_TIME）
     * @param expireDays     套餐快照有效期天数；{@code null} = 永久套餐
     * @return 有限期套餐返回 {@code paySuccessTime + expireDays}；永久套餐返回 {@code null}
     */
    public static String compute(String paySuccessTime, Integer expireDays) {
        // 永久套餐同样先校验时间格式：一条格式非法的支付成功时间意味着上游事实已被污染，
        // 即使本次用不到它也必须 fail-closed，而不是放行到后续步骤才炸
        LocalDateTime paid = parse(paySuccessTime, "支付成功时间");
        if (expireDays == null) {
            // 永久套餐：EXPIRE_TIME 保持 SQL NULL。这里绝不能返回某个「很远的时间」凑数——
            // 永久的唯一持久语义就是 NULL，任何具体值都会让这张卡在未来某天被误判过期。
            return null;
        }
        if (expireDays <= 0 || expireDays > RechargeLimits.EXPIRE_DAYS_MAX) {
            throw new JbkException("套餐有效期天数非法，拒绝发卡");
        }
        return paid.plusDays(expireDays).format(FMT);
    }

    private static LocalDateTime parse(String value, String label) {
        if (value == null || value.length() != 14) {
            throw new JbkException(label + "格式非法，拒绝发卡");
        }
        try {
            return LocalDateTime.parse(value, FMT);
        } catch (DateTimeParseException e) {
            throw new JbkException(label + "格式非法，拒绝发卡");
        }
    }
}

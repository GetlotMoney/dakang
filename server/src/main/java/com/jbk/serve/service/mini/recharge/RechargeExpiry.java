package com.jbk.serve.service.mini.recharge;

import com.jbk.tool.exception.JbkException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 有限卡充值续期（L2 契约 v2 §2.2 / §6.4 步骤 5，决策 L2-D4 已冻结）。
 *
 * <p>冻结公式：{@code newExpireTime = max(currentExpireTime, paySuccessTime) + expireDays}。</p>
 *
 * <p>取 max 而不是简单地在当前有效期上加天数，是为了同时覆盖两种情形：卡还没过期时按剩余有效期
 * 往后叠加（用户不吃亏），卡已经自然过期时从<b>支付成功时刻</b>起算（不把权益续到一段已经过去的时间里）。
 * 基准必须是权威 {@code paySuccessTime}，<b>不能用处理时间代替</b>——异步 Worker 的处理时刻可能远晚于
 * 支付时刻，用它起算会凭空多送用户一段有效期，且同一笔支付重放时结果不稳定。</p>
 *
 * <p>业务时区固定 Asia/Shanghai；时间一律 {@code yyyyMMddHHmmss} 字符串，与全仓口径一致。</p>
 */
public final class RechargeExpiry {

    private static final DateTimeFormatter FMT = com.jbk.tool.utils.DateUtils.COMPACT_FORMATTER;
    private RechargeExpiry() {
    }

    /**
     * 计算有限卡续期后的有效期。
     *
     * @param currentExpireTime 事务 B <b>锁卡后</b>读取的当前聚合有效期（不可为空——永久卡不该走本方法）
     * @param paySuccessTime    权威支付成功时间（来自 payment.PAY_SUCCESS_TIME）
     * @param expireDays        套餐快照中的有效期天数，正整数
     */
    public static String extend(String currentExpireTime, String paySuccessTime, Integer expireDays) {
        LocalDateTime current = parse(currentExpireTime, "水卡当前有效期");
        LocalDateTime paid = parse(paySuccessTime, "支付成功时间");
        if (expireDays == null || expireDays <= 0 || expireDays > RechargeLimits.EXPIRE_DAYS_MAX) {
            throw new JbkException("套餐有效期天数非法，拒绝入账");
        }
        LocalDateTime base = current.isAfter(paid) ? current : paid;
        return base.plusDays(expireDays).format(FMT);
    }

    /**
     * 算得的新有效期是否已经不晚于处理时刻。
     *
     * <p>契约 §6.4 步骤 5：这种情况是<b>不可恢复错误</b>——绝不能写一段已经过期的权益，
     * 也不能顺手把卡恢复成正常态，必须让订单进入 6 转人工。用户已经付了钱，
     * 静默发放一份当场作废的权益比不发放更糟。</p>
     */
    public static boolean isAlreadyExpired(String newExpireTime, String processingTime) {
        LocalDateTime expire = parse(newExpireTime, "续期后有效期");
        LocalDateTime now = parse(processingTime, "处理时间");
        return !expire.isAfter(now);
    }

    /** 自然过期判定：有效期非空且不晚于处理时刻。仅此一种 CARD_STATUS=3 允许继续入账。 */
    public static boolean naturallyExpired(String currentExpireTime, String processingTime) {
        if (currentExpireTime == null || currentExpireTime.isEmpty()) {
            return false;
        }
        return !parse(currentExpireTime, "水卡当前有效期").isAfter(parse(processingTime, "处理时间"));
    }

    private static LocalDateTime parse(String value, String label) {
        if (value == null || value.length() != 14) {
            throw new JbkException(label + "格式非法，拒绝入账");
        }
        try {
            return LocalDateTime.parse(value, FMT);
        } catch (DateTimeParseException e) {
            throw new JbkException(label + "格式非法，拒绝入账");
        }
    }
}

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
 * <p>取 max：未过期按剩余有效期叠加，已过期从支付成功时刻起算。基准必须是权威
 * paySuccessTime、不能用处理时间——Worker 延迟会凭空多送有效期且重放结果不稳定。
 * 时间一律 Asia/Shanghai 的 yyyyMMddHHmmss。</p>
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
     * 新有效期是否已不晚于处理时刻。契约 §6.4 步骤 5：不可恢复错误——
     * 绝不写一段已过期的权益，订单进 6 转人工。
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

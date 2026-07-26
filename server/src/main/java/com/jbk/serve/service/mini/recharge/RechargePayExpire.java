package com.jbk.serve.service.mini.recharge;

import com.jbk.tool.exception.JbkException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 不可变付款截止时间的冻结算法（L2 契约 §2.2）。
 *
 * <pre>
 * 永久卡：PAY_EXPIRE_TIME = createTime + 30min
 * 有限卡：PAY_EXPIRE_TIME = min(createTime + 30min, expireTimeAtCreate)
 * </pre>
 *
 * <p>有限卡在创单时剩余有效期不足 1 分钟则**拒绝创建**——不得收一笔实际上无法在窗口内完成的款项。</p>
 *
 * <p>该值创建后不可修改：L2-T 与 L2-WX 共用同一字段；后续其他充值、卡有效期延长或套餐变化
 * 均不得追溯改写旧待支付订单的付款资格。</p>
 */
public final class RechargePayExpire {

    /** 业务时区 Asia/Shanghai 下的紧凑时间格式。 */
    public static final DateTimeFormatter FMT = com.jbk.tool.utils.DateUtils.COMPACT_FORMATTER;
    /** 第一版支付窗口固定 30 分钟。 */
    public static final int PAY_WINDOW_MINUTES = 30;
    /** 有限卡创单时剩余有效期下限。 */
    public static final int MIN_REMAINING_MINUTES = 1;

    private RechargePayExpire() {
    }

    public static LocalDateTime parse(String time14, String label) {
        if (time14 == null || time14.length() != 14) {
            throw new JbkException(label + "时间格式非法");
        }
        try {
            return LocalDateTime.parse(time14, FMT);
        } catch (DateTimeParseException e) {
            throw new JbkException(label + "时间格式非法");
        }
    }

    /**
     * 计算不可变付款截止时间。
     *
     * @param createTime         订单创建时间（服务端同一时刻）
     * @param expireTimeAtCreate 创建时卡有效期；{@code null}/空 表示永久卡
     * @return PAY_EXPIRE_TIME（yyyyMMddHHmmss）
     */
    public static String compute(String createTime, String expireTimeAtCreate) {
        LocalDateTime created = parse(createTime, "订单创建");
        LocalDateTime windowEnd = created.plusMinutes(PAY_WINDOW_MINUTES);
        if (expireTimeAtCreate == null || expireTimeAtCreate.isEmpty()) {
            return windowEnd.format(FMT);
        }
        LocalDateTime cardExpire = parse(expireTimeAtCreate, "水卡到期");
        // 有限卡：剩余不足 1 分钟直接拒绝，避免收下无法在窗口内完成的款项。
        if (Duration.between(created, cardExpire).toMinutes() < MIN_REMAINING_MINUTES) {
            throw new JbkException("水卡剩余有效期不足 1 分钟，无法创建充值订单");
        }
        LocalDateTime effective = cardExpire.isBefore(windowEnd) ? cardExpire : windowEnd;
        return effective.format(FMT);
    }

    /** 付款时点资格：只按权威 paySuccessTime <= PAY_EXPIRE_TIME 判断。 */
    public static boolean paidInTime(String paySuccessTime, String payExpireTime) {
        if (paySuccessTime == null || payExpireTime == null) {
            return false;
        }
        LocalDateTime paid = parse(paySuccessTime, "支付成功");
        LocalDateTime expire = parse(payExpireTime, "付款截止");
        return !paid.isAfter(expire);
    }
}

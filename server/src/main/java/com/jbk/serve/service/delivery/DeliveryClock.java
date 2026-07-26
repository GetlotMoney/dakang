package com.jbk.serve.service.delivery;

import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 配送履约统一时间口径（E2E-03 规则13 与 Mock 契约的单调时间轴）。
 *
 * <p>动作时间=服务端时钟，但必须不早于各前置节点：创建≤接单≤离站≤送达≤签收===完成。
 * 服务器时钟回拨/同秒并发时，用 floor 取「当前时间与全部地板时间的最大值」，
 * 保证时间轴单调；yyyyMMddHHmmss 字符串比较与时间序同构。</p>
 */
public final class DeliveryClock {

    private DeliveryClock() {
    }

    /** 动作时间 = max(now, floors...)；null 地板跳过。 */
    public static String floor(String now, String... floors) {
        String result = requireTime(now, "当前时间");
        if (floors != null) {
            for (String f : floors) {
                if (f != null && !f.isBlank() && f.compareTo(result) > 0) {
                    result = f;
                }
            }
        }
        return result;
    }

    /** time + hours 小时（申诉截止=签收+24h 的唯一计算口径）。 */
    public static String plusHours(String time, long hours) {
        return LocalDateTime.parse(requireTime(time, "时间"), DateUtils.COMPACT_FORMATTER)
                .plusHours(hours).format(DateUtils.COMPACT_FORMATTER);
    }

    /** 自动补货期序：floor(锚点到 now 的整天数 / 周期天数)；now 早于锚点=0。 */
    public static long periodIndex(String anchorTime, String now, int intervalDays) {
        if (intervalDays <= 0) {
            throw new JbkException("补货周期天数非法");
        }
        LocalDateTime anchor = LocalDateTime.parse(requireTime(anchorTime, "周期锚点"), DateUtils.COMPACT_FORMATTER);
        LocalDateTime current = LocalDateTime.parse(requireTime(now, "当前时间"), DateUtils.COMPACT_FORMATTER);
        if (!current.isAfter(anchor)) {
            return 0L;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(anchor, current);
        return days / intervalDays;
    }

    public static String requireTime(String time, String label) {
        if (time == null || time.length() != 14) {
            throw new JbkException(label + "格式非法");
        }
        try {
            LocalDateTime.parse(time, DateUtils.COMPACT_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new JbkException(label + "格式非法");
        }
        return time;
    }
}

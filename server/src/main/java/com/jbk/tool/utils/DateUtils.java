package com.jbk.tool.utils;

import com.jbk.tool.exception.JbkException;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/** 日期工具类。 */
public class DateUtils {
    public final static String DATE_TIME_1 = "yyyyMMddHHmmss";
    /** yyyyMMddHHmmss 的共享格式器（线程安全），全仓单一出处，防口径分裂。 */
    public static final java.time.format.DateTimeFormatter COMPACT_FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern(DATE_TIME_1);
    public final static String DATE_TIME_2 = "yyyy年MM月dd日 HH:mm";
    public final static String DATE_TIME_3 = "yyyy-MM-dd";
    public final static String DATE_TIME_4 = "yyyy-MM-dd HH:mm:ss";
    public final static String DATE_TIME_5 = "yyyyMMdd";
    public final static String DATE_TIME_6 = "HHmmss";
    public final static String DATE_TIME_7 = "HH:mm";
    public final static String DATE_TIME_8 = "yyyy";
    public final static String DATE_TIME_9 = "MM";
    public final static String DATE_TIME_10 = "dd";
    public final static String DATE_TIME_11 = "yyyyMM";
    public final static String DATE_TIME_12 = "HH";
    public final static String DATE_TIME_DEFAULT_1 = "01000000";
    public final static String DATE_TIME_DEFAULT_2 = "235959";

    public final static String DATE_TIME_DEFAULT_3 = "000000";

    /**
     * 日期格式化 日期格式为：yyyy-MM-dd
     *
     * @param date 日期
     * @return 返回yyyy-MM-dd格式日期
     */
    public static String format(Date date) {
        return format(date, DATE_TIME_3);
    }

    /**
     * 日期格式化 日期格式为：yyyyMMddHHmmss
     *
     * @return 返回yyyyMMddHHmmss格式日期
     */
    public static String time() {
        return DateUtil.format(new Date(), DATE_TIME_1);
    }

    /**
     * 日期格式化 日期格式为：yyyyMMddHHmmss
     *
     * @return 返回yyyyMMddHHmmss格式日期
     */
    public static String time(String format) {
        return DateUtil.format(new Date(), format);
    }

    /**
     * 日期格式化 日期格式为：yyyyMMddHHmmss
     *
     * @param date 日期
     * @return 返回yyyyMMddHHmmss格式日期
     */
    public static String timeTransition(Date date) {
        return DateUtil.format(date, DATE_TIME_1);
    }


    /**
     * 日期格式化 日期格式为：yyyy-MM-dd
     *
     * @param date    日期
     * @param pattern 格式，如：DateUtils.DATE_TIME_PATTERN
     * @return 返回yyyy-MM-dd格式日期
     */
    public static String format(Date date, String pattern) {
        if (date != null) {
            SimpleDateFormat df = new SimpleDateFormat(pattern);
            return df.format(date);
        }
        return null;
    }


    /**
     * 日期格式化 日期格式为：yyyy-MM-dd
     *
     * @param date    日期 yyyyMMddHHmmss
     * @param pattern 格式，如：DateUtils.DATE_TIME_PATTERN
     * @return 返回yyyy-MM-dd格式日期
     */
    public static String format(String date, String pattern) {
        if (StrUtil.isNotEmpty(date)) {
            SimpleDateFormat df = new SimpleDateFormat(pattern);
            cn.hutool.core.date.DateTime dateTime = DateUtil.parse(date);
            return df.format(dateTime);
        }
        return null;
    }

    /**
     * 日期解析
     *
     * @param date    日期
     * @param pattern 格式，如：DateUtils.DATE_TIME_PATTERN
     * @return 返回Date
     */
    public static Date parse(String date, String pattern) {
        try {
            return new SimpleDateFormat(pattern).parse(date);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 字符串转换成日期
     *
     * @param strDate 日期字符串
     * @param pattern 日期的格式，如：DateUtils.DATE_TIME_PATTERN
     */
    public static Date stringToDate(String strDate, String pattern) {
        if (StringUtils.isBlank(strDate)) {
            return null;
        }

        DateTimeFormatter fmt = DateTimeFormat.forPattern(pattern);
        return fmt.parseLocalDateTime(strDate).toDate();
    }

    /**
     * 根据周数，获取开始日期、结束日期
     *
     * @param week 周期  0本周，-1上周，-2上上周，1下周，2下下周
     * @return 返回date[0]开始日期、date[1]结束日期
     */
    public static Date[] getWeekStartAndEnd(int week) {
        DateTime dateTime = new DateTime();
        LocalDate date = new LocalDate(dateTime.plusWeeks(week));

        date = date.dayOfWeek().withMinimumValue();
        Date beginDate = date.toDate();
        Date endDate = date.plusDays(6).toDate();
        return new Date[]{beginDate, endDate};
    }

    /**
     * 对日期的【秒】进行加/减
     *
     * @param date    日期
     * @param seconds 秒数，负数为减
     * @return 加/减几秒后的日期
     */
    public static Date addDateSeconds(Date date, int seconds) {
        DateTime dateTime = new DateTime(date);
        return dateTime.plusSeconds(seconds).toDate();
    }

    /**
     * 对日期的【分钟】进行加/减
     *
     * @param date    日期
     * @param minutes 分钟数，负数为减
     * @return 加/减几分钟后的日期
     */
    public static Date addDateMinutes(Date date, int minutes) {
        DateTime dateTime = new DateTime(date);
        return dateTime.plusMinutes(minutes).toDate();
    }

    /**
     * 对日期的【小时】进行加/减
     *
     * @param date  日期
     * @param hours 小时数，负数为减
     * @return 加/减几小时后的日期
     */
    public static Date addDateHours(Date date, int hours) {
        DateTime dateTime = new DateTime(date);
        return dateTime.plusHours(hours).toDate();
    }

    /**
     * 对日期的【天】进行加/减
     *
     * @param date 日期
     * @param days 天数，负数为减
     * @return 加/减几天后的日期
     */
    public static Date addDateDays(Date date, int days) {
        DateTime dateTime = new DateTime(date);
        return dateTime.plusDays(days).toDate();
    }

    /**
     * 对日期的【周】进行加/减
     *
     * @param date  日期
     * @param weeks 周数，负数为减
     * @return 加/减几周后的日期
     */
    public static Date addDateWeeks(Date date, int weeks) {
        DateTime dateTime = new DateTime(date);
        return dateTime.plusWeeks(weeks).toDate();
    }

    /**
     * 对日期的【月】进行加/减
     *
     * @param date   日期
     * @param months 月数，负数为减
     * @return 加/减几月后的日期
     */
    public static Date addDateMonths(Date date, int months) {
        DateTime dateTime = new DateTime(date);
        return dateTime.plusMonths(months).toDate();
    }

    /**
     * 对日期的【年】进行加/减
     *
     * @param date  日期
     * @param years 年数，负数为减
     * @return 加/减几年后的日期
     */
    public static Date addDateYears(Date date, int years) {
        DateTime dateTime = new DateTime(date);
        return dateTime.plusYears(years).toDate();
    }

    /**
     * dayofweek
     */
    public static int dayOfWeek(cn.hutool.core.date.DateTime bookingTime) {
        int dayOfWeek = bookingTime.dayOfWeek();
        return dayOfWeek - 1 == 0 ? 7 : dayOfWeek - 1;
    }



    /**
     * yyyyMMddHHmmss 时间串加秒——全仓单一出处（重试排期口径统一）。
     * 非法时间串抛 {@link JbkException} 而非逸出 DateTimeParseException：调用点在资金重试路径，需要可读原因落 LAST_ERROR。
     *
     * @param time    14 位业务时间串
     * @param seconds 增加的秒数，可为负
     */
    public static String plusSeconds(String time, long seconds) {
        try {
            return java.time.LocalDateTime.parse(time, COMPACT_FORMATTER)
                    .plusSeconds(seconds).format(COMPACT_FORMATTER);
        } catch (RuntimeException e) {
            throw new JbkException("业务时间格式非法，无法安排重试");
        }
    }

    /** 14 位数字形态；只做形状判断，真实性由 {@link #isCanonicalBusinessTime} 负责。 */
    private static final java.util.regex.Pattern COMPACT_SHAPE =
            java.util.regex.Pattern.compile("^\\d{14}$");

    /**
     * 严格业务时间判据：14 位数字 + 能解析 + 格式化回来逐字相同，三条缺一不可。
     * 陷阱：SMART 解析会把 20260230 悄悄夹成 2026-02-28 不报错，必须往返比对拒绝。用于资金链外部时间的准入。
     */
    public static boolean isCanonicalBusinessTime(String time) {
        if (time == null || !COMPACT_SHAPE.matcher(time).matches()) {
            return false;
        }
        try {
            return time.equals(java.time.LocalDateTime.parse(time, COMPACT_FORMATTER)
                    .format(COMPACT_FORMATTER));
        }
        catch (RuntimeException invalid) {
            return false;
        }
    }
}

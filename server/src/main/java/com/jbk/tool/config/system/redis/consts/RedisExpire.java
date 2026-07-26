package com.jbk.tool.config.system.redis.consts;

/**
 * @ClassName RedisExpire
 * @Author xs
 * @Date 2024/6/7 11:52
 * @Version 1.0
 */
public class RedisExpire {
    /**
     * 2秒钟
     */
    public final static long SEC_TWO_EXPIRE = 2L;
    public final static int SEC_TWO_EXPIRE_ = 2;
    /**
     * 5秒钟
     */
    public final static long SEC_FIVE_EXPIRE = 5L;
    public final static int SEC_FIVE_EXPIRE_ = 5;

    /**
     * 30秒钟
     */
    public final static long HALF_ONE_EXPIRE = 30L;
    public final static int HALF_ONE_EXPIRE_ = 30;

    /**
     * 1分钟
     */
    public final static long ONE_EXPIRE = 60 * 1L;
    public final static int ONE_EXPIRE_ = 60 * 1;

    /**
     * 5分钟
     **/
    public final static long FIVE_EXPIRE = 60 * 5L;
    public final static int FIVE_EXPIRE_ = 60 * 5;

    /**
     * 10分钟
     **/
    public final static long TEN_EXPIRE = 60 * 10L;
    public final static int TEN_EXPIRE_ = 60 * 10;
    /**
     * 过期时长为0.5小时，单位：秒
     */
    public final static long HOUR_HALF_EXPIRE = 60 * 30 * 1L;
    public final static int HOUR_HALF_EXPIRE_ = 60 * 30 * 1;

    /**
     * 过期时长为1小时，单位：秒
     */
    public final static long HOUR_ONE_EXPIRE = 60 * 60 * 1L;
    public final static int HOUR_ONE_EXPIRE_ = 60 * 60 * 1;

    /**
     * 过期时长为2小时，单位：秒
     */
    public final static long HOUR_TWO_EXPIRE = 60 * 60 * 2L;
    public final static int HOUR_TWO_EXPIRE_ = 60 * 60 * 2;
    /**
     * 过期时长为6小时，单位：秒
     */
    public final static long HOUR_SIX_EXPIRE = 60 * 60 * 6L;
    public final static int HOUR_SIX_EXPIRE_ = 60 * 60 * 6;

    /**
     * 默认过期时长为24小时，单位：秒
     */
    public final static long ONE_DAY_EXPIRE = 60 * 60 * 24L;
    public final static int ONE_DAY_EXPIRE_ = 60 * 60 * 24;

    /**
     * 过期时长3天
     */
    public final static long THREE_DAY_EXPIRE = 3 * 60 * 60 * 24L;
    public final static int THREE_DAY_EXPIRE_ = 3 * 60 * 60 * 24;

    /**
     * 过期时长7天
     */
    public final static long WEEK_DAY_EXPIRE = 7 * 60 * 60 * 24L;
    public final static int WEEK_DAY_EXPIRE_ = 7 * 60 * 60 * 24;

    /**
     * 过期时长30天
     */
    public final static long MONTH_ONE_EXPIRE = 30 * 60 * 60 * 24L;
    public final static int MONTH_ONE_EXPIRE_ = 30 * 60 * 60 * 24;

    /**
     * 不设置过期时长
     */
    public final static long NOT_EXPIRE = -1L;
    public final static int NOT_EXPIRE_ = -1;


    /**
     * 自动刷新时间配置
     */
    public final static int REFRESH_ONE = 60 * 1;
    public final static int STOP_REFRESH_ONE = 60 * 1 * 4;

    public final static int REFRESH_FIVE = 60 * 5;
    public final static int STOP_REFRESH_FIVE = 60 * 5 * 4;

    public final static int REFRESH_HOUR_HALF = 60 * 30;
    public final static int STOP_REFRESH_HOUR_HALF = 60 * 30 * 4;

    public final static int REFRESH_HOUR_ONE = 60 * 60;
    public final static int STOP_REFRESH_HOUR_ONE = 60 * 60 * 4;

    public final static int REFRESH_HOUR_SIX = 60 * 60 * 6;
    public final static int STOP_REFRESH_HOUR_ONEREFRESH_HOUR_SIX = 60 * 60 * 6 * 3;

    public final static int REFRESH_DAY_ONE = 60 * 60 * 24;
    public final static int STOP_REFRESH_DAY_ONE = 60 * 60 * 24 * 3;
}



package com.jbk.tool.utils;

import cn.hutool.core.util.StrUtil;

import java.util.regex.Pattern;

/**
 * 敏感信息脱敏的唯一实现：日志出口（RequestAspect/SysLogAspect）与业务快照（审计导出筛选条件）共用，必须同口径。
 */
public final class MaskUtils {

    /**
     * 中国大陆手机号：非数字边界 + {@code 1[3-9]} 开头的 11 位。
     * 边界约束不可去掉，否则 14 位业务时间戳与订单号数字段会被误伤成手机号。
     */
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");

    private MaskUtils() {
    }

    /** 把文本中出现的手机号中间四位替换为 {@code ****}；嵌在句中也命中，无命中原样返回。 */
    public static String maskPhoneLike(String text) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        return PHONE_PATTERN.matcher(text).replaceAll("$1****$2");
    }
}

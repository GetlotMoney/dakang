package com.jbk.tool.utils;

import cn.hutool.core.util.StrUtil;

import java.util.regex.Pattern;

/**
 * 敏感信息脱敏的唯一实现。
 *
 * <p>被两类调用方共用，二者必须同口径：</p>
 * <ul>
 *   <li>日志出口（{@code RequestAspect}/{@code SysLogAspect}）——请求体落库前的值级兜底；</li>
 *   <li>业务快照（如审计导出的筛选条件）——写库前的脱敏。</li>
 * </ul>
 *
 * <p>抽成独立工具而不是留在日志切面里，是因为"什么算敏感、怎么遮"是一条业务规则，
 * 分散成两份实现迟早会漂移——而漂移的表现是"日志里遮了、业务表里没遮"这种最难发现的形态。</p>
 */
public final class MaskUtils {

    /**
     * 中国大陆手机号：非数字边界 + {@code 1[3-9]} 开头的 11 位。
     *
     * <p>前后的边界约束不可去掉——否则 14 位业务时间戳（{@code yyyyMMddHHmmss}）
     * 与订单号里的连续数字段会被误伤成"手机号"。</p>
     */
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");

    private MaskUtils() {
    }

    /**
     * 把文本中出现的手机号中间四位替换为 {@code ****}。
     *
     * <p>整串是手机号、或手机号嵌在一句话里（如筛选快照"操作人 13900001111"）都能命中；
     * 无命中时原样返回。</p>
     */
    public static String maskPhoneLike(String text) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        return PHONE_PATTERN.matcher(text).replaceAll("$1****$2");
    }
}

package com.jbk.tool.utils;

import cn.hutool.core.util.StrUtil;

/**
 * 手机号脱敏的唯一实现。
 *
 * <p>规则：恰 11 位 → 前 3 后 4 中间打星；任何其他长度整体屏蔽为空串——
 * 宁可不显示也绝不回落明文。此前小程序卡域与 PC 追溯各写一份，
 * 两份规则一旦走样，就有一处在界面上漏明文；脱敏这种事必须只有一个真相。</p>
 */
public final class PhoneMask {

    private PhoneMask() {
    }

    public static String mask(String phone) {
        if (StrUtil.length(phone) != 11) {
            return "";
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}

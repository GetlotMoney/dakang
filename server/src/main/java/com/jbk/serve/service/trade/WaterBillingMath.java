package com.jbk.serve.service.trade;

import com.jbk.tool.exception.JbkException;

/**
 * 取水计费唯一数学入口。
 *
 * <p>所有计量单位固定为毫升、分/升和分。调用方必须先通过本类完成值域与溢出校验，
 * 禁止各业务路径复制未经检查的乘除公式。</p>
 */
public final class WaterBillingMath {

    public static final long PLAN_ML_MAX = 99_000L;
    public static final int UNIT_PRICE_MAX_FEN_PER_LITER = 100_000;

    private WaterBillingMath() {
    }

    public static int requireUnitPrice(int unitPriceFenPerLiter) {
        if (unitPriceFenPerLiter < 0 || unitPriceFenPerLiter > UNIT_PRICE_MAX_FEN_PER_LITER) {
            throw new JbkException("取水单价超出允许范围");
        }
        return unitPriceFenPerLiter;
    }

    /**
     * 解析出水口配置单价（{@code ws_device_outlet.OUTLET_PRICE}，varchar 存的「分/升」）为整数。
     *
     * <p>唯一实现：此前扫码上下文、下单装配、结算三处各写一份 {@code Integer.parseInt}，
     * 宽严不一（有的容忍前后空白、有的不校验范围），最松的那一处就是错误计价的入口。
     * 只接受纯十进制整数文本，负数、小数、带符号与任何非数字一律拒绝，范围复用
     * {@link #requireUnitPrice}。前后空白先 trim 后再判——它是人工录入的常见噪声，
     * 而不是语义差异；读侧收紧成拒绝会让库里已有的带空白配置突然取不了水。
     * 内部空白（如 "2 0"）仍然拒绝。</p>
     *
     * @param outletPrice 出水口配置原文
     * @return 规范化后的分/升
     * @throws JbkException 文本非法或越界
     */
    public static int requireOutletPrice(String outletPrice) {
        String text = outletPrice == null ? "" : outletPrice.trim();
        if (!text.matches("^(?:0|[1-9]\\d*)$")) {
            throw new JbkException("出水口单价配置非法（" + outletPrice + "）");
        }
        long value;
        try {
            value = Long.parseLong(text);
        }
        catch (NumberFormatException overflow) {
            throw new JbkException("出水口单价配置超出取值范围（" + outletPrice + "）");
        }
        if (value > UNIT_PRICE_MAX_FEN_PER_LITER) {
            throw new JbkException("取水单价超出允许范围");
        }
        return requireUnitPrice((int) value);
    }

    public static long requireNonNegativeMl(Long ml, String label) {
        if (ml == null || ml < 0) {
            throw new JbkException(label + "必须为非负整数毫升");
        }
        return ml;
    }

    public static long requirePlanMl(Long planMl) {
        long value = requireNonNegativeMl(planMl, "计划水量");
        if (value == 0 || value > PLAN_ML_MAX) {
            throw new JbkException("计划水量必须在 1..99000 毫升范围内");
        }
        return value;
    }

    /** 金额(分) = ceil(毫升 × 分/升 ÷ 1000)，全程使用 checked arithmetic。 */
    public static long ceilAmount(long ml, int unitPriceFenPerLiter) {
        if (ml < 0) {
            throw new JbkException("结算水量必须为非负整数毫升");
        }
        int price = requireUnitPrice(unitPriceFenPerLiter);
        try {
            long numerator = Math.multiplyExact(ml, (long) price);
            long amount = Math.floorDiv(Math.addExact(numerator, 999L), 1000L);
            if (amount < 0) {
                throw new JbkException("结算金额不能为负数");
            }
            return amount;
        } catch (ArithmeticException e) {
            throw new JbkException("取水计费计算溢出，拒绝结算");
        }
    }
}

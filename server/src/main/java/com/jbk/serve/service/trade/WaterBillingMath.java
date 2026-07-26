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

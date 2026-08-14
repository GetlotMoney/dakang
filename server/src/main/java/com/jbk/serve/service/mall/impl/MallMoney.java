package com.jbk.serve.service.mall.impl;

import com.jbk.tool.exception.JbkException;

/**
 * 商城金额运算（E2E-09 S2 R1-P1-5）：一切乘加走 checked arithmetic。
 *
 * <p>金额是 bigint 分，单价与数量都由外部输入影响。普通 {@code *} / {@code +} 在越界时
 * 静默回绕成负数或小数，而库层的 `ITEM_AMOUNT = UNIT_PRICE × QUANTITY` 与
 * `ORDER_AMOUNT = PRODUCT + DELIVERY` 两条 CHECK 只校验等式成立、不校验是否溢出——
 * 回绕后的两边同样相等，能一路写进数据库。所以溢出必须在 Java 侧就显式拒绝。</p>
 *
 * <p>本类不设任何价格业务上限：单价多少是商品定价的事，这里只保证算术安全。</p>
 *
 * @author dakang
 * @since 2026-08-09
 */
final class MallMoney {

    private MallMoney() {
    }

    /** 单价 × 数量；溢出即拒绝。 */
    static long multiply(long unitPriceFen, long quantity) {
        try {
            return Math.multiplyExact(unitPriceFen, quantity);
        }
        catch (ArithmeticException overflow) {
            throw new JbkException("金额超出可处理范围，请调整数量或联系客服");
        }
    }

    /** 金额累加；溢出即拒绝。 */
    static long add(long left, long right) {
        try {
            return Math.addExact(left, right);
        }
        catch (ArithmeticException overflow) {
            throw new JbkException("金额超出可处理范围，请调整数量或联系客服");
        }
    }

    /** 件数累加（购物车合计等）；溢出即拒绝。 */
    static int addCount(int left, int right) {
        try {
            return Math.addExact(left, right);
        }
        catch (ArithmeticException overflow) {
            throw new JbkException("数量超出可处理范围，请调整后重试");
        }
    }
}

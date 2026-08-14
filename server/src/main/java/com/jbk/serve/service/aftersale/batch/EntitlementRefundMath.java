package com.jbk.serve.service.aftersale.batch;

import com.jbk.tool.exception.JbkException;

/**
 * 权益批次退款折算——<b>单一出处</b>（E2E-04 包D，REQ-061）。
 * 输入只取批次自身与冻结快照，绝不用 ws_card 聚合余额——聚合分不清多笔充值，多退少退都是事故。
 *
 * <h3>两种套餐两条公式（任务书 3.4 原文）</h3>
 * <pre>
 * 水量套餐：
 *   usedPrincipalFen = ceil(payAmountFen × usedWaterMl ÷ grantedWaterMl)
 *   refundableFen    = max(0, payAmountFen − usedPrincipalFen − consumedBonusFen)
 *
 * 纯金额套餐：
 *   refundableFen    = max(0, payAmountFen − consumedPrincipalFen − consumedBonusFen)
 * </pre>
 *
 * <p>水量侧向上取整：除不尽的一分归公司，方向确定可审计（floor 会在大量小额退款上持续多退）。
 * 已消费的赠送不退（{@code consumedBonusFen} 扣掉），未消费的赠送随批次作废、不退现金。
 * 只算不写、不持有 Mapper；全程 {@code Math.*Exact}，溢出在写库前炸掉。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public final class EntitlementRefundMath {

    private EntitlementRefundMath() {
    }

    /**
     * 水量套餐的已用本金。
     *
     * @param payAmountFen   本批次实付金额（分），来自批次快照
     * @param usedWaterMl    本批次已消费水量（毫升）= 发放量 − 剩余量
     * @param grantedWaterMl 本批次发放水量（毫升）
     */
    public static long usedPrincipalOfWaterPackage(long payAmountFen, long usedWaterMl, long grantedWaterMl) {
        requireNonNegative(payAmountFen, "实付金额");
        requireNonNegative(usedWaterMl, "已消费水量");
        if (grantedWaterMl <= 0) {
            throw new JbkException("水量套餐的发放水量必须为正，实际 " + grantedWaterMl);
        }
        if (usedWaterMl > grantedWaterMl) {
            // 已用超过发放：批次账本自相矛盾
            throw new JbkException("已消费水量 " + usedWaterMl + " 超过发放水量 " + grantedWaterMl
                    + "，批次账本异常，拒绝折算");
        }
        // ceil(a×b÷c) 用整数运算表达，避免浮点误差在分位上抖动
        long numerator = Math.addExact(Math.multiplyExact(payAmountFen, usedWaterMl), grantedWaterMl - 1);
        return numerator / grantedWaterMl;
    }

    /**
     * 水量套餐的可退金额。
     *
     * @param consumedBonusFen 已消费的赠送金额（分）；赠送用掉的部分不退
     */
    public static long refundableOfWaterPackage(long payAmountFen, long usedWaterMl, long grantedWaterMl,
                                                long consumedBonusFen) {
        long usedPrincipal = usedPrincipalOfWaterPackage(payAmountFen, usedWaterMl, grantedWaterMl);
        return refundableFrom(payAmountFen, usedPrincipal, consumedBonusFen);
    }

    /**
     * 纯金额套餐的可退金额。
     *
     * @param consumedPrincipalFen 已消费的本金金额（分）
     * @param consumedBonusFen     已消费的赠送金额（分）
     */
    public static long refundableOfCashPackage(long payAmountFen, long consumedPrincipalFen,
                                               long consumedBonusFen) {
        requireNonNegative(consumedPrincipalFen, "已消费本金");
        return refundableFrom(payAmountFen, consumedPrincipalFen, consumedBonusFen);
    }

    /**
     * 两条公式共用的收尾：{@code max(0, pay − usedPrincipal − consumedBonus)}。
     * 下限取 0：负的可退额一旦流进退款金额，「退款」就变成「向用户收钱」。
     */
    private static long refundableFrom(long payAmountFen, long usedPrincipalFen, long consumedBonusFen) {
        requireNonNegative(payAmountFen, "实付金额");
        requireNonNegative(usedPrincipalFen, "已消费本金");
        requireNonNegative(consumedBonusFen, "已消费赠送");
        long remaining = Math.subtractExact(
                Math.subtractExact(payAmountFen, usedPrincipalFen), consumedBonusFen);
        return Math.max(0L, remaining);
    }

    private static void requireNonNegative(long value, String label) {
        if (value < 0) {
            throw new JbkException(label + "不能为负：" + value);
        }
    }
}

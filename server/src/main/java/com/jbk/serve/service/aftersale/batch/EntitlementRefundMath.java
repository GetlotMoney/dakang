package com.jbk.serve.service.aftersale.batch;

import com.jbk.tool.exception.JbkException;

/**
 * 权益批次退款折算——<b>单一出处</b>（E2E-04 包D，REQ-061）。
 *
 * <h3>为什么不能用 ws_card 的聚合余额算退款</h3>
 * <p>聚合值只回答「这张卡现在还剩多少」，回答不了「要退的<b>这一笔</b>充值，
 * 它带来的权益被用掉了多少」。同一张卡上有两笔充值时，聚合剩余里既有本笔的也有别笔的：
 * 按聚合值退款，要么把别笔的剩余也退掉（多退），要么在本笔其实没怎么用时按聚合的低值退（少退）。
 * 两个方向都是资金事故。故折算的每一个输入都必须来自<b>批次自身</b>与下单时冻结的订单快照。</p>
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
 * <p><b>为什么水量侧向上取整</b>：ceil 让「用掉的本金」向上走，退款额随之向下走。
 * 除不尽时的那一分零头归公司而不是归用户——这是刻意的方向选择：
 * 反过来（floor）在大量小额退款上会持续多退，且每一笔都「看起来只差一分」。
 * 差额的绝对值不超过 1 分，方向确定，可审计。</p>
 *
 * <h3>赠送部分已消费即不退</h3>
 * <p>{@code consumedBonusFen} 从可退金额里扣掉：赠送权益是营销成本，用户没有为它付过钱，
 * 用掉之后再按实付金额全额退款，等于公司既送了水又退了钱。
 * 未消费的赠送部分不进入计算——它随批次一起作废即可，本来就不该退现金。</p>
 *
 * <p>本类<b>只算不写</b>，不持有任何 Mapper：输入由调用方在锁定批次后查好传进来。
 * 这样公式可被纯单测穷举，而调用方无法「顺手在公式里补一次查询」把口径改宽。
 * 全程 {@code Math.*Exact}：金额溢出必须在写库前炸掉，绝不静默回绕成负数。</p>
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
            // 已用超过发放：批次账本自相矛盾，继续算下去只会得出一个「看起来合理」的错数
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
     *
     * <p>下限取 0 而不是允许负数：负的可退额没有业务含义，而一旦它流进退款金额，
     * 「退款」就变成了「向用户收钱」。这里把它夹到 0，异常留给上面两个方法的
     * 账本自洽性检查去拒绝。</p>
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

package com.jbk.serve.service.aftersale.batch;

import com.jbk.tool.exception.JbkException;

/**
 * 首购退款后的水卡处置规则——<b>单一出处</b>（E2E-04 包D-5，任务书 3.4）。
 *
 * <p>任务书原文：「若卡无其他批次、成员授权、未完成订单及剩余权益，退款完成后卡转注销；
 * 否则保留卡，只撤销该批次并重算聚合有效期。不允许删除卡或历史流水。」
 * 剩余权益一项与 {@code TradeCardMapper.closeEmptyCard} 的零余额谓词互为印证。</p>
 *
 * <p>只判定不写库，证据由调用方在锁内查好传入；结果只有「注销」与「保留」，不存在「删卡」。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public final class CardClosureRule {

    private CardClosureRule() {
    }

    /**
     * 注销判定所需证据（全部在退款结算事务的卡行锁内查出）。
     *
     * @param firstPurchase       被退批次是否为「首次购卡」来源；已有卡充值的退款一律保留卡
     * @param otherBatchCount     该卡上除本批次之外仍未退款的批次数
     * @param activeMemberCount   该卡上仍有效的成员授权数
     * @param unfinishedOrderCount 该卡上仍在途（待支付/已支付/出水中/配送中）的订单数
     * @param remainAmountFen     冲减后卡上的余额（分）
     * @param remainMl            冲减后卡上的水量（毫升）
     */
    public record Evidence(boolean firstPurchase, int otherBatchCount, int activeMemberCount,
                           int unfinishedOrderCount, long remainAmountFen, long remainMl) {
    }

    /** 判定结论。{@code KEEP} 时调用方需重算聚合有效期。 */
    public enum Decision {
        /** 卡转注销（CARD_STATUS=4），不删卡、不删流水。 */
        CLOSE,
        /** 保留卡，只撤销该批次并重算聚合有效期。 */
        KEEP
    }

    public static Decision decide(Evidence evidence) {
        if (evidence == null) {
            throw new JbkException("水卡注销判定证据缺失");
        }
        if (evidence.otherBatchCount() < 0 || evidence.activeMemberCount() < 0
                || evidence.unfinishedOrderCount() < 0
                || evidence.remainAmountFen() < 0 || evidence.remainMl() < 0) {
            // 负数只可能来自聚合查询出错，「能不能注销」没有可信答案，拒绝整笔结算
            throw new JbkException("水卡注销判定证据非法（出现负数），拒绝结算");
        }
        boolean closable = evidence.firstPurchase()
                && evidence.otherBatchCount() == 0
                && evidence.activeMemberCount() == 0
                && evidence.unfinishedOrderCount() == 0
                && evidence.remainAmountFen() == 0
                && evidence.remainMl() == 0;
        return closable ? Decision.CLOSE : Decision.KEEP;
    }
}

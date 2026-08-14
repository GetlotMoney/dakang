package com.jbk.serve.service.aftersale.batch;

import cn.hutool.core.util.ObjectUtil;
import com.jbk.tool.data.aftersale.po.WsCardEntitlementBatch;
import com.jbk.tool.exception.JbkException;

/**
 * 充值/购卡退款的可退金额与冲减量计划——<b>单一出处</b>（E2E-04 包D-5，REQ-061）：
 * 一次算清并冻结「退多少钱（{@link EntitlementRefundMath} 冻结公式）/ 从卡上减多少（批次剩余，
 * 两维各自减）/ 能不能退（来源、状态、账本自洽三类闸）」。
 *
 * <p>冲减量是「剩余」而非「可退金额」：可退金额按实付折算，剩余含赠送——
 * 拿可退金额减卡会把赠送权益留在卡上白送，拿剩余去退款则为赠送额出真金白银。
 * 套餐口径看 {@code GRANT_WATER_ML}：发过水量按水量套餐折算，否则按纯金额套餐；
 * 混合套餐一旦被消费即 fail-closed 转人工（两条公式都无法给出唯一正确答案）。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public final class EntitlementRefundPlan {

    private EntitlementRefundPlan() {
    }

    /**
     * 退款计划（全部由服务端算定并冻结进 CALC_SNAPSHOT，前端一个字段都不参与）。
     *
     * @param batchId       被退的批次
     * @param refundableFen 可退金额（分），进 ws_refund.REFUND_AMOUNT
     * @param reverseFen    需从卡上冲减的余额权益（分）= 批次剩余
     * @param reverseMl     需从卡上冲减的水量权益（毫升）= 批次剩余
     * @param waterPackage  是否按水量套餐折算（用于快照与审计可复核）
     * @param usedWaterMl   已消费水量（毫升）
     *
     * <p>刻意不带「本批次是否分文未动」（订单落 7/8 的判据）：那必须在结算事务里按锁内重读现算，
     * 冻进计划就是一份会过期的副本。</p>
     */
    public record Plan(Long batchId, long refundableFen, long reverseFen, long reverseMl,
                       boolean waterPackage, long usedWaterMl) {
    }

    /**
     * 按批次算定退款计划。不需要调用方传「已消费赠送金额」：公式里本金与赠送同权重，
     * 合计（发放 − 剩余）即可，另传拆分只会多一个重复扣减入口。
     *
     * @param batch 锁定后读到的批次行
     */
    public static Plan of(WsCardEntitlementBatch batch) {
        long consumedBonusFen = 0L;
        requireRefundableSource(batch);
        long payAmountFen = requireNonNegative(batch.getPayAmountFen(), "批次实付金额");
        long grantFen = requireNonNegative(batch.getGrantAmountFen(), "批次发放余额");
        long grantMl = requireNonNegative(batch.getGrantWaterMl(), "批次发放水量");
        long remainFen = requireNonNegative(batch.getRemainAmountFen(), "批次剩余余额");
        long remainMl = requireNonNegative(batch.getRemainWaterMl(), "批次剩余水量");
        requireNonNegative(consumedBonusFen, "已消费赠送金额");
        if (remainFen > grantFen || remainMl > grantMl) {
            // 剩余大于发放：账本自相矛盾
            throw new JbkException("批次剩余超过发放量，账本异常，拒绝退款折算");
        }
        if (payAmountFen <= 0) {
            // 实付为 0 没有可退基准（历史聚合批次已在来源闸拦下，此处是实付缺失的充值批次）
            throw new JbkException("批次实付金额为 0，没有可退基准，转人工处理");
        }

        long usedFen = Math.subtractExact(grantFen, remainFen);
        long usedMl = Math.subtractExact(grantMl, remainMl);
        boolean waterPackage = grantMl > 0;
        boolean mixed = grantMl > 0 && grantFen > 0;
        if (mixed && (usedFen > 0 || usedMl > 0)) {
            throw new JbkException("水量与金额混合套餐已被部分消费，折算口径未冻结，转人工处理");
        }

        long refundableFen = waterPackage
                ? EntitlementRefundMath.refundableOfWaterPackage(payAmountFen, usedMl, grantMl, consumedBonusFen)
                : EntitlementRefundMath.refundableOfCashPackage(payAmountFen, usedFen, consumedBonusFen);
        if (refundableFen <= 0) {
            // 不是错误，但不该建出一张 0 元退款单
            throw new JbkException("该充值权益已消费完毕，可退金额为 0，不予退款");
        }
        return new Plan(batch.getId(), refundableFen, remainFen, remainMl, waterPackage, usedMl);
    }

    /**
     * 来源与状态闸：只有「首次购卡」「已有卡充值」两种来源、且处于 1可用 的批次可退。
     * 历史聚合批次（来源 3 / 状态 6）无付款归属永远不可退；已退款(3)不得二退；
     * 退款锁定(2)由 {@code uk_after_sale_source} 与本闸双重收敛。
     */
    private static void requireRefundableSource(WsCardEntitlementBatch batch) {
        if (ObjectUtil.isNull(batch) || ObjectUtil.isNull(batch.getId())) {
            throw new JbkException("权益批次不存在，无法退款");
        }
        Integer source = batch.getSourceType();
        if (!ObjectUtil.equals(source, EntitlementBatchOrder.SourceType.FIRST_PURCHASE)
                && !ObjectUtil.equals(source, EntitlementBatchOrder.SourceType.RECHARGE)) {
            throw new JbkException("该权益无法归属到具体充值订单（来源 " + source + "），退款只能走人工");
        }
        Integer status = batch.getBatchStatus();
        if (ObjectUtil.equals(status, EntitlementBatchOrder.BatchStatus.REFUND_LOCKED)) {
            throw new JbkException("该权益批次已有退款在处理中，拒绝重复受理");
        }
        if (ObjectUtil.equals(status, EntitlementBatchOrder.BatchStatus.REFUNDED)) {
            throw new JbkException("该权益批次已退款，拒绝二次退款");
        }
        if (!ObjectUtil.equals(status, EntitlementBatchOrder.BatchStatus.AVAILABLE)) {
            throw new JbkException("权益批次当前状态不可退款：" + status);
        }
    }

    private static long requireNonNegative(Number value, String label) {
        if (ObjectUtil.isNull(value)) {
            throw new JbkException(label + "缺失");
        }
        long amount = value.longValue();
        if (amount < 0) {
            throw new JbkException(label + "不能为负：" + amount);
        }
        return amount;
    }
}

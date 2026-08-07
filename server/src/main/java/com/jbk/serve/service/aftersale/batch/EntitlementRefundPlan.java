package com.jbk.serve.service.aftersale.batch;

import cn.hutool.core.util.ObjectUtil;
import com.jbk.tool.data.aftersale.po.WsCardEntitlementBatch;
import com.jbk.tool.exception.JbkException;

/**
 * 充值/购卡退款的可退金额与冲减量计划——<b>单一出处</b>（E2E-04 包D-5，REQ-061）。
 *
 * <h3>本类回答三个问题，一次算清并冻结</h3>
 * <ol>
 *   <li><b>退多少钱</b>：走 {@link EntitlementRefundMath} 的两条冻结公式，输入只取批次自身；</li>
 *   <li><b>从卡上减多少权益</b>：批次的剩余额度，两维各自减，绝不折算；</li>
 *   <li><b>这批次能不能退</b>：来源、状态、账本自洽性三类闸。</li>
 * </ol>
 *
 * <h3>为什么冲减量是「剩余」而不是「可退金额」</h3>
 * <p>两者是不同维度的东西，混用是本包最容易犯的错。可退金额按<b>实付</b>折算
 * （用掉的本金与赠送不退），而卡上要减掉的是这批次<b>还没被用掉</b>的权益——
 * 它包含赠送部分，也可能远大于或远小于可退金额。
 * 拿可退金额去减卡余额，赠送权益就会留在卡上白送；拿剩余去退款，公司会为赠送额出真金白银。</p>
 *
 * <h3>纯金额套餐与水量套餐怎么分</h3>
 * <p>看批次的 {@code GRANT_WATER_ML}：发过水量的按水量套餐折算（已用水量占比 × 实付），
 * 没发水量的按纯金额套餐折算（已消费金额直接抵扣）。两者都发的混合套餐按水量口径处理，
 * 因为水量是主权益、金额部分在这类套餐里恒为赠送——这一点由 {@code requireSaneBatch} 的
 * 「混合套餐必须整份未消费才可退」兜住：一旦被用过，两条公式都无法给出唯一正确答案，
 * 此时 fail-closed 转人工，而不是挑一条看起来合理的公式。</p>
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
     * <p>刻意<b>不</b>带「本批次是否分文未动」（订单落 7 还是 8 的判据）：那要在结算事务里
     * 按<b>锁内重读</b>的批次行现算。受理与结算之间隔着一个外部系统，受理时算出的"未动"
     * 到结算时可能已经不成立；把它冻进计划里，就等于给终态判定留了一份会过期的副本。</p>
     */
    public record Plan(Long batchId, long refundableFen, long reverseFen, long reverseMl,
                       boolean waterPackage, long usedWaterMl) {
    }

    /**
     * 按批次算定退款计划。
     *
     * <h3>为什么不需要调用方传「已消费赠送金额」</h3>
     * <p>冻结公式里 {@code consumedPrincipal} 与 {@code consumedBonus} 都以权重 1 从实付里减掉，
     * 故只要传入的是「已消费金额<b>合计</b>」，两项怎么拆分对结果没有影响
     * （拆分只影响可读性，不影响金额）。合计由批次自身给出：发放 − 剩余。
     * 让调用方另算一份拆分再传进来，只会多出一个可以把同一笔消费重复减两次的入口。
     * 纯水量套餐的金额维度发放恒为 0，故赠送消费必然为 0；
     * 混合套餐一旦被消费即在下面 fail-closed，不进入公式。</p>
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
            // 剩余大于发放：账本已自相矛盾，继续算只会得出一个「看起来合理」的错数
            throw new JbkException("批次剩余超过发放量，账本异常，拒绝退款折算");
        }
        if (payAmountFen <= 0) {
            // 实付为 0 的批次没有可退基准（历史聚合批次即如此，已在来源闸拦下；
            // 走到这里说明是一个实付缺失的充值批次，同样只能转人工）
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
            // 权益已用尽或用量折算后无可退金额：这不是错误，但也不该建出一张 0 元退款单
            throw new JbkException("该充值权益已消费完毕，可退金额为 0，不予退款");
        }
        return new Plan(batch.getId(), refundableFen, remainFen, remainMl, waterPackage, usedMl);
    }

    /**
     * 来源与状态闸：只有「首次购卡」「已有卡充值」两种来源、且处于 1可用 的批次可退。
     *
     * <p>历史聚合批次（来源 3 / 状态 6）永远退不了款：这部分权益对应哪一笔付款、付了多少，
     * 账本里没有答案。已退款(3)不得二次退款；退款锁定(2)说明已有一笔退款在处理中，
     * 由 {@code uk_after_sale_source} 与本闸双重收敛。</p>
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

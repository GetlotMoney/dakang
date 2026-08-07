package com.jbk.serve.service.aftersale.batch;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.tool.data.aftersale.po.WsCardEntitlementBatch;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;

/**
 * 权益批次写入——<b>单一出处</b>（E2E-04 包D，REQ-061）。
 *
 * <h3>为什么两个入账点必须共用这一份</h3>
 * <p>建批次的地方有两处：首次购卡（{@code RechargeIssueTxImpl}）与已有卡充值
 * （{@code RechargeCreditTxImpl}）。两处各写一遍，就有两份「批次该长什么样」的定义——
 * 而批次是退款折算的<b>唯一基准</b>，两份定义一旦漂移（比如一处记了赠送、另一处没记），
 * 同样的充值经不同路径进来会退出不同的钱，且两条路都「看起来正常」。</p>
 *
 * <h3>本类是静态工具而非 Service</h3>
 * <p>它必须在入账事务<b>内部</b>被调用（批次与卡权益必须同生共死：卡加了权益而批次没建，
 * 那笔充值就永远退不了；批次建了而卡没加，退款会退出根本没发放的权益）。
 * 做成 Service 注入会诱导有人给它加 {@code @Transactional}，
 * 那时传播级别一旦写成 REQUIRES_NEW，同生共死就断了。静态方法没有这个入口。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
public final class EntitlementBatchWriter {

    private EntitlementBatchWriter() {
    }

    /**
     * 在入账事务内建立本次充值的权益批次。
     *
     * @param mapper       批次 Mapper
     * @param order        充值订单（提供 ORDER_ID / ORDER_NO / PACKAGE_ID / PACKAGE_SNAP）
     * @param cardId       目标水卡
     * @param userId       卡主
     * @param paymentId    本次充值的支付单ID；批次退款与原支付共键的锚点
     * @param sourceType   {@link EntitlementBatchOrder.SourceType#FIRST_PURCHASE} 或 {@code RECHARGE}
     * @param payAmountFen 本次实付金额（分）；退款折算的分子基准
     * @param grantFen     本次发放的余额权益（分）=本金+赠送
     * @param bonusFen     其中赠送部分（分）
     * @param grantMl      本次发放的水量权益（毫升）
     * @param expireTime   本批次有效期；null=永久
     * @param scopeJson    本批次可用范围
     * @param now          业务时间，与入账事务同一个时钟
     * @return 批次 ID
     */
    public static Long createOnCredit(WsCardEntitlementBatchMapper mapper, WsOrder order,
                                      Long cardId, Long userId, Long paymentId, int sourceType,
                                      long payAmountFen, long grantFen, long bonusFen, long grantMl,
                                      String expireTime, String scopeJson, String now) {
        if (mapper == null || ObjectUtil.isNull(order) || cardId == null || userId == null
                || paymentId == null || paymentId <= 0) {
            throw new JbkException("权益批次建立入参缺失");
        }
        if (sourceType != EntitlementBatchOrder.SourceType.FIRST_PURCHASE
                && sourceType != EntitlementBatchOrder.SourceType.RECHARGE) {
            // 历史聚合批次只能由回填脚本产生，绝不由入账路径建出：
            // 它是「无法归属」的标记，而入账路径恰恰知道归属。
            throw new JbkException("入账路径只能建立首次购卡或充值来源的批次，实际 " + sourceType);
        }
        requireNonNegative(payAmountFen, "实付金额");
        requireNonNegative(grantFen, "发放余额");
        requireNonNegative(bonusFen, "赠送金额");
        requireNonNegative(grantMl, "发放水量");
        if (bonusFen > grantFen) {
            throw new JbkException("赠送金额 " + bonusFen + " 超过发放余额 " + grantFen + "，批次数据自相矛盾");
        }
        if (grantFen == 0 && grantMl == 0) {
            // 零权益批次没有业务含义，且会让「每笔充值恰好一个批次」的对账多出一堆空行
            throw new JbkException("本次充值未发放任何权益，拒绝建立空批次");
        }

        WsCardEntitlementBatch batch = new WsCardEntitlementBatch()
                .setCardId(cardId)
                .setUserId(userId)
                .setSourceType(sourceType)
                .setOrderId(order.getId())
                .setOrderNo(order.getOrderNo())
                .setPaymentId(paymentId)
                .setPackageId(order.getPackageId())
                .setPackageSnap(order.getPackageSnap())
                .setPayAmountFen(payAmountFen)
                .setGrantAmountFen(grantFen)
                .setGrantBonusFen(bonusFen)
                .setGrantWaterMl(grantMl)
                // 新建批次的剩余恒等于发放量：任何「建的时候就少一点」都会让首笔退款算错
                .setRemainAmountFen(grantFen)
                .setRemainWaterMl(grantMl)
                .setExpireTime(expireTime)
                .setScopeJson(scopeJson)
                .setBatchStatus(EntitlementBatchOrder.BatchStatus.AVAILABLE)
                .setRefundedAmountFen(0L)
                .setVersion(1);
        batch.setDataStatus(0);
        batch.setCreateBy(userId);
        batch.setCreateTime(now);
        batch.setUpdateBy(userId);
        batch.setUpdateTime(now);

        // 撞 uk_batch_order 即同一笔充值重放，整事务回滚，天然幂等（铁律②）
        if (mapper.insert(batch) != 1 || batch.getId() == null) {
            throw new JbkException("权益批次创建失败");
        }
        return batch.getId();
    }

    /**
     * 运营赠卡入批（E2E-08 包D / D-213 口径）：独立入口而非放宽 {@link #createOnCredit}——
     * 放宽会让入账路径能建无支付锚点的批次。赠卡批次恒不可退（NON_REFUNDABLE）、
     * 实付恒 0、无订单/支付锚；幂等由调用方的赠卡请求号（派生卡号 uk_card_no）保证。
     *
     * @param expireTime 必填非空（D-213：EXPIRE_TIME 机制仅用于活动赠卡）
     */
    public static Long createOnGift(WsCardEntitlementBatchMapper mapper, Long cardId, Long userId,
                                    long grantFen, long grantMl, String expireTime,
                                    String scopeJson, String snapJson, String now) {
        if (mapper == null || cardId == null || userId == null) {
            throw new JbkException("赠卡批次入参缺失");
        }
        if (StrUtil.isBlank(expireTime)) {
            // D-213：赠卡必带有效期。编号留在代码里，消息只说事实。
            throw new JbkException("赠卡批次必须带有效期");
        }
        requireNonNegative(grantFen, "发放余额");
        requireNonNegative(grantMl, "发放水量");
        if (grantFen == 0 && grantMl == 0) {
            throw new JbkException("赠卡未发放任何权益，拒绝建立空批次");
        }
        WsCardEntitlementBatch batch = new WsCardEntitlementBatch()
                .setCardId(cardId)
                .setUserId(userId)
                .setSourceType(EntitlementBatchOrder.SourceType.GIFT)
                .setPayAmountFen(0L)
                .setGrantAmountFen(grantFen)
                .setGrantBonusFen(grantFen)
                .setGrantWaterMl(grantMl)
                .setRemainAmountFen(grantFen)
                .setRemainWaterMl(grantMl)
                .setExpireTime(expireTime)
                .setScopeJson(scopeJson)
                .setPackageSnap(snapJson)
                .setBatchStatus(EntitlementBatchOrder.BatchStatus.NON_REFUNDABLE)
                .setRefundedAmountFen(0L)
                .setVersion(1);
        batch.setDataStatus(0);
        batch.setCreateBy(userId);
        batch.setCreateTime(now);
        batch.setUpdateBy(userId);
        batch.setUpdateTime(now);
        if (mapper.insert(batch) != 1 || batch.getId() == null) {
            throw new JbkException("赠卡批次创建失败");
        }
        return batch.getId();
    }

    private static void requireNonNegative(long value, String label) {
        if (value < 0) {
            throw new JbkException(label + "不能为负：" + value);
        }
    }
}

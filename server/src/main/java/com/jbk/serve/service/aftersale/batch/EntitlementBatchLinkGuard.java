package com.jbk.serve.service.aftersale.batch;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.aftersale.po.WsCardEntitlementBatch;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;

/**
 * 充值订单、目标卡与权益批次的共键守卫（E2E-04 包D）。
 *
 * <p>退款预览、受理、退款单创建与成功结算必须共用这一份校验。只按
 * {@code ORDER_ID} 找到批次并不足以证明它属于该订单：批次的卡、用户或订单号错位时，
 * 后续可能按一张卡的批次计算退款，却从另一张卡冲减权益。</p>
 */
public final class EntitlementBatchLinkGuard {

    private EntitlementBatchLinkGuard() {
    }

    /**
     * 核验批次与充值订单、预期卡主的完整共键。
     *
     * @param expectedCardId    锁内目标卡或售后动作冻结的卡 ID
     * @param expectedUserId    锁内目标卡或售后动作冻结的卡主 ID
     * @param expectedPaymentId 已取得支付证据时传支付单 ID；尚未读取支付证据时传 {@code null}
     */
    public static WsCardEntitlementBatch requireLinked(WsOrder order, WsCardEntitlementBatch batch,
                                                        Long expectedCardId, Long expectedUserId,
                                                        Long expectedPaymentId) {
        if (ObjectUtil.isNull(order) || ObjectUtil.notEqual(order.getDataStatus(), 0)
                || ObjectUtil.notEqual(order.getOrderType(), TradeEnum.OrderType.CARD.getValue())) {
            throw new JbkException("充值退款关联订单不存在、已删除或类型错误");
        }
        if (ObjectUtil.isNull(batch) || ObjectUtil.notEqual(batch.getDataStatus(), 0)) {
            throw new JbkException("充值退款关联权益批次不存在或已删除");
        }
        requireId(order.getId(), "充值订单ID");
        requireId(order.getCardId(), "充值订单目标卡ID");
        requireId(order.getUserId(), "充值订单用户ID");
        requireId(expectedCardId, "预期水卡ID");
        requireId(expectedUserId, "预期卡主用户ID");
        if (StrUtil.isBlank(order.getOrderNo()) || StrUtil.isBlank(batch.getOrderNo())) {
            throw new JbkException("充值订单或权益批次缺少订单号，拒绝退款");
        }
        if (ObjectUtil.notEqual(batch.getOrderId(), order.getId())
                || !StrUtil.equals(batch.getOrderNo(), order.getOrderNo())
                || ObjectUtil.notEqual(batch.getCardId(), order.getCardId())
                || ObjectUtil.notEqual(batch.getCardId(), expectedCardId)
                || ObjectUtil.notEqual(batch.getUserId(), order.getUserId())
                || ObjectUtil.notEqual(batch.getUserId(), expectedUserId)) {
            throw new JbkException("充值订单、权益批次与目标水卡共键错位，拒绝退款");
        }
        if (ObjectUtil.isNotNull(expectedPaymentId)) {
            requireId(expectedPaymentId, "原支付单ID");
            if (ObjectUtil.notEqual(batch.getPaymentId(), expectedPaymentId)) {
                throw new JbkException("权益批次与原支付单共键错位，拒绝退款");
            }
        }
        return batch;
    }

    private static void requireId(Long id, String label) {
        if (ObjectUtil.isNull(id) || id <= 0) {
            throw new JbkException(label + "非法");
        }
    }
}

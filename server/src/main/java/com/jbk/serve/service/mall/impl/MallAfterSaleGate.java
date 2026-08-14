package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.data.mall.po.WsMallAfterSale;
import com.jbk.tool.data.mall.po.WsMallOrder;
import com.jbk.tool.data.mall.po.WsMallOrderItem;
import com.jbk.tool.data.mall.po.WsMallPayment;
import com.jbk.tool.data.mall.po.WsMallRefund;
import com.jbk.tool.utils.DateUtils;

import java.util.List;

/**
 * 商城售后共键校验器（E2E-09 S4）——<b>唯一出处</b>。
 *
 * <p>售后链有四个入口（用户申请与取消、PC 审核收货质检、退款事实推进、换货补发），
 * 每个入口都要回答同一个问题：这张售后单和这张订单、这个用户、这笔支付、这些明细，
 * 到底是不是同一件事。判据只写一份，四处共用——写四份的结果是四份会各自演化，
 * 而资金链上最先被发现的往往是最松的那一份。</p>
 *
 * <p>所有读取一律用「原样读」（绕过逻辑删除过滤）后自己核 DATA_STATUS：这样「被删了」
 * 是一个可判定的事实，而不是「查不到」。查不到与被删在退款场景里后果完全不同。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
final class MallAfterSaleGate {

    private MallAfterSaleGate() {
    }

    /**
     * 售后单与订单的基础共键：存在、未删除、归属一致、仓一致。
     *
     * @return null 表示通过；否则为可落进轨迹与错误提示的拒绝原因
     */
    static String linkMismatch(WsMallAfterSale afterSale, WsMallOrder order, Long expectUserId) {
        if (ObjectUtil.isNull(afterSale) || !ObjectUtil.equal(afterSale.getDataStatus(), 0)) {
            return "售后单不存在或已删除";
        }
        if (ObjectUtil.isNull(order) || !ObjectUtil.equal(order.getDataStatus(), 0)) {
            return "原订单不存在或已删除";
        }
        if (!ObjectUtil.equal(afterSale.getOrderId(), order.getId())
                || !ObjectUtil.equal(afterSale.getOrderNo(), order.getOrderNo())
                || !ObjectUtil.equal(afterSale.getUserId(), order.getUserId())
                || !ObjectUtil.equal(afterSale.getWarehouseId(), order.getWarehouseId())) {
            return "售后单与订单共键不一致，请人工核查";
        }
        if (ObjectUtil.isNotNull(expectUserId)
                && !ObjectUtil.equal(afterSale.getUserId(), expectUserId)) {
            // 他人售后一律按不存在处理，不泄露存在性
            return "售后单不存在";
        }
        if (ObjectUtil.isNotNull(order.getSourceAfterSaleId())) {
            // 换货补发单是内部零价单：它自己不能再被申请售后，否则退款会退到一笔没收过的钱上
            return "换货补发单不支持再次申请售后";
        }
        return null;
    }

    /** 支付单共键：必须是这张订单那笔已成功的支付，退款只能原路退回它。 */
    static String paymentMismatch(WsMallOrder order, WsMallPayment payment) {
        if (ObjectUtil.isNull(payment) || !ObjectUtil.equal(payment.getDataStatus(), 0)) {
            return "原支付单不存在或已删除";
        }
        if (!ObjectUtil.equal(payment.getOrderId(), order.getId())
                || !ObjectUtil.equal(payment.getOrderNo(), order.getOrderNo())) {
            return "支付单与订单共键不一致，请人工核查";
        }
        if (!ObjectUtil.equal(payment.getPayStatus(), MallEnum.PayStatus.SUCCESS.getValue())) {
            return "原订单未支付成功，无可退金额";
        }
        return null;
    }

    /** 退款单共键：与售后单、订单、支付单、用户四方一致，金额与币种同源。 */
    static String refundMismatch(WsMallRefund refund, WsMallAfterSale afterSale,
                                 WsMallOrder order, WsMallPayment payment) {
        if (ObjectUtil.isNull(refund) || !ObjectUtil.equal(refund.getDataStatus(), 0)) {
            return "退款单不存在或已删除";
        }
        if (!ObjectUtil.equal(refund.getAfterSaleId(), afterSale.getId())
                || !ObjectUtil.equal(refund.getAfterSaleNo(), afterSale.getAfterSaleNo())
                || !ObjectUtil.equal(refund.getOrderId(), order.getId())
                || !ObjectUtil.equal(refund.getOrderNo(), order.getOrderNo())
                || !ObjectUtil.equal(refund.getUserId(), order.getUserId())
                || !ObjectUtil.equal(refund.getPaymentId(), payment.getId())) {
            return "退款单与售后/订单/支付共键不一致，请人工核查";
        }
        if (!ObjectUtil.equal(refund.getRefundAmountFen(), afterSale.getRefundAmountFen())) {
            return "退款金额与售后单不一致，请人工核查";
        }
        if (!ObjectUtil.equal(refund.getCurrency(), payment.getCurrency())) {
            // 金额相同币种不同等于退错了钱
            return "退款币种与原支付单不一致，请人工核查";
        }
        if (!ObjectUtil.equal(refund.getRefundSource(), payment.getPaySource())) {
            return "退款来源与原支付来源不一致，请人工核查";
        }
        return null;
    }

    /**
     * 售后明细与原订单明细的逐项共键：明细必须属于本订单，SKU 与单价逐字相同。
     *
     * <p>单价不重新取 SKU 现价——商品事后调价不该改变已经卖出去那一单该退多少。</p>
     */
    static String itemsMismatch(List<WsMallOrderItem> orderItems, Long orderItemId, Long skuId,
                                Long unitPriceFen) {
        for (WsMallOrderItem item : orderItems) {
            if (ObjectUtil.equal(item.getId(), orderItemId)) {
                if (!ObjectUtil.equal(item.getSkuId(), skuId)
                        || !ObjectUtil.equal(item.getUnitPriceFen(), unitPriceFen)) {
                    return "售后明细与原订单明细不一致，请人工核查";
                }
                return null;
            }
        }
        return "售后明细不属于该订单，请人工核查";
    }

    /**
     * 售后申请窗：签收时间必须是合法业务时间，且申请落在 [签收, 签收+窗口] 内。
     *
     * <p>签收时间缺失或非法时一律拒绝，不用当前时间或订单更新时间猜测——猜出来的窗口
     * 会让早已过期的订单重新可退，而这件事在账面上完全看不出来。</p>
     */
    static String windowMismatch(String signTime, String applyTime, int windowDays) {
        if (!DateUtils.isCanonicalBusinessTime(signTime)) {
            return "订单签收时间缺失或非法，无法判定售后窗口";
        }
        if (!DateUtils.isCanonicalBusinessTime(applyTime)) {
            return "申请时间非法";
        }
        // 两串都已确认是 14 位定宽规范形态：等宽定长下字典序即时间序
        if (applyTime.compareTo(signTime) < 0) {
            return "申请时间早于签收时间，请人工核查";
        }
        String deadline = DateUtils.plusSeconds(signTime, windowDays * 24L * 3600L);
        if (applyTime.compareTo(deadline) > 0) {
            return "已超过售后申请期限";
        }
        return null;
    }
}

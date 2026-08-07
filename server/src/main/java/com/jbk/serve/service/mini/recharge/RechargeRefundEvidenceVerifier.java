package com.jbk.serve.service.mini.recharge;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.tool.consts.aftersale.AfterSaleEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.mini.vo.MiniAfterSaleProgressVo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 充值退款终态的只读证据闸。
 *
 * <p>订单状态 7/8 只能由 E2E-04 充值退款事务产生。只看订单状态会把手工改库或关联错位
 * 误报成已退款，因此小程序详情与 pay-status 必须共同经过这一处校验。</p>
 */
@Component
@RequiredArgsConstructor
public class RechargeRefundEvidenceVerifier {

    private final WsOrderMapper orderMapper;

    /** 非退款终态不处理；退款终态缺少完整售后投影时 fail-closed。 */
    public void requireIfRefunded(WsOrder order) {
        if (order == null || !isRefundedStatus(order.getOrderStatus())) {
            return;
        }
        MiniAfterSaleProgressVo progress = orderMapper
                .selectLatestMiniAfterSaleProgress(order.getId(), order.getUserId());
        String mismatch = mismatchReason(order, progress);
        if (mismatch != null) {
            throw new JbkException("充值退款证据不一致：" + mismatch);
        }
    }

    public static boolean isRefundedStatus(Integer status) {
        return ObjectUtil.equals(status, TradeEnum.OrderStatus.REFUNDED.getValue())
                || ObjectUtil.equals(status, TradeEnum.OrderStatus.PART_REFUNDED.getValue());
    }

    static String mismatchReason(WsOrder order, MiniAfterSaleProgressVo progress) {
        if (progress == null) {
            return "缺少售后动作";
        }
        if (StrUtil.isBlank(progress.getAfterSaleNo())
                || !ObjectUtil.equals(progress.getSourceType(),
                    AfterSaleEnum.SourceType.RECHARGE_REFUND.getValue())
                || !ObjectUtil.equals(progress.getActionType(),
                    AfterSaleEnum.ActionType.GATEWAY_REFUND.getValue())
                || !ObjectUtil.equals(progress.getActionStatus(),
                    AfterSaleEnum.ActionStatus.SUCCESS.getValue())) {
            return "售后动作类型、来源或终态不匹配";
        }
        Long productFen = progress.getRefundProductFen();
        Long serviceFen = progress.getRefundServiceFen();
        Long productMl = progress.getRefundProductMl();
        Long totalFen = progress.getRefundAmount();
        if (productFen == null || productFen < 0
                || serviceFen == null || serviceFen < 0
                || productMl == null || productMl < 0
                || totalFen == null || totalFen <= 0) {
            return "退款额度缺失或非法";
        }
        try {
            if (Math.addExact(productFen, serviceFen) != totalFen) {
                return "退款金额分项与合计不一致";
            }
        } catch (ArithmeticException overflow) {
            return "退款金额合计溢出";
        }
        if (!ObjectUtil.equals(progress.getRefundSource(), 1)
                && !ObjectUtil.equals(progress.getRefundSource(), 2)) {
            return "缺少有效退款来源";
        }
        if (StrUtil.isBlank(order.getFinishTime())
                || !StrUtil.equals(order.getFinishTime(), progress.getFinishTime())) {
            return "订单与售后终态时间不一致";
        }
        return null;
    }
}

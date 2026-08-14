package com.jbk.serve.service.mini.recharge;

import cn.hutool.core.util.StrUtil;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;

/**
 * 充值订单"当前能否发起支付"的唯一判定（抽自 Pay-Sim，Pay-Sim 与微信 JSAPI 下单共用）。
 * 两条支付通道各写一套门，漂移时会出现"模拟通道拒绝、真实通道放行"——真实通道多扣钱。
 * 返回 null 表示可支付；否则为面向用户的拒绝原因。
 */
public final class RechargePayEligibility {

    private RechargePayEligibility() {
    }

    public static String rejectReason(WsOrder order, WsPayment payment, String now) {
        int ord = order.getOrderStatus() == null ? -1 : order.getOrderStatus();
        int pay = payment.getPayStatus() == null ? -1 : payment.getPayStatus();

        switch (ord) {
            case RechargePayStatus.ORDER_PENDING:
                break;
            case RechargePayStatus.ORDER_PAID:
                return "订单已支付，权益正在入账中，无需重复支付";
            case RechargePayStatus.ORDER_FINISHED:
                return "订单已完成，无需再次支付";
            case RechargePayStatus.ORDER_CANCELLED:
                return "订单已取消或关闭，无法继续支付，请重新下单";
            case RechargePayStatus.ORDER_ABNORMAL:
                return "订单支付异常已转人工对账，请联系客服，不可重复支付";
            case RechargePayStatus.ORDER_REFUNDED:
                return "订单已退款，无法继续支付";
            default:
                // 未登记组合一律 fail-closed：不认识的状态绝不当成可支付
                return "订单当前状态不可支付，请联系客服";
        }

        if (pay == RechargePayStatus.PAY_SUCCESS) {
            // order 1/payment 2 本身已是不自洽数据，但对用户而言最重要的事实是「钱已经收到了」
            return "该订单已收到支付成功事实，无需重复支付";
        }
        if (pay == RechargePayStatus.PAY_CLOSED) {
            return "该订单支付已关闭，无法继续支付，请重新下单";
        }
        if (pay != RechargePayStatus.PAY_PENDING) {
            return "订单支付单状态异常，无法继续支付，请联系客服";
        }

        if (StrUtil.isBlank(payment.getPayExpireTime())) {
            // 截止时间是付款资格的唯一依据，缺失时无从判断按时与否，只能拒
            return "订单付款截止时间缺失，无法继续支付，请联系客服";
        }
        if (!RechargePayExpire.paidInTime(now, payment.getPayExpireTime())) {
            return "该订单已超过付款截止时间（" + payment.getPayExpireTime() + "），无法继续支付，请重新下单";
        }
        return null;
    }
}

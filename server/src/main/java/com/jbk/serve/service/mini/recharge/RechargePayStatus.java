package com.jbk.serve.service.mini.recharge;

import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsPaymentEvent;

import java.util.List;
import java.util.Objects;

/**
 * pay-status 的**精确状态矩阵**（L2 契约 §9.1）。
 *
 * <p>纯函数：输入 order/payment/events/充值流水条数，输出解释结果或 mismatch 原因。
 * 严禁 {@code orderStatus >= n} 一类数值序比较——只按列出的精确组合解释，其余一律 mismatch。</p>
 */
public final class RechargePayStatus {

    /** 支付状态(1342)。 */
    public static final int PAY_PENDING = 1;
    public static final int PAY_SUCCESS = 2;
    public static final int PAY_CLOSED = 4;
    /** 订单状态(1341)。 */
    public static final int ORDER_PENDING = 1;
    public static final int ORDER_PAID = 2;
    public static final int ORDER_FINISHED = 4;
    public static final int ORDER_CANCELLED = 5;
    public static final int ORDER_ABNORMAL = 6;
    public static final int ORDER_REFUNDED = 7;
    /** 事件处理态。 */
    public static final int P_PENDING = 1;
    public static final int P_PROCESSING = 2;
    public static final int P_PROCESSED = 3;
    public static final int P_RETRY_WAIT = 4;
    public static final int P_RECONCILIATION = 5;

    public static final String SUCCESS = "SUCCESS";
    public static final String NOTPAY = "NOTPAY";
    public static final String CLOSED = "CLOSED";

    /** 解释结果。{@code ok=false} 时 {@code reason} 为结构化 mismatch 原因。 */
    public record Resolved(boolean ok, String statusCode, String statusMessage, String reason) {
        static Resolved ok(String code, String msg) {
            return new Resolved(true, code, msg, null);
        }

        static Resolved mismatch(String reason) {
            return new Resolved(false, "MISMATCH", "订单支付数据不一致，请联系客服", reason);
        }
    }

    private RechargePayStatus() {
    }

    /**
     * 按精确矩阵解释状态。调用方须已完成共键校验（本方法只做组合与事件语义判定）。
     *
     * @param flowCount 该订单的充值入账流水条数（FLOW_TYPE=1，跨全部 DATA_STATUS）
     */
    public static Resolved resolve(WsOrder order, WsPayment payment, List<WsPaymentEvent> events, long flowCount) {
        int pay = payment.getPayStatus() == null ? -1 : payment.getPayStatus();
        int ord = order.getOrderStatus() == null ? -1 : order.getOrderStatus();
        List<WsPaymentEvent> successes = byState(events, SUCCESS);
        List<WsPaymentEvent> closeds = byState(events, CLOSED);
        List<WsPaymentEvent> notpays = byState(events, NOTPAY);

        // 任何未登记的 TRADE_STATE 只留证并转人工，不参与状态解释
        if (events.size() != successes.size() + closeds.size() + notpays.size()) {
            return Resolved.mismatch("存在未登记的支付事实状态");
        }

        if (pay == PAY_PENDING && ord == ORDER_PENDING) {
            if (flowCount != 0) {
                return Resolved.mismatch("待支付订单不应存在充值流水");
            }
            if (!successes.isEmpty() || !closeds.isEmpty()) {
                return Resolved.mismatch("待支付订单不应存在成功或关闭事实");
            }
            if (notpays.stream().anyMatch(e -> !isProcessed(e))) {
                return Resolved.mismatch("待支付订单的查询事实未收敛");
            }
            return Resolved.ok("WAITING_PAYMENT", "待支付");
        }

        if (pay == PAY_CLOSED && ord == ORDER_CANCELLED) {
            if (flowCount != 0) {
                return Resolved.mismatch("已关闭订单不应存在充值流水");
            }
            if (!successes.isEmpty()) {
                return Resolved.mismatch("已关闭订单不应存在成功事实");
            }
            if (closeds.isEmpty() || closeds.stream().anyMatch(e -> !isProcessed(e))) {
                return Resolved.mismatch("已关闭订单缺少已处理的关闭事实");
            }
            // §9.1 只允许"更早的 PROCESSED NOTPAY"共存。一条未收敛的 NOTPAY 意味着支付方
            // 曾说未支付却没走完处理路径，与"已按权威结果关闭"这个结论互相冲突，属于必须暴露的事实。
            if (notpays.stream().anyMatch(e -> !isProcessed(e))) {
                return Resolved.mismatch("已关闭订单存在未收敛的查询事实");
            }
            return Resolved.ok("CLOSED", "订单已关闭");
        }

        if (pay == PAY_SUCCESS && ord == ORDER_PAID) {
            if (flowCount != 0) {
                return Resolved.mismatch("尚未入账的订单不应存在充值流水");
            }
            if (!closeds.isEmpty()) {
                return Resolved.mismatch("已支付订单不应存在关闭事实");
            }
            if (!hasInTimeSuccess(successes, payment)) {
                return Resolved.mismatch("缺少按时支付的成功事实");
            }
            if (successes.stream().anyMatch(e -> isProcessed(e) || isReconciliation(e))) {
                return Resolved.mismatch("已支付待入账订单的成功事实处理态非法");
            }
            return Resolved.ok("PAID_CREDIT_PENDING", "已支付，权益入账中");
        }

        if (pay == PAY_SUCCESS && ord == ORDER_FINISHED) {
            if (flowCount != 1) {
                return Resolved.mismatch("已完成订单的充值流水必须恰好一条");
            }
            if (!closeds.isEmpty()) {
                return Resolved.mismatch("已完成订单不应存在关闭事实");
            }
            if (successes.stream().noneMatch(e -> isProcessed(e) && inTime(e, payment))) {
                return Resolved.mismatch("已完成订单缺少已处理且按时的成功事实");
            }
            if (successes.stream().anyMatch(RechargePayStatus::isReconciliation)) {
                return Resolved.mismatch("已完成订单不应存在需对账的成功事实");
            }
            return Resolved.ok("COMPLETED", "充值已到账");
        }

        if (pay == PAY_SUCCESS && ord == ORDER_ABNORMAL) {
            if (flowCount != 0) {
                return Resolved.mismatch("异常待处理订单不应存在已完成充值流水");
            }
            if (successes.isEmpty()) {
                return Resolved.mismatch("异常待处理订单缺少成功事实");
            }
            boolean allReconciliation = successes.stream().allMatch(RechargePayStatus::isReconciliation);
            boolean approvedGroup = isApprovedRecoveryGroup(successes);
            if (!allReconciliation && !approvedGroup) {
                return Resolved.mismatch("异常订单的成功事实既非全部待对账，也不是同一已授权恢复组");
            }
            return Resolved.ok("RECONCILIATION_REQUIRED", "订单异常，待人工对账");
        }

        if (pay == PAY_SUCCESS && ord == ORDER_REFUNDED) {
            // 退款合同（REQ-044）未冻结、未启用：fail-closed，绝不把状态 7 拼成成功
            return new Resolved(false, "REFUND_CONTRACT_NOT_ENABLED",
                    "退款流程尚未启用，请联系客服", "order 7 预留给后续真实退款合同");
        }

        return Resolved.mismatch("payment " + pay + " / order " + ord + " 不是合法组合");
    }

    /**
     * 多事件处理态聚合（仅用于展示）：
     * RECONCILIATION_REQUIRED &gt; PROCESSING &gt; RETRY_WAIT &gt; PENDING &gt; PROCESSED。
     * 待支付且无事件时返回 WAITING_PAYMENT。
     */
    public static String aggregateProcessing(List<WsPaymentEvent> events) {
        if (events == null || events.isEmpty()) {
            return "WAITING_PAYMENT";
        }
        int[] priority = { P_RECONCILIATION, P_PROCESSING, P_RETRY_WAIT, P_PENDING, P_PROCESSED };
        String[] names = { "RECONCILIATION_REQUIRED", "PROCESSING", "RETRY_WAIT", "PENDING", "PROCESSED" };
        for (int i = 0; i < priority.length; i++) {
            int want = priority[i];
            if (events.stream().anyMatch(e -> Objects.equals(e.getProcessingStatus(), want))) {
                return names[i];
            }
        }
        return "UNKNOWN";
    }

    private static List<WsPaymentEvent> byState(List<WsPaymentEvent> events, String state) {
        return events.stream().filter(e -> state.equals(e.getTradeState())).toList();
    }

    private static boolean isProcessed(WsPaymentEvent e) {
        return Objects.equals(e.getProcessingStatus(), P_PROCESSED);
    }

    private static boolean isReconciliation(WsPaymentEvent e) {
        return Objects.equals(e.getProcessingStatus(), P_RECONCILIATION);
    }

    private static boolean inTime(WsPaymentEvent e, WsPayment payment) {
        return RechargePayExpire.paidInTime(e.getPaySuccessTime(), payment.getPayExpireTime());
    }

    private static boolean hasInTimeSuccess(List<WsPaymentEvent> successes, WsPayment payment) {
        return successes.stream().anyMatch(e -> inTime(e, payment));
    }

    /** 恢复路径：同一非空授权组键 + 审批人齐备，且处理态只允许 RETRY_WAIT/PROCESSING。 */
    private static boolean isApprovedRecoveryGroup(List<WsPaymentEvent> successes) {
        String group = successes.get(0).getRecoveryApprovalGroupKey();
        if (group == null || group.isEmpty()) {
            return false;
        }
        return successes.stream().allMatch(e ->
                group.equals(e.getRecoveryApprovalGroupKey())
                        && e.getRecoveryApprovedBy() != null
                        && (Objects.equals(e.getProcessingStatus(), P_RETRY_WAIT)
                        || Objects.equals(e.getProcessingStatus(), P_PROCESSING)));
    }
}

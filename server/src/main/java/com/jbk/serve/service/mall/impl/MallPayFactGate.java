package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.data.mall.po.WsMallOrder;
import com.jbk.tool.data.mall.po.WsMallPayment;
import com.jbk.tool.data.mall.po.WsMallPaymentFact;
import com.jbk.tool.utils.DateUtils;

/**
 * 商城支付事实共键校验器（E2E-09 S2 R2）。
 *
 * <p>事务A（落事实并置支付单成功）与事务B（实销并推进订单）刻意分处两个事务，中间隔着
 * 一次提交。正因为分离，**事务B 不能信任事务A 曾经校验过**——两段之间支付单可能被改、
 * 被逻辑删除、被别的交易号置成功。所以判据必须只有一份、两段共用，谁也不许省。</p>
 *
 * <p>订单与支付单一律用 {@code selectByOrderNoIncludingDeleted} 原样读（绕过逻辑删除
 * 过滤），是为了让"被删了"成为一个可判定的事实而不是"查不到"。代价是本校验器必须
 * 自己显式核 DATA_STATUS——漏这一步，逻辑删除的订单也能把支付单推成成功。</p>
 *
 * @author dakang
 * @since 2026-08-09
 */
final class MallPayFactGate {

    private MallPayFactGate() {
    }

    /**
     * 基础共键：存在、未删除、共键一致、来源与金额币种一致、交易号与成功时间合法。
     *
     * @return null 表示通过；否则为可落进 LAST_ERROR 的拒绝原因
     */
    static String linkMismatch(Long factOrderId, Long factPaymentId, Integer paySource,
                               Long payAmountFen, String currency, String transactionId,
                               String paySuccessTime, WsMallOrder order, WsMallPayment payment) {
        if (ObjectUtil.isNull(payment) || ObjectUtil.isNull(order)) {
            return "订单号未关联到商城订单或支付单";
        }
        if (!ObjectUtil.equal(order.getDataStatus(), 0)) {
            return "订单已被删除，不得推进支付";
        }
        if (!ObjectUtil.equal(payment.getDataStatus(), 0)) {
            return "支付单已被删除，不得推进支付";
        }
        if (!ObjectUtil.equal(payment.getOrderId(), order.getId())) {
            return "支付单与订单共键不一致";
        }
        if (ObjectUtil.isNotNull(factOrderId) && !ObjectUtil.equal(factOrderId, order.getId())) {
            return "事实订单ID与订单号指向不一致";
        }
        if (ObjectUtil.isNotNull(factPaymentId)
                && !ObjectUtil.equal(factPaymentId, payment.getId())) {
            return "事实支付单ID与订单号指向不一致";
        }
        if (!ObjectUtil.equal(paySource, payment.getPaySource())) {
            return "支付来源与支付单不一致";
        }
        if (ObjectUtil.isNull(payAmountFen)
                || !ObjectUtil.equal(payAmountFen, payment.getPayAmountFen())
                || !ObjectUtil.equal(payAmountFen, order.getOrderAmountFen())) {
            return "支付金额与订单/支付单不一致";
        }
        if (!ObjectUtil.equal(currency, payment.getCurrency())) {
            return "支付币种与支付单不一致";
        }
        if (StrUtil.isBlank(transactionId)) {
            return "成功事实缺交易号";
        }
        if (!DateUtils.isCanonicalBusinessTime(paySuccessTime)) {
            // 14 位数字不等于真实时间：20260230/00000000000000 这类值一旦写进支付单，
            // 之后没有任何环节还能发现它是假的
            return "成功事实的支付时间不是合法业务时间";
        }
        return payWindowMismatch(paySuccessTime, order, payment);
    }

    /**
     * 付款资格：成功时间必须落在订单自己冻结的付款窗 [CREATE_TIME, PAY_EXPIRE_TIME] 内。
     *
     * <p>"是不是真实日历时刻"只排除了伪造，排除不了**错窗**。订单建单时已经把创建时间与
     * 付款截止时间一并冻结，自动推进的资格因此完全可由现有字段判定，不需要任何产品口径：
     * 早于创建时间的支付不可能属于这张单，晚于截止时间的支付属于超时后到账，
     * 该由人工决定是退款还是补发，不能由平台自动扣掉库存发货。</p>
     *
     * <p>超时关单 Worker 每分钟才扫一轮，"已过期但还没被关掉"的窗口是常态而非异常。
     * 缺了这道判据，那段窗口里的支付会一路走到实销。</p>
     *
     * <p><b>下界是零容差的跨时钟比较</b>：paySuccessTime 由支付渠道给，createTime 是平台
     * 自己的时钟。上界有整个付款窗做余量，下界一秒都没有。当前唯一的生产者 Pay-Sim 与
     * 平台同进程同时钟，因此不可达；接真实微信支付时若发现渠道时钟慢于平台导致合法支付
     * 被判"早于建单"，那是一次需要显式决定容差的产品判断，不要在这里悄悄放宽。</p>
     */
    private static String payWindowMismatch(String paySuccessTime, WsMallOrder order,
                                            WsMallPayment payment) {
        String createTime = order.getCreateTime();
        String deadline = order.getPayExpireTime();
        if (!DateUtils.isCanonicalBusinessTime(createTime)) {
            return "订单创建时间非法，无法判定付款资格";
        }
        if (!DateUtils.isCanonicalBusinessTime(deadline)
                || !DateUtils.isCanonicalBusinessTime(payment.getPayExpireTime())) {
            return "付款截止时间非法，无法判定付款资格";
        }
        if (!ObjectUtil.equal(deadline, payment.getPayExpireTime())) {
            // 两处截止时间本由同一个变量写入，不一致即证明有一侧被改过
            return "订单与支付单的付款截止时间不一致";
        }
        // 三个串都已确认是 14 位规范形态：等宽定长下字典序即时间序，无需再解析一次
        if (paySuccessTime.compareTo(createTime) < 0) {
            return "支付成功时间早于订单创建时间";
        }
        if (paySuccessTime.compareTo(deadline) > 0) {
            return "支付成功时间晚于付款截止时间";
        }
        return null;
    }

    /**
     * 事务B 追加判据：支付单必须**精确处于本事实对应的成功态**。
     *
     * <p>不是"支付单成功就行"——必须是被这一条事实推成功的那一次：交易号、成功时间、
     * 来源三者都对得上。否则说明两段之间支付单被另一笔交易改过，此刻实销等于把货
     * 记到错误的那笔钱上。</p>
     */
    static String settleMismatch(WsMallPaymentFact fact, WsMallPayment payment) {
        if (!ObjectUtil.equal(payment.getPayStatus(), MallEnum.PayStatus.SUCCESS.getValue())) {
            return "支付单未处于成功态，不得实销";
        }
        if (!ObjectUtil.equal(payment.getTransactionId(), fact.getTransactionId())) {
            return "支付单交易号与本事实不一致";
        }
        if (!ObjectUtil.equal(payment.getPaySuccessTime(), fact.getPaySuccessTime())) {
            return "支付单成功时间与本事实不一致";
        }
        if (!ObjectUtil.equal(payment.getPaySource(), fact.getPaySource())) {
            return "支付单来源与本事实不一致";
        }
        return null;
    }

    /**
     * 订单是否处于"已支付及其下游"的状态（S3 履约中/已完成同属推进后）。
     *
     * <p>白名单而非黑名单：新增状态默认落在"不相容"一侧转人工。S4 引入售后中之后，
     * 一条迟到的支付成功事实撞上售后中订单该不该算幂等完成，是个业务判断，
     * 必须那时显式回答，不能靠这里悄悄放行。</p>
     */
    static boolean isAdvancedBeyondPay(WsMallOrder order) {
        Integer status = order.getOrderStatus();
        return ObjectUtil.equal(status, MallEnum.OrderStatus.PAID.getValue())
                || ObjectUtil.equal(status, MallEnum.OrderStatus.FULFILLING.getValue())
                || ObjectUtil.equal(status, MallEnum.OrderStatus.COMPLETED.getValue());
    }
}

package com.jbk.serve.service.mini.recharge.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.service.mini.recharge.IRechargeCreditTx;
import com.jbk.serve.service.mini.recharge.IRechargeCreditFailureTx;
import com.jbk.serve.service.mini.recharge.IRechargeIssueTx;
import com.jbk.serve.service.mini.recharge.IRechargePayCloseTx;
import com.jbk.serve.service.mini.recharge.IRechargePayConfirmTx;
import com.jbk.serve.service.mini.recharge.IRechargePayFactService;
import com.jbk.serve.service.mini.recharge.RechargePayExpire;
import com.jbk.serve.service.mini.recharge.RechargePayStatus;
import com.jbk.serve.service.mini.recharge.RechargeSnapshot;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsPaymentEvent;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 支付事实处理编排（L2-T）。
 *
 * <p>固定顺序：①CAS 认领事实（并发下只有一个处理者进得来）→ ②跨全状态重读订单与支付单并逐项校验
 * → ③事务A 认定支付事实 → ④事务B 入账 → ⑤事实标记已处理。</p>
 *
 * <p><b>钱不会静默消失</b>：任何一步判定为不安全，都走「订单转 6异常 + 事实转 5待对账」，
 * 留下完整线索等人工，绝不 catch 后 return 了事。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RechargePayFactServiceImpl implements IRechargePayFactService {

    private static final int ORDER_TYPE_RECHARGE = 2;
    private static final String CURRENCY_CNY = "CNY";

    private final RechargeCreditMapper creditMapper;
    private final RechargeIdentityMapper identityMapper;
    private final IRechargePayConfirmTx confirmTx;
    private final IRechargePayCloseTx closeTx;
    private final IRechargeCreditTx creditTx;
    private final IRechargeCreditFailureTx failureTx;
    private final IRechargeIssueTx issueTx;

    @Override
    public Outcome process(Long eventId) {
        String now = DateUtils.time();
        WsPaymentEvent event = creditMapper.selectEventById(eventId);
        if (event == null) {
            return new Outcome("MISMATCH", "支付事实不存在");
        }
        if (!ObjectUtil.equals(event.getDataStatus(), 0)) {
            return new Outcome("MISMATCH", "支付事实已被逻辑删除，拒绝处理");
        }
        String state = event.getTradeState();
        if (RechargePayStatus.NOTPAY.equals(state) || RechargePayStatus.CLOSED.equals(state)) {
            // 契约 §6.2 第 5/6 条：查单事实走独立分支，永不触碰事务 B（§6.3「NOTPAY/CLOSED 永不进入权益 Worker」）
            return processQueryFact(event, now);
        }
        if (!RechargePayStatus.SUCCESS.equals(state)) {
            // §6.2 第 7 条：其他支付方状态只保存可信事实并标记待对账，不得修改 payment/order/card/flow。
            // 这里绝不能返回"跳过"了事——一条无法解释的支付方状态被静默放着，就没人会去看它。
            return parkUnknownState(event, now);
        }
        // CAS 认领：并发下只有一个处理者能把 1待处理 改成 2处理中
        if (creditMapper.claimEvent(eventId, now, DateUtils.plusSeconds(now, 300)) != 1) {
            // 认领不到分三种：已处理完（重复通知的常态）、待对账、正被别人处理。
            // 必须分开答——把"已经入过账了"和"暂时没抢到"混成一句，会让重复通知看起来像失败。
            WsPaymentEvent current = creditMapper.selectEventById(eventId);
            Integer status = current == null ? null : current.getProcessingStatus();
            if (ObjectUtil.equals(status, RechargePayStatus.P_PROCESSED)) {
                return new Outcome("ALREADY", "该支付事实此前已处理完成");
            }
            if (ObjectUtil.equals(status, RechargePayStatus.P_RECONCILIATION)) {
                return new Outcome("RECONCILIATION", "该支付事实待人工对账");
            }
            return new Outcome("SKIPPED", "事实正被其他处理者处理");
        }

        try {
            WsPaymentEvent claimed = creditMapper.selectEventById(eventId);
            if (claimed == null || !ObjectUtil.equals(claimed.getProcessingStatus(), 2)) {
                return new Outcome("MISMATCH", "支付事实认领后状态丢失");
            }
            return handleClaimed(claimed, now);
        } catch (RuntimeException e) {
            // 认领后的任何意外都必须留痕转人工——钱已经收了，不能让异常把事实吞掉
            log.error("支付事实处理异常，转人工对账：eventId={} orderNo={}", eventId, event.getOrderNo(), e);
            recordFailure(event, now, false, "处理异常：" + e.getMessage());
            return new Outcome("RECONCILIATION", "处理异常已转人工对账：" + e.getMessage());
        }
    }

    /**
     * 共键关联（契约 §6.2 第 3 条前半段）：把事实定位到唯一的 order/payment 并逐项核对。
     * SUCCESS 与 NOTPAY/CLOSED 共用同一份判定与同一批原因串——两条路径若各写一套，
     * 迟早会出现"成功路径拒绝、查单路径放行"的不对称漏洞。
     *
     * @param reason 非 null 即校验失败原因；此时 order/payment 不可使用
     */
    private record Linked(WsOrder order, WsPayment payment, String reason) {
        static Linked fail(String reason) {
            return new Linked(null, null, reason);
        }
    }

    private Linked link(WsPaymentEvent event) {
        List<WsOrder> orders = identityMapper.selectOrdersByOrderNoIncludingDeleted(event.getOrderNo());
        if (orders == null || orders.size() != 1) {
            return Linked.fail("订单不存在或订单号重复");
        }
        WsOrder order = orders.get(0);
        if (!ObjectUtil.equals(order.getDataStatus(), 0)
                || !ObjectUtil.equals(order.getOrderType(), ORDER_TYPE_RECHARGE)) {
            return Linked.fail("订单已删除或类型不符");
        }
        List<WsPayment> payments = identityMapper.selectPaymentsByOrderIdIncludingDeleted(order.getId());
        if (payments == null || payments.size() != 1) {
            return Linked.fail("支付单必须恰好一条");
        }
        WsPayment payment = payments.get(0);
        if (!ObjectUtil.equals(payment.getDataStatus(), 0)) {
            return Linked.fail("支付单已被逻辑删除");
        }
        // 事实与支付单的共键：事实必须明确指向这一单这一支付单，为空即「未完成可信关联」
        if (!ObjectUtil.equals(event.getOrderId(), order.getId())
                || !ObjectUtil.equals(event.getPaymentId(), payment.getId())) {
            return Linked.fail("支付事实与订单/支付单共键错位");
        }
        if (!ObjectUtil.equals(event.getPaySource(), payment.getPaySource())) {
            return Linked.fail("支付事实来源与支付单不一致");
        }
        return new Linked(order, payment, null);
    }

    private Outcome handleClaimed(WsPaymentEvent event, String now) {
        Linked linked = link(event);
        if (linked.reason() != null) {
            return reconcile(event, now, linked.reason());
        }
        WsOrder order = linked.order();
        WsPayment payment = linked.payment();
        // 金额三方一致：事实 = 支付单 = 订单。差一分都不入账。
        if (!ObjectUtil.equals(event.getPayAmount(), payment.getPayAmount())
                || !ObjectUtil.equals(payment.getPayAmount(), order.getOrderAmount())) {
            return reconcile(event, now, "支付金额与订单/支付单不一致");
        }
        if (!CURRENCY_CNY.equals(event.getCurrency())) {
            return reconcile(event, now, "币种非 CNY");
        }
        if (StrUtil.isBlank(event.getTransactionId()) || StrUtil.isBlank(event.getPaySuccessTime())) {
            return reconcile(event, now, "成功事实缺少交易号或成功时间");
        }
        // 超时支付：钱收到了但已过付款截止时间，不能当正常充值入账，转人工决定退款还是补入
        if (!RechargePayExpire.paidInTime(event.getPaySuccessTime(), payment.getPayExpireTime())) {
            return reconcile(event, now, "支付成功时间晚于付款截止时间");
        }

        RechargeSnapshot.Parsed snap;
        try {
            snap = RechargeSnapshot.parse(order.getPackageSnap());
        } catch (RuntimeException e) {
            return reconcile(event, now, "订单快照错位：" + e.getMessage());
        }
        if (!StrUtil.equals(snap.capturedTime(), order.getCreateTime())) {
            return reconcile(event, now, "快照采集时间与订单创建时间不一致");
        }

        IRechargePayConfirmTx.Result confirmed = confirmTx.confirm(order, payment,
                event.getTransactionId(), event.getPaySuccessTime(), now);
        if (confirmed == IRechargePayConfirmTx.Result.MISMATCH) {
            return reconcile(event, now, "支付单/订单状态错位，无法认定支付事实");
        }

        // L2-A 事实路由（决策 A2）：CARD_ID 为空 = 首次购卡订单 → 发卡事务；否则走既有充值入账事务。
        // 卡相关校验都在各自事务的锁内进行；上面的共键、金额、快照 capturedTime 锚点检查两条路共用。
        // 已完成的购卡单 CARD_ID 已被回填，其重放事实自然走 creditTx 的只读幂等核验分支——
        // 该分支的完成态账本核验对 purchase 快照按 NewCardExpiry 下限判定（见 RechargeLedgerVerifier）。
        if (order.getCardId() == null) {
            return handleClaimedPurchase(event, now);
        }

        IRechargeCreditTx.CreditResult credited;
        try {
            credited = creditTx.credit(event.getId(), now);
        } catch (RuntimeException e) {
            recordFailure(event, now, false, "权益事务回滚：" + e.getMessage());
            return new Outcome("RECONCILIATION", "权益事务失败，已按锁内现状落痕：" + e.getMessage());
        }

        switch (credited.outcome()) {
            case CREDITED -> {
                return new Outcome("CREDITED", "入账完成");
            }
            case ALREADY -> {
                return new Outcome("ALREADY", "该订单已完成且账本核验一致");
            }
            case RETRY_CARD_FROZEN -> {
                // 可恢复：订单保持 2，事实转待重试。绝不推进到 6——那会把可自动恢复的单堆进人工队列。
                failureTx.record(event.getId(), true, credited.reason(), now);
                log.warn("充值入账可恢复失败，订单保持待入账：orderNo={} 原因={}",
                        order.getOrderNo(), credited.reason());
                return new Outcome("RETRY", credited.reason());
            }
            default -> {
                recordFailure(event, now, false, credited.reason());
                return new Outcome("RECONCILIATION", credited.reason());
            }
        }
    }

    // ------------------------------------------------------------------
    // L2-A 首次购卡（决策 A2/A3/A4）
    // ------------------------------------------------------------------

    /**
     * 首次购卡成功事实：进入发卡事务（建卡 + 首充入账 + 回填 CARD_ID + 订单 2→4，整体原子）。
     * 结果语义与充值路径同源：不可恢复失败通过 {@code issueTx.recordFailure} 落痕为
     * payment 2/order 6 无卡转人工（决策 A2），绝不 catch 后静默返回。
     */
    private Outcome handleClaimedPurchase(WsPaymentEvent event, String now) {
        IRechargeCreditTx.CreditResult issued;
        try {
            issued = issueTx.issue(event.getId(), now);
        } catch (RuntimeException e) {
            recordIssueFailure(event, now, false, "发卡事务回滚：" + e.getMessage());
            return new Outcome("RECONCILIATION", "发卡事务失败，已按锁内现状落痕：" + e.getMessage());
        }
        switch (issued.outcome()) {
            case CREDITED -> {
                return new Outcome("CREDITED", "发卡并首充入账完成");
            }
            case ALREADY -> {
                return new Outcome("ALREADY", "该购卡订单已发卡且账本核验一致");
            }
            case RETRY_CARD_FROZEN -> {
                // 发卡路径没有既有卡可冻结，正常不会出现；防御性按可恢复处理，订单保持 2 无卡等重试
                recordIssueFailure(event, now, true, issued.reason());
                return new Outcome("RETRY", issued.reason());
            }
            default -> {
                recordIssueFailure(event, now, false, issued.reason());
                return new Outcome("RECONCILIATION", issued.reason());
            }
        }
    }

    /** 发卡失败落痕：走 purchase 专用落痕事务（购卡单无卡，不能进 {@code failureTx} 的锁卡装载）。 */
    private void recordIssueFailure(WsPaymentEvent event, String now, boolean retryable, String reason) {
        try {
            issueTx.recordFailure(event.getId(), retryable, reason, now);
        } catch (RuntimeException e) {
            // 与 recordFailure 同一兜底纪律：无法形成完整锁序时只精确更新当前已认领事件，
            // 不猜测订单，也不覆盖任何已完成状态。
            log.error("发卡失败落痕无法关联完整业务对象：eventId={} orderNo={}", event.getId(), event.getOrderNo(), e);
            int changed = creditMapper.markEventReconciliation(event.getId(), 2,
                    StrUtil.maxLength(reason, 500), now);
            if (changed != 1) {
                throw new IllegalStateException("发卡失败落痕影响行数异常", e);
            }
        }
    }

    // ------------------------------------------------------------------
    // 查单事实（NOTPAY / CLOSED）：契约 §6.2 第 5/6 条、§7.1
    // ------------------------------------------------------------------

    /**
     * 处理一条支付方查单事实。<b>与 SUCCESS 走同一个入口、同一套共键校验</b>，
     * 只在最后的状态推进上分叉——将来接真实微信主动查单时不需要再写一条路径。
     *
     * <p>本方法不引用 {@code creditTx}/{@code failureTx}：查单事实永远不进入权益事务 B，
     * 也不会把订单推到 6。哪怕判定失败，动的也只有事实自己的处理态。</p>
     */
    private Outcome processQueryFact(WsPaymentEvent event, String now) {
        if (creditMapper.claimQueryEvent(event.getId(), now, DateUtils.plusSeconds(now, 300)) != 1) {
            WsPaymentEvent current = creditMapper.selectEventById(event.getId());
            Integer status = current == null ? null : current.getProcessingStatus();
            if (ObjectUtil.equals(status, RechargePayStatus.P_PROCESSED)) {
                return new Outcome("ALREADY", "该查单事实此前已处理完成");
            }
            if (ObjectUtil.equals(status, RechargePayStatus.P_RECONCILIATION)) {
                return new Outcome("RECONCILIATION", "该查单事实待人工对账");
            }
            return new Outcome("SKIPPED", "查单事实正被其他处理者处理");
        }
        try {
            WsPaymentEvent claimed = creditMapper.selectEventById(event.getId());
            if (claimed == null
                    || !ObjectUtil.equals(claimed.getProcessingStatus(), RechargePayStatus.P_PROCESSING)) {
                return new Outcome("MISMATCH", "查单事实认领后状态丢失");
            }
            return handleClaimedQuery(claimed, now);
        } catch (RuntimeException e) {
            // 认领后的任何意外都必须留痕：查单事实虽然不涉及已收款项，
            // 但一条停在"处理中"的事实会让后续查单永远认领不到，订单卡死在待支付
            log.error("查单事实处理异常，转人工对账：eventId={} orderNo={}", event.getId(), event.getOrderNo(), e);
            parkQuery(event.getId(), now, "查单事实处理异常：" + e.getMessage());
            return new Outcome("RECONCILIATION", "查单事实处理异常已转人工对账：" + e.getMessage());
        }
    }

    private Outcome handleClaimedQuery(WsPaymentEvent event, String now) {
        Linked linked = link(event);
        if (linked.reason() != null) {
            return queryReconcile(event, now, linked.reason());
        }
        WsOrder order = linked.order();
        WsPayment payment = linked.payment();

        // 非成功事实允许字段为空，但支付方一旦返回金额/币种就必须与内部精确一致（§9.1 同口径）。
        // 反过来不成立：这里绝不用内部订单金额去补造外部事实（§5.3 明令禁止）。
        if (event.getPayAmount() != null
                && (!ObjectUtil.equals(event.getPayAmount(), payment.getPayAmount())
                || !ObjectUtil.equals(payment.getPayAmount(), order.getOrderAmount()))) {
            return queryReconcile(event, now, "查单事实金额与订单/支付单不一致");
        }
        if (event.getCurrency() != null && !CURRENCY_CNY.equals(event.getCurrency())) {
            return queryReconcile(event, now, "查单事实币种非 CNY");
        }
        // 非成功事实不得携带成功语义字段——带了就说明支付方给的状态与内容自相矛盾
        if (StrUtil.isNotBlank(event.getTransactionId()) || StrUtil.isNotBlank(event.getPaySuccessTime())) {
            return queryReconcile(event, now, "非成功事实不得携带交易号或成功时间");
        }

        // §6.2 第 3 条：任何渠道的事实在关联时都必须重验付款截止时间与不可变资格快照。
        // 关单尤其依赖它——PAY_EXPIRE_TIME 若可被改写，"支付方认为已过期"这个前提就不可信了。
        RechargeSnapshot.Parsed snap;
        try {
            snap = RechargeSnapshot.parse(order.getPackageSnap());
        } catch (RuntimeException e) {
            return queryReconcile(event, now, "订单快照错位：" + e.getMessage());
        }
        if (!StrUtil.equals(snap.capturedTime(), order.getCreateTime())) {
            return queryReconcile(event, now, "快照采集时间与订单创建时间不一致");
        }
        if (StrUtil.isBlank(payment.getPayExpireTime())
                || !StrUtil.equals(payment.getPayExpireTime(),
                RechargePayExpire.compute(snap.capturedTime(), snap.expireTimeAtCreate()))) {
            return queryReconcile(event, now, "付款截止时间与资格快照不一致");
        }

        int pay = payment.getPayStatus() == null ? -1 : payment.getPayStatus();
        int ord = order.getOrderStatus() == null ? -1 : order.getOrderStatus();

        if (RechargePayStatus.NOTPAY.equals(event.getTradeState())) {
            // §6.2 第 5 条：仅 payment 1/order 1 保持不变并置 PROCESSED；已进入其他状态一律待对账，不得回退。
            if (pay != RechargePayStatus.PAY_PENDING || ord != RechargePayStatus.ORDER_PENDING) {
                return queryReconcile(event, now,
                        "支付方返回未支付但内部已推进至 payment " + pay + "/order " + ord);
            }
            if (creditMapper.markEventProcessed(event.getId(), RechargePayStatus.P_PROCESSING, now) != 1) {
                return new Outcome("MISMATCH", "查单事实收敛影响行数异常");
            }
            return new Outcome("NOTPAY", "支付方确认未支付，订单保持待支付");
        }

        // CLOSED：§6.2 第 6 条 + §7.1「仅支付方确认 CLOSED 后才进入 payment 4/order 5」
        IRechargePayCloseTx.Result closed = closeTx.close(order.getId(), payment.getId(), event.getId(), now);
        return switch (closed) {
            case CLOSED -> new Outcome("CLOSED", "支付方确认关闭，订单已关闭");
            case ALREADY -> new Outcome("ALREADY", "订单此前已按支付方关闭结果收敛");
            case MISMATCH -> queryReconcile(event, now,
                    "支付方返回已关闭但内部状态为 payment " + pay + "/order " + ord + "，拒绝关单");
        };
    }

    /** 查单事实转人工：<b>只动事实自己</b>，绝不修改 payment/order/card/flow（§6.2 第 7 条）。 */
    private Outcome queryReconcile(WsPaymentEvent event, String now, String reason) {
        parkQuery(event.getId(), now, reason);
        return new Outcome("RECONCILIATION", reason);
    }

    private void parkQuery(Long eventId, String now, String reason) {
        int changed = creditMapper.markEventReconciliation(eventId, RechargePayStatus.P_PROCESSING,
                StrUtil.maxLength(reason, 500), now);
        if (changed != 1) {
            // 认领时是 2处理中，此刻却改不动：状态被并发改写，宁可抛出让上层告警，也不假装已留痕
            throw new IllegalStateException("查单事实待对账落痕影响行数异常，eventId=" + eventId);
        }
    }

    /** 未登记的支付方状态：原地转待对账，不认领、不推进任何业务对象。 */
    private Outcome parkUnknownState(WsPaymentEvent event, String now) {
        Integer before = event.getProcessingStatus();
        if (ObjectUtil.equals(before, RechargePayStatus.P_RECONCILIATION)) {
            return new Outcome("RECONCILIATION", "未登记的支付方状态，此前已转人工对账");
        }
        int changed = creditMapper.markEventReconciliation(event.getId(), before,
                StrUtil.maxLength("未登记的支付方状态：" + event.getTradeState(), 500), now);
        if (changed != 1) {
            return new Outcome("MISMATCH", "未登记支付方状态落痕影响行数异常");
        }
        return new Outcome("RECONCILIATION", "未登记的支付方状态已转人工对账：" + event.getTradeState());
    }

    /** 转人工：订单转 6异常 + 事实转 5待对账，两处都留下同一句原因。 */
    private Outcome reconcile(WsPaymentEvent event, String now, String reason) {
        recordFailure(event, now, false, reason);
        return new Outcome("RECONCILIATION", reason);
    }

    private void recordFailure(WsPaymentEvent event, String now, boolean retryable, String reason) {
        try {
            failureTx.record(event.getId(), retryable, reason, now);
        } catch (RuntimeException e) {
            // 无法形成完整锁序通常意味着外部事实尚未可信关联；只允许精确更新当前已认领事件，
            // 不猜测订单，也不覆盖任何已完成状态。
            log.error("严格失败落痕无法关联完整业务对象：eventId={} orderNo={}", event.getId(), event.getOrderNo(), e);
            int changed = creditMapper.markEventReconciliation(event.getId(), 2,
                    StrUtil.maxLength(reason, 500), now);
            if (changed != 1) {
                throw new IllegalStateException("支付事实失败落痕影响行数异常", e);
            }
        }
    }

}

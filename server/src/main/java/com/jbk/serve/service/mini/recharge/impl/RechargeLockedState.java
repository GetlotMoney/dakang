package com.jbk.serve.service.mini.recharge.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.serve.service.mini.recharge.RechargeCredit;
import com.jbk.serve.service.mini.recharge.RechargePayExpire;
import com.jbk.serve.service.mini.recharge.RechargePayStatus;
import com.jbk.serve.service.mini.recharge.RechargeSnapshot;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsPaymentEvent;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 事务 B 与失败落痕事务共用的锁内视图。
 *
 * <p>调用方必须已开启 Spring 事务。本组件严格按 payment → order → card → 同单事件组 →
 * 目标卡流水的顺序取得行锁，避免入账与失败落痕使用不同锁序形成竞态或死锁。</p>
 */
@Component
@RequiredArgsConstructor
final class RechargeLockedState {

    private static final int ORDER_TYPE_RECHARGE = 2;
    private static final int PAY_WAY_WECHAT = 1;
    private static final int PAY_SUCCESS = 2;

    private final RechargeCreditMapper mapper;

    Locked load(Long eventId) {
        WsPaymentEvent hint = mapper.selectEventById(eventId);
        if (hint == null || hint.getPaymentId() == null) {
            throw new JbkException("支付事实缺失或未关联支付单");
        }
        WsPayment payment = mapper.lockPayment(hint.getPaymentId());
        if (payment == null || payment.getOrderId() == null) {
            throw new JbkException("支付单缺失或未关联订单");
        }
        WsOrder order = mapper.lockOrder(payment.getOrderId());
        if (order == null || order.getCardId() == null) {
            throw new JbkException("订单缺失或未关联目标卡");
        }
        // 转正单（D-416，审计 P1-1）：在锁卡前先锁用户行，锁序 payment→order→user→card
        // 与首购发卡（RechargeIssueTxImpl 决策 A4）完全同序——它是「一人一张付费卡」在
        // 并发下的唯一串行化锚，两张赠卡并发转正、转正与首购并发都在这里排队。
        // 快照仅作提示（解析失败不在此拦截，verify 阶段会正式拒绝）；普通充值不加用户锁。
        if (promoteHint(order.getPackageSnap())) {
            if (mapper.lockUserRow(order.getUserId()) == null) {
                throw new JbkException("用户不存在，无法入账");
            }
        }
        WsCard card = mapper.lockCard(order.getCardId());
        List<WsPaymentEvent> events = mapper.lockEventsByOrderNo(order.getOrderNo());
        List<WsWalletFlow> flows = mapper.lockCardFlows(order.getCardId());
        WsPaymentEvent event = events.stream()
                .filter(row -> ObjectUtil.equals(row.getId(), eventId))
                .findFirst()
                .orElseThrow(() -> new JbkException("支付事实与订单号错位"));
        return new Locked(payment, order, card, event, List.copyOf(events), List.copyOf(flows));
    }

    /** 锁序提示：快照能解析且带转正标志才需要用户锁；解析异常按非转正处理（verify 兜底拒绝）。 */
    private boolean promoteHint(String packageSnap) {
        try {
            return RechargeSnapshot.parse(packageSnap).promoteToPermanent();
        } catch (Exception unparsable) {
            return false;
        }
    }

    Verified verifyForCredit(Locked locked) {
        return verify(locked, true);
    }

    Verified verifyForCompleted(Locked locked) {
        return verify(locked, false);
    }

    private Verified verify(Locked locked, boolean requireClaimedCurrentEvent) {
        WsPayment payment = locked.payment();
        WsOrder order = locked.order();
        WsPaymentEvent event = locked.event();
        if (!ObjectUtil.equals(payment.getDataStatus(), 0)
                || !ObjectUtil.equals(order.getDataStatus(), 0)
                || locked.card() == null) {
            throw new JbkException("支付单、订单或目标卡不可用");
        }
        if (!ObjectUtil.equals(order.getOrderType(), ORDER_TYPE_RECHARGE)
                || !ObjectUtil.equals(order.getPayWay(), PAY_WAY_WECHAT)
                || !ObjectUtil.equals(payment.getPayStatus(), PAY_SUCCESS)) {
            throw new JbkException("支付单或订单状态不允许入账");
        }
        if (!ObjectUtil.equals(payment.getOrderId(), order.getId())
                || !StrUtil.equals(payment.getOrderNo(), order.getOrderNo())
                || !ObjectUtil.equals(payment.getPayAmount(), order.getOrderAmount())) {
            throw new JbkException("支付单与订单共键错位");
        }
        if (!RechargePayStatus.SUCCESS.equals(event.getTradeState())
                || (requireClaimedCurrentEvent && !ObjectUtil.equals(event.getProcessingStatus(), 2))) {
            throw new JbkException("当前支付事实不是可处理的成功事实");
        }
        RechargeSnapshot.Parsed snap = RechargeSnapshot.parse(order.getPackageSnap());
        if (!StrUtil.equals(snap.capturedTime(), order.getCreateTime())
                || !StrUtil.equals(snap.packageId(), String.valueOf(order.getPackageId()))
                || !ObjectUtil.equals(snap.payAmount(), order.getOrderAmount())) {
            throw new JbkException("订单快照与订单共键错位");
        }
        // 转正单（D-416）按永久卡口径重算：创单时就是这么算的，重验必须同式同输入
        String expectedExpire = RechargePayExpire.compute(snap.capturedTime(),
                snap.promoteToPermanent() ? null : snap.expireTimeAtCreate());
        if (!StrUtil.equals(payment.getPayExpireTime(), expectedExpire)) {
            throw new JbkException("付款截止时间与不可变资格快照不一致");
        }
        if (StrUtil.isBlank(payment.getTransactionId()) || StrUtil.isBlank(payment.getPaySuccessTime())
                || !RechargePayExpire.paidInTime(payment.getPaySuccessTime(), payment.getPayExpireTime())) {
            throw new JbkException("权威支付事实缺失或超过付款截止时间");
        }
        verifySuccessGroup(locked, payment, order);
        return new Verified(locked, snap, RechargeCredit.of(snap));
    }

    private void verifySuccessGroup(Locked locked, WsPayment payment, WsOrder order) {
        for (WsPaymentEvent row : locked.events()) {
            if (!ObjectUtil.equals(row.getDataStatus(), 0)) {
                throw new JbkException("存在被逻辑删除的支付事实");
            }
            if (!RechargePayStatus.SUCCESS.equals(row.getTradeState())) {
                continue;
            }
            if (!ObjectUtil.equals(row.getOrderId(), order.getId())
                    || !ObjectUtil.equals(row.getPaymentId(), payment.getId())
                    || !StrUtil.equals(row.getOrderNo(), order.getOrderNo())
                    || !ObjectUtil.equals(row.getPaySource(), payment.getPaySource())
                    || !ObjectUtil.equals(row.getPayAmount(), payment.getPayAmount())
                    || !"CNY".equals(row.getCurrency())
                    || !StrUtil.equals(row.getTransactionId(), payment.getTransactionId())
                    || !StrUtil.equals(row.getPaySuccessTime(), payment.getPaySuccessTime())) {
                throw new JbkException("同单成功支付事实组存在共键错位");
            }
            Integer status = row.getProcessingStatus();
            if (!ObjectUtil.equals(status, 1) && !ObjectUtil.equals(status, 2)
                    && !ObjectUtil.equals(status, 3) && !ObjectUtil.equals(status, 4)) {
                throw new JbkException("同支付事实组存在待对账或未知处理态");
            }
        }
    }

    record Locked(WsPayment payment, WsOrder order, WsCard card, WsPaymentEvent event,
                  List<WsPaymentEvent> events, List<WsWalletFlow> flows) {
    }

    record Verified(Locked locked, RechargeSnapshot.Parsed snapshot, RechargeCredit credit) {
    }
}

package com.jbk.serve.service.mini.recharge.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.serve.service.mini.recharge.IRechargeCreditTx;
import com.jbk.serve.service.mini.recharge.RechargeCredit;
import com.jbk.serve.service.mini.recharge.RechargeExpiry;
import com.jbk.serve.service.mini.card.WaterCardScope;
import com.jbk.serve.service.mini.recharge.RechargeSnapshot;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPaymentEvent;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事务 B 实现（L2 契约 v2 §6.4）。
 *
 * <p>顺序刻意如此：<b>锁卡 → 分类 → 计算 → 一条 CAS UPDATE 写全部权益 → 插带幂等键的流水 → 订单转 4</b>。</p>
 *
 * <p>两条不能动的设计：</p>
 * <ol>
 *   <li><b>入账幂等由数据库唯一键保证，不由应用层查重保证。</b>先加钱再插流水，撞键则整事务回滚，
 *       连同刚加的余额、水量与续期一起撤销。两个处理者同时进来也不可能双倍入账。</li>
 *   <li><b>权益取值只来自订单快照。</b>支付报文只负责证明「钱收到了」，给多少权益是下单那一刻定死的；
 *       续期基准是权威 {@code paySuccessTime} 而非处理时刻，否则异步延迟会凭空多送有效期。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeCreditTxImpl implements IRechargeCreditTx {

    /** 卡状态(1332)：1正常 2冻结 3已过期 4已注销。 */
    private static final int CARD_NORMAL = 1;
    private static final int CARD_FROZEN = 2;
    private static final int CARD_EXPIRED = 3;
    private static final int CARD_CANCELLED = 4;
    private static final int ORDER_PAID = 2;
    private static final int ORDER_FINISHED = 4;

    private final RechargeCreditMapper creditMapper;
    private final RechargeLockedState lockedState;
    private final RechargeLedgerVerifier ledgerVerifier;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CreditResult credit(Long eventId, String processingTime) {
        RechargeLockedState.Verified verified = lockedState.verifyForCredit(lockedState.load(eventId));
        RechargeLockedState.Locked locked = verified.locked();
        WsOrder order = locked.order();
        WsCard card = locked.card();
        RechargeCredit credit = verified.credit();

        if (ObjectUtil.equals(order.getOrderStatus(), ORDER_FINISHED)) {
            ledgerVerifier.requireAlreadyCredited(order, card, locked.flows(), credit,
                    verified.snapshot(), locked.payment().getPaySuccessTime());
            completeSuccessGroup(locked.events(), processingTime);
            return CreditResult.already();
        }
        if (!ObjectUtil.equals(order.getOrderStatus(), ORDER_PAID)) {
            return CreditResult.unrecoverable("订单状态不允许进入正常权益事务：" + order.getOrderStatus());
        }
        ledgerVerifier.requireNoRechargeFlow(order, card, locked.flows(),
                creditMapper.countRechargeFlowByOrderId(order.getId()),
                creditMapper.countFlowByBizKey(bizKey(order.getOrderNo())));

        // ── 步骤 4：锁卡后按处理时事实精确分类 ──
        if (!ObjectUtil.equals(card.getDataStatus(), 0)) {
            return CreditResult.unrecoverable("目标卡已被逻辑删除");
        }
        // 换主的卡绝不能入账：钱是 A 付的，权益不能落到 B 名下
        if (!ObjectUtil.equals(card.getUserId(), order.getUserId())) {
            return CreditResult.unrecoverable("目标卡归属已变更");
        }
        String scopeMismatch = checkScope(card, verified.snapshot());
        if (scopeMismatch != null) {
            return CreditResult.unrecoverable(scopeMismatch);
        }

        Integer status = card.getCardStatus();
        if (ObjectUtil.equals(status, CARD_FROZEN)) {
            // 可恢复：冻结是限制用卡，不是没收已付款项。订单保持 2 等待重试，不写任何权益。
            return CreditResult.retry("目标卡冻结中，等待解冻后重试入账");
        }
        if (ObjectUtil.equals(status, CARD_CANCELLED)) {
            return CreditResult.unrecoverable("目标卡已注销");
        }
        if (ObjectUtil.equals(status, CARD_EXPIRED)
                && !RechargeExpiry.naturallyExpired(card.getExpireTime(), processingTime)) {
            // 状态 3 但不是自然过期形成的（有效期为空或仍在未来）：数据自相矛盾，fail-closed
            return CreditResult.unrecoverable("目标卡状态为已过期但有效期不自洽");
        }
        if (!ObjectUtil.equals(status, CARD_NORMAL) && !ObjectUtil.equals(status, CARD_EXPIRED)) {
            return CreditResult.unrecoverable("目标卡状态不允许入账：" + status);
        }

        // ── 步骤 5：计算 credit 与预期 AFTER；有限卡算续期 ──
        long oldAmount = card.getBalanceAmount() == null ? 0L : card.getBalanceAmount();
        long oldMl = card.getBalanceMl() == null ? 0L : card.getBalanceMl();
        long afterAmount;
        long afterMl;
        try {
            afterAmount = Math.addExact(oldAmount, credit.amountFen());
            afterMl = Math.addExact(oldMl, credit.ml());
        } catch (ArithmeticException e) {
            return CreditResult.unrecoverable("入账后余额或水量溢出");
        }

        boolean cardFinite = StrUtil.isNotBlank(card.getExpireTime());
        boolean packageFinite = verified.snapshot().expireDays() != null;
        if (cardFinite != packageFinite) {
            // 创单时已校验过同类型；此刻不一致说明卡有效期在支付期间被改写
            return CreditResult.unrecoverable("水卡与套餐有效期类型在支付期间发生变化");
        }

        String newExpireTime = null;
        if (packageFinite) {
            newExpireTime = RechargeExpiry.extend(card.getExpireTime(), locked.payment().getPaySuccessTime(),
                    verified.snapshot().expireDays());
            if (RechargeExpiry.isAlreadyExpired(newExpireTime, processingTime)) {
                // 不可恢复：绝不写一段已经作废的权益，也不顺手把卡恢复成正常态
                return CreditResult.unrecoverable(
                        "续期后有效期 " + newExpireTime + " 不晚于处理时间 " + processingTime);
            }
        }

        // ── 步骤 6：一条 CAS UPDATE 写全部权益，前态任一变化即影响 0 行 ──
        int rows = packageFinite
                ? creditMapper.creditFiniteCard(card.getId(), credit.amountFen(), credit.ml(),
                        newExpireTime, order.getPackageId(), order.getPackageSnap(),
                        order.getUserId(), processingTime,
                        oldAmount, oldMl, card.getExpireTime(), card.getScopeJson())
                : creditMapper.creditPermanentCard(card.getId(), credit.amountFen(), credit.ml(),
                        order.getPackageId(), order.getPackageSnap(),
                        order.getUserId(), processingTime,
                        oldAmount, oldMl, card.getScopeJson());
        if (rows != 1) {
            // 锁内前态与 UPDATE 时不一致，只可能是并发写入；整事务回滚，绝不重试后半段
            throw new JbkException("入账失败：目标卡前态在事务内发生变化");
        }

        // ── 步骤 7：唯一流水。AFTER 用预期值而非重读，重读会把并发改动当成本次结果 ──
        if (creditMapper.insertRechargeFlow(card.getId(), order.getUserId(),
                credit.amountFen(), credit.ml(), afterAmount, afterMl,
                order.getId(), "充值入账 " + order.getOrderNo(),
                bizKey(order.getOrderNo()), processingTime) != 1) {
            throw new JbkException("充值流水插入影响行数异常");
        }

        // ── 步骤 8：订单转 4 ──
        if (creditMapper.markOrderFinished(order.getId(), processingTime) != 1) {
            throw new JbkException("订单状态非已支付，拒绝完成入账");
        }
        completeSuccessGroup(locked.events(), processingTime);
        log.info("充值入账完成：orderNo={} 余额 {}→{} 水量 {}→{} 有效期 {}→{}",
                order.getOrderNo(), oldAmount, afterAmount, oldMl, afterMl,
                card.getExpireTime(), newExpireTime);
        return CreditResult.credited();
    }

    /** 同一支付事实组必须与权益、流水、订单在本事务内一起收敛。 */
    private void completeSuccessGroup(java.util.List<WsPaymentEvent> events, String now) {
        for (WsPaymentEvent event : events) {
            if (!"SUCCESS".equals(event.getTradeState()) || ObjectUtil.equals(event.getProcessingStatus(), 3)) {
                continue;
            }
            Integer expected = event.getProcessingStatus();
            if (!ObjectUtil.equals(expected, 1) && !ObjectUtil.equals(expected, 2)
                    && !ObjectUtil.equals(expected, 4)) {
                throw new JbkException("成功支付事实组含不可收敛状态：" + expected);
            }
            if (creditMapper.markEventProcessed(event.getId(), expected, now) != 1) {
                throw new JbkException("支付事实组收敛影响行数异常");
            }
        }
    }

    /**
     * 当前卡范围必须与快照锚定的范围语义一致。
     * 充值不得因为卡范围后来被改动而把权益落进一个用户当初没同意的授权范围。
     */
    private String checkScope(WsCard card, RechargeSnapshot.Parsed snap) {
        try {
            WaterCardScope current = WaterCardScope.normalize(card.getScopeJson(), "水卡");
            if (!current.sameAuthorityAs(snap.cardScope())) {
                return "目标卡可用范围与下单时快照不一致";
            }
        } catch (JbkException e) {
            return "目标卡可用范围非法：" + e.getMessage();
        }
        return null;
    }

    /** 充值入账幂等键固定 {@code RECHARGE:<orderNo>}（L2-DB 契约 §5.1）。 */
    public static String bizKey(String orderNo) {
        return "RECHARGE:" + orderNo;
    }
}

package com.jbk.serve.service.mini.recharge.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.serve.service.aftersale.batch.EntitlementBatchOrder;
import com.jbk.serve.service.aftersale.batch.EntitlementBatchWriter;
import com.jbk.serve.service.aftersale.batch.EntitlementLedger;
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
    /** 入账同事务建立权益批次，作为退款折算的唯一基准。 */
    private final WsCardEntitlementBatchMapper batchMapper;
    private final EntitlementLedger entitlementLedger;

    @Override
    /**
     * READ_COMMITTED（与 RechargeIssueTxImpl 的 N-15 修复同因同解）：REPEATABLE READ 下
     * 转正名额复查是普通一致性读，读视图在事务首条 SELECT（selectEventById）时已建立——
     * 后到者在 ws_user 行锁上醒来后读到的仍是先行者提交前的快照，两张赠卡并发转正会各成一张
     * 付费卡（真库复现见 RechargeCreditTxDbTest 并发转正用例）。READ_COMMITTED 让复查读到
     * 赢家刚提交的卡；刻意不用 FOR UPDATE 复查——无卡用户的锁定读在二级索引上留 gap 锁，
     * 无关用户并发会互等死锁。关键读本就全是 FOR UPDATE，不受隔离级别变化影响。
     */
    @Transactional(rollbackFor = Exception.class,
            isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
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

        boolean cardFinite = StrUtil.isNotBlank(card.getExpireTime());
        boolean packageFinite = verified.snapshot().expireDays() != null;
        boolean promote = verified.snapshot().promoteToPermanent();
        if (promote) {
            // 转正单（D-416）的预期形态恰是「有限赠卡 × 永久套餐」；其余组合仍是错位
            if (!cardFinite || packageFinite) {
                return CreditResult.unrecoverable("转正单形态不符：卡应为有限赠卡、套餐应为永久");
            }
            // 审计 P1-1：锁内名额复查。用户行锁已在 RechargeLockedState.load 按
            // payment→order→user→card 序取得，这里的计数不会再被并发转正/首购绕过。
            // 口径=首购发卡资格 SQL（跨全部 CARD_STATUS、按 CardEligibility 排除赠卡）：
            // 本卡是赠卡不计入，>0 即名下已有付费卡，钱已收但一人一卡不允许第二张——
            // 订单转 6、事实转待对账（unrecoverable 通道），零权益写入。
            if (creditMapper.countLiveCardsByUser(order.getUserId()) > 0) {
                return CreditResult.unrecoverable("入账时名下已有正式水卡，转正终止转人工");
            }
            // 审计 P0-2：已自然过期的赠卡先在同一事务内作废旧权益（批次置5清零 + 卡同步
            // 扣减 + EXPIRE_CLEAR 流水，EntitlementLedger.settleExpired 唯一入口），
            // 只有本次真实充值的新权益才能随转正成为永久权益。权益耗尽但未到期的赠卡
            // 余额本就是 0，清算自然零动作。
            if (RechargeExpiry.naturallyExpired(card.getExpireTime(), processingTime)) {
                EntitlementLedger.CardAfter settled =
                        entitlementLedger.settleExpired(card, order.getUserId(), processingTime);
                card.setBalanceAmount(settled.amountAfter());
                card.setBalanceMl(settled.mlAfter());
            }
            // 审计 R2 P0-1：入账锁内重验转正资格——旧权益必须已<b>完全</b>归零。
            // 创单到入账的窗口里，退款/退差/补偿可以把权益加回这张赠卡（refundCardAssets
            // 的状态白名单含 1/3），耗尽资格在创单时成立不代表此刻仍成立；已过期赠卡上
            // 退款锁定(2)的正余额批次 settleExpired 刻意不动，同样不得随转正永久化。
            // 三条同时成立才放行：卡两列为 0 + 全部未删批次（不限状态）零正剩余。
            long residualFen = card.getBalanceAmount() == null ? 0L : card.getBalanceAmount();
            long residualMl = card.getBalanceMl() == null ? 0L : card.getBalanceMl();
            if (residualFen != 0L || residualMl != 0L
                    || !batchMapper.lockAllPositiveRemainByCard(card.getId()).isEmpty()) {
                return CreditResult.unrecoverable("转正前赠卡仍有未归零权益（余额 " + residualFen
                        + " 分 / 水量 " + residualMl + " 毫升或存在正剩余批次），可能被退款或补偿恢复，转人工处理");
            }
        }
        else if (cardFinite != packageFinite) {
            // 创单时已校验过同类型；此刻不一致说明卡有效期在支付期间被改写
            return CreditResult.unrecoverable("水卡与套餐有效期类型在支付期间发生变化");
        }

        // ── 步骤 5：计算 credit 与预期 AFTER（转正单的前态是清算后的值）；有限卡算续期 ──
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
        int rows;
        if (promote) {
            // 赠卡转正（D-416）：复用有限卡 CAS，新有效期传 NULL——SQL 的
            // SET EXPIRE_TIME=#{newExpireTime}, CARD_STATUS=1 一条语句同时完成
            // 「转永久 + 过期状态恢复」，前态锁清算后余额与旧到期时间，并发改动即 0 行回滚。
            // 创单到入账的窗口由上方的用户行锁 + 锁内名额复查封死（审计 P1-1），
            // 不再依赖任何事后兜底。
            rows = creditMapper.creditFiniteCard(card.getId(), credit.amountFen(), credit.ml(),
                    null, order.getPackageId(), order.getPackageSnap(),
                    order.getUserId(), processingTime,
                    oldAmount, oldMl, card.getExpireTime(), card.getScopeJson());
        }
        else if (packageFinite) {
            rows = creditMapper.creditFiniteCard(card.getId(), credit.amountFen(), credit.ml(),
                    newExpireTime, order.getPackageId(), order.getPackageSnap(),
                    order.getUserId(), processingTime,
                    oldAmount, oldMl, card.getExpireTime(), card.getScopeJson());
        }
        else {
            rows = creditMapper.creditPermanentCard(card.getId(), credit.amountFen(), credit.ml(),
                    order.getPackageId(), order.getPackageSnap(),
                    order.getUserId(), processingTime,
                    oldAmount, oldMl, card.getScopeJson());
        }
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

        // E2E-04 包D：入账同事务建立权益批次（REQ-061）。
        // 位置紧跟流水之后、与卡权益同事务：三者同生共死。理由见 EntitlementBatchWriter 类注释。
        // 本批次的有效期取<b>本次充值算出的 newExpireTime</b> 而非卡的聚合有效期——
        // 卡聚合有效期是所有批次里最晚的那个，用它会让本批次看起来比实际更晚过期，
        // 消费次序因此排错，早到期的权益反而被留到最后作废。
        EntitlementBatchWriter.createOnCredit(batchMapper, order, card.getId(), order.getUserId(),
                locked.payment().getId(),
                EntitlementBatchOrder.SourceType.RECHARGE,
                verified.snapshot().payAmount(), credit.amountFen(),
                verified.snapshot().bonusAmount(), credit.ml(),
                packageFinite ? newExpireTime : null, card.getScopeJson(), processingTime);

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

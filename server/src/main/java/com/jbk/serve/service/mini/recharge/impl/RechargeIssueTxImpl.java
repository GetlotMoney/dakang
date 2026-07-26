package com.jbk.serve.service.mini.recharge.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.serve.service.mini.recharge.IRechargeCreditTx;
import com.jbk.serve.service.mini.recharge.IRechargeIssueTx;
import com.jbk.serve.service.mini.recharge.NewCardExpiry;
import com.jbk.serve.service.mini.recharge.RechargeCredit;
import com.jbk.serve.service.mini.recharge.RechargeExpiry;
import com.jbk.serve.service.mini.recharge.RechargePayExpire;
import com.jbk.serve.service.mini.recharge.RechargePayStatus;
import com.jbk.serve.service.mini.recharge.RechargeSnapshot;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsPaymentEvent;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 首次购卡发卡事务（决策 A2/A3/A4）——{@link RechargeCreditTxImpl} 的姊妹实现。
 *
 * <p>固定顺序（锁序 payment → order → 用户行 → 发行锚点卡 → 同单事件组 → 卡流水，
 * 与事务 B 的 payment → order → card → events → flows 保持「卡先于事件」一致，避免交叉锁序死锁）：</p>
 * <ol>
 *   <li>锁 payment、order 并逐项核验共键、快照与付款资格；</li>
 *   <li>锁 {@code ws_user} 行——同一用户的发卡在数据库层串行化（决策 A4）；</li>
 *   <li>发行锚点 {@code ISSUE_ORDER_ID} 命中即只做幂等核验，绝不建第二张（决策 A3）；</li>
 *   <li>复查资格：存在任意 DATA_STATUS=0 的卡即不可恢复（钱已收但不能再发卡，转人工）；</li>
 *   <li>建零余额零水量虚拟卡 → 复用 credit CAS 原子加权益（前态 0,0）→ 唯一流水
 *       → 回填 CARD_ID CAS → 订单 2→4 → 事件组收敛。</li>
 * </ol>
 *
 * <p>任一步失败抛异常整体回滚：不留卡、不留流水、订单不动（决策 A2）。
 * 权益取值只来自订单快照；新卡有效期只从权威 paySuccessTime 起算（{@link NewCardExpiry}，决策 A1）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeIssueTxImpl implements IRechargeIssueTx {

    private static final int ORDER_TYPE_RECHARGE = 2;
    private static final int PAY_WAY_WECHAT = 1;
    private static final int PAY_SUCCESS = 2;
    private static final int ORDER_PAID = 2;
    private static final int ORDER_FINISHED = 4;
    /** 卡类型(1331)：1 虚拟卡（决策 A5：一期 L2-A 固定建虚拟卡）。 */
    private static final int CARD_TYPE_VIRTUAL = 1;
    private static final int CARD_NORMAL = 1;
    private static final DateTimeFormatter TIME = com.jbk.tool.utils.DateUtils.COMPACT_FORMATTER;

    private final RechargeCreditMapper mapper;
    private final RechargeLedgerVerifier ledgerVerifier;

    @Override
    /**
     * N-15 修复：本事务用 READ_COMMITTED。
     *
     * <p>REPEATABLE READ 下，资格复查 {@code countLiveCardsByUser} 是普通一致性读，读视图在
     * 事务首条 SELECT 时已建立——后到者在 ws_user 行锁上醒来后，读到的仍是先行者提交前的快照，
     * 于是同用户两笔已支付购卡单并发时各发一张卡（真库复现见 RechargeIssueTxDbTest）。
     * READ_COMMITTED 让每条语句读最新已提交版本：拿到用户行锁后复查即可看见赢家刚提交的卡。</p>
     *
     * <p>刻意不用 FOR SHARE/FOR UPDATE 复查：对无卡用户在二级索引 idx_card_user 上做锁定读会留
     * gap 锁，两个落在同一索引间隙的<b>无关</b>用户并发发卡会插入意向互等死锁
     * （selectCardByIssueOrderId 的注释已记录过同型教训）。降隔离级别零新增锁面。</p>
     *
     * <p>同用户串行化仍由步骤 b 的 ws_user 行 X 锁保证；本事务内 payment/order/user 等关键读
     * 本就全是 FOR UPDATE（当前读），不受隔离级别变化影响。</p>
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public IRechargeCreditTx.CreditResult issue(Long eventId, String processingTime) {
        Locked locked = lockAndVerify(eventId);
        WsOrder order = locked.order();
        WsPayment payment = locked.payment();
        RechargeSnapshot.Parsed snap = locked.snapshot();

        // ── 步骤 c：幂等锚点（决策 A3）。命中只核验，绝不建第二张。
        // 锚点的存在性由**锁内订单状态**分流，而不是先无差别地按 ISSUE_ORDER_ID 查一把：
        // 锚点卡与订单 2→4 在同一事务写入（决策 A2 的原子性保证），因此锁内订单仍为 2
        // ⟹ 锚点必不存在（若有人绕过本事务手工插了锚点卡，步骤 e 的 uk_card_issue_order
        // 会在插入时兜底回滚）。这样 fresh 路径完全不对空的唯一索引做锁定查询——
        // 对不存在的行加 FOR UPDATE 会留下 gap 锁，两笔不相关的并发发卡会在相邻空隙上互等死锁；
        // 而不加锁的普通读在 REPEATABLE READ 下又可能读到过期快照，把并发方刚发的卡看成不存在。
        if (ObjectUtil.equals(order.getOrderStatus(), ORDER_FINISHED)) {
            // 订单终态分支：锚点卡此刻应当存在（FOR UPDATE 命中的是既有行，只加行锁不留 gap 锁）
            WsCard issued = mapper.selectCardByIssueOrderId(order.getId());
            if (issued == null) {
                // 订单已完成却没有发行锚点卡：账实不符（锚点是发卡的唯一凭证），只能人工厘清
                return IRechargeCreditTx.CreditResult.unrecoverable("购卡订单已完成但缺少发行锚点水卡，账实不符");
            }
            return verifyAlreadyIssued(locked, issued, processingTime);
        }
        if (!ObjectUtil.equals(order.getOrderStatus(), ORDER_PAID)) {
            return IRechargeCreditTx.CreditResult.unrecoverable(
                    "订单状态不允许进入发卡事务：" + order.getOrderStatus());
        }

        // ── 步骤 d：复查资格（决策 A4）。创单校验过不代表现在仍无卡——支付窗口里可能被后台发过卡 ──
        if (mapper.countLiveCardsByUser(order.getUserId()) > 0) {
            return IRechargeCreditTx.CreditResult.unrecoverable("用户已持有生效水卡，禁止重复首次发卡");
        }

        RechargeCredit credit = RechargeCredit.of(snap);
        // 决策 A1：有效期只从权威 paySuccessTime 起算；永久套餐返回 null（EXPIRE_TIME 保持 SQL NULL）
        String newExpireTime = NewCardExpiry.compute(payment.getPaySuccessTime(), snap.expireDays());
        if (newExpireTime != null && RechargeExpiry.isAlreadyExpired(newExpireTime, processingTime)) {
            // 处理严重延迟到新卡有效期已经过去：绝不发一张当场作废的卡（§2.2 M8 同源规则）
            return IRechargeCreditTx.CreditResult.unrecoverable(
                    "新卡有效期 " + newExpireTime + " 不晚于处理时间 " + processingTime + "，拒绝发卡");
        }

        // ── 步骤 e：建零余额零水量虚拟卡（决策 A2）──
        // 范围取快照冻结的新卡初始范围（=创单时套餐范围，决策 A6），不回读套餐当前值
        String scopeJson = snap.cardScope().toCanonicalJson().toJSONString();
        WsCard card = new WsCard();
        card.setDataStatus(0);
        card.setCreateBy(order.getUserId());
        card.setCreateTime(processingTime);
        card.setUpdateBy(order.getUserId());
        card.setUpdateTime(processingTime);
        card.setCardNo(deriveCardNo(order.getOrderNo()));
        card.setCardType(CARD_TYPE_VIRTUAL);
        card.setUserId(order.getUserId());
        card.setBalanceAmount(0L);
        card.setBalanceMl(0L);
        card.setPackageId(order.getPackageId());
        card.setPackageSnap(order.getPackageSnap());
        card.setScopeJson(scopeJson);
        card.setExpireTime(newExpireTime);
        card.setCardStatus(CARD_NORMAL);
        card.setIssueOrderId(order.getId());
        // uk_card_no / uk_card_issue_order 双唯一键：并发重放在这里撞键 → 整事务回滚 → 天然幂等
        if (mapper.insertIssuedCard(card) != 1 || card.getId() == null) {
            throw new JbkException("虚拟卡创建失败");
        }

        // ── 步骤 f：复用 L2-B 的 credit CAS 原子加权益，前态即刚写入的 0,0 与有效期/范围 ──
        // 权益路径只此一条：不在 INSERT 里顺手写余额，否则发卡与充值就有两套入账口径
        int rows = newExpireTime != null
                ? mapper.creditFiniteCard(card.getId(), credit.amountFen(), credit.ml(),
                        newExpireTime, order.getPackageId(), order.getPackageSnap(),
                        order.getUserId(), processingTime,
                        0L, 0L, newExpireTime, scopeJson)
                : mapper.creditPermanentCard(card.getId(), credit.amountFen(), credit.ml(),
                        order.getPackageId(), order.getPackageSnap(),
                        order.getUserId(), processingTime,
                        0L, 0L, scopeJson);
        if (rows != 1) {
            // 刚插入的卡前态就该是 0,0；改不动说明同事务数据已不自洽，整体回滚
            throw new JbkException("首充权益写入失败：新卡前态异常");
        }

        // ── 步骤 g：唯一流水。幂等键仍 RECHARGE:<orderNo>；AFTER = 0 + 权益值 ──
        // 撞唯一键（该订单曾以任何路径入过账）→ 整事务回滚，连同刚建的卡一起撤销
        if (mapper.insertRechargeFlow(card.getId(), order.getUserId(),
                credit.amountFen(), credit.ml(), credit.amountFen(), credit.ml(),
                order.getId(), "首次购卡入账 " + order.getOrderNo(),
                RechargeCreditTxImpl.bizKey(order.getOrderNo()), processingTime) != 1) {
            throw new JbkException("首充流水插入影响行数异常");
        }

        // ── 步骤 h：回填 CAS。CARD_ID IS NULL 前态保证该列只被写一次，绝不覆盖既有关联 ──
        if (mapper.backfillOrderCardId(order.getId(), card.getId(), processingTime) != 1) {
            throw new JbkException("订单 CARD_ID 回填失败：订单已关联其他卡");
        }

        // ── 步骤 i：订单 2→4，影响行必须 1 ──
        if (mapper.markOrderFinished(order.getId(), processingTime) != 1) {
            throw new JbkException("订单状态非已支付，拒绝完成发卡");
        }

        // ── 步骤 j：事件组收敛（与 RechargeCreditTxImpl.completeSuccessGroup 同一语义）──
        completeSuccessGroup(locked.events(), processingTime);
        log.info("首次购卡发卡完成：orderNo={} cardNo={} 余额 0→{} 水量 0→{} 有效期 {}",
                order.getOrderNo(), card.getCardNo(), credit.amountFen(), credit.ml(),
                newExpireTime == null ? "永久" : newExpireTime);
        return IRechargeCreditTx.CreditResult.credited();
    }

    /**
     * 发卡失败落痕（决策 A2，语义对齐 {@link RechargeCreditFailureTxImpl}）。
     * 重新按锁序锁定现状，绝不沿用失败事务里的对象或无锁读结果。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordFailure(Long eventId, boolean retryable, String reason, String now) {
        WsPaymentEvent hint = mapper.selectEventById(eventId);
        if (hint == null || hint.getPaymentId() == null) {
            throw new JbkException("支付事实缺失或未关联支付单");
        }
        WsPayment payment = mapper.lockPayment(hint.getPaymentId());
        if (payment == null || payment.getOrderId() == null) {
            throw new JbkException("支付单缺失或未关联订单");
        }
        WsOrder order = mapper.lockOrder(payment.getOrderId());
        if (order == null) {
            throw new JbkException("订单缺失");
        }
        String safeReason = StrUtil.maxLength(StrUtil.blankToDefault(reason, "首次购卡发卡失败"), 500);
        if (!ObjectUtil.equals(payment.getPayStatus(), PAY_SUCCESS)) {
            throw new JbkException("失败落痕时支付单不在成功态，拒绝覆盖现状");
        }

        if (ObjectUtil.equals(order.getOrderStatus(), ORDER_FINISHED)) {
            settleCompletedOrder(payment, order, safeReason, now);
            return;
        }
        if (!ObjectUtil.equals(order.getOrderStatus(), ORDER_PAID)) {
            throw new JbkException("失败落痕时订单不在可写前态，拒绝覆盖现状");
        }
        List<WsPaymentEvent> events = mapper.lockEventsByOrderNo(order.getOrderNo());
        if (retryable) {
            // 可恢复：订单保持 2 无卡等重试（决策 A2）
            updateMatchingGroup(events, payment, order, true, safeReason, now);
            return;
        }
        // 不可恢复：精确前态 CAS 订单 2→6，钱已收、无卡，转人工（决策 A2）
        if (mapper.markOrderAbnormal(order.getId(),
                StrUtil.maxLength("首次购卡待人工：" + safeReason, 200), now) != 1) {
            throw new JbkException("购卡订单异常落痕 CAS 失败");
        }
        updateMatchingGroup(events, payment, order, false, safeReason, now);
    }

    // ------------------------------------------------------------------
    // 锁定与核验
    // ------------------------------------------------------------------

    /**
     * purchase 专用装载：order.CARD_ID 为 NULL（或刚被回填），不能走 {@link RechargeLockedState#load}
     * ——那条路径把「订单未关联卡」视为硬错误，而这恰是购卡单支付成功时的常态。
     */
    private record Locked(WsPayment payment, WsOrder order, WsPaymentEvent event,
                          List<WsPaymentEvent> events, RechargeSnapshot.Parsed snapshot) {
    }

    private Locked lockAndVerify(Long eventId) {
        WsPaymentEvent hint = mapper.selectEventById(eventId);
        if (hint == null || hint.getPaymentId() == null) {
            throw new JbkException("支付事实缺失或未关联支付单");
        }
        // ── 步骤 a：锁 payment、order ──
        WsPayment payment = mapper.lockPayment(hint.getPaymentId());
        if (payment == null || payment.getOrderId() == null) {
            throw new JbkException("支付单缺失或未关联订单");
        }
        WsOrder order = mapper.lockOrder(payment.getOrderId());
        if (order == null) {
            throw new JbkException("订单缺失");
        }
        if (!ObjectUtil.equals(payment.getDataStatus(), 0) || !ObjectUtil.equals(order.getDataStatus(), 0)) {
            throw new JbkException("支付单或订单不可用");
        }
        if (!ObjectUtil.equals(order.getOrderType(), ORDER_TYPE_RECHARGE)
                || !ObjectUtil.equals(order.getPayWay(), PAY_WAY_WECHAT)
                || !ObjectUtil.equals(payment.getPayStatus(), PAY_SUCCESS)) {
            throw new JbkException("支付单或订单状态不允许发卡");
        }
        if (!ObjectUtil.equals(payment.getOrderId(), order.getId())
                || !StrUtil.equals(payment.getOrderNo(), order.getOrderNo())
                || !ObjectUtil.equals(payment.getPayAmount(), order.getOrderAmount())) {
            throw new JbkException("支付单与订单共键错位");
        }
        RechargeSnapshot.Parsed snap = RechargeSnapshot.parse(order.getPackageSnap());
        if (snap.purchaseMode() == null) {
            // CARD_ID 为空却是 L2-B 充值快照：订单身份自相矛盾，绝不能按「无卡」发一张卡出去
            throw new JbkException("订单快照不是首次购卡快照，拒绝发卡");
        }
        if (!StrUtil.equals(snap.capturedTime(), order.getCreateTime())
                || !StrUtil.equals(snap.packageId(), String.valueOf(order.getPackageId()))
                || !ObjectUtil.equals(snap.payAmount(), order.getOrderAmount())) {
            throw new JbkException("订单快照与订单共键错位");
        }
        // purchase 快照 expireTimeAtCreate 恒为 null → 冻结算法给出 createTime+30min（决策 A1）
        String expectedExpire = RechargePayExpire.compute(snap.capturedTime(), snap.expireTimeAtCreate());
        if (!StrUtil.equals(payment.getPayExpireTime(), expectedExpire)) {
            throw new JbkException("付款截止时间与不可变资格快照不一致");
        }
        if (StrUtil.isBlank(payment.getTransactionId()) || StrUtil.isBlank(payment.getPaySuccessTime())
                || !RechargePayExpire.paidInTime(payment.getPaySuccessTime(), payment.getPayExpireTime())) {
            throw new JbkException("权威支付事实缺失或超过付款截止时间");
        }

        // ── 步骤 b：锁用户行（决策 A4：同一用户发卡串行化）──
        if (mapper.lockUserRow(order.getUserId()) == null) {
            throw new JbkException("下单用户不存在，无法发卡");
        }
        List<WsPaymentEvent> events = mapper.lockEventsByOrderNo(order.getOrderNo());
        WsPaymentEvent event = events.stream()
                .filter(row -> ObjectUtil.equals(row.getId(), eventId))
                .findFirst()
                .orElseThrow(() -> new JbkException("支付事实与订单号错位"));
        if (!RechargePayStatus.SUCCESS.equals(event.getTradeState())
                || !ObjectUtil.equals(event.getProcessingStatus(), 2)) {
            throw new JbkException("当前支付事实不是可处理的成功事实");
        }
        verifySuccessGroup(events, payment, order);
        return new Locked(payment, order, event, List.copyOf(events), snap);
    }

    /** 同 {@link RechargeLockedState} 的成功事实组校验：任何一条错位都不允许发卡。 */
    private void verifySuccessGroup(List<WsPaymentEvent> events, WsPayment payment, WsOrder order) {
        for (WsPaymentEvent row : events) {
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

    /**
     * 幂等核验（决策 A3）：发行锚点已有卡时，核验卡归属、卡号派生、账本与本单权益一致，
     * 收敛事件后返回 ALREADY。任何不一致抛异常转人工，绝不建第二张、也绝不修补既有卡。
     */
    private IRechargeCreditTx.CreditResult verifyAlreadyIssued(Locked locked, WsCard card,
                                                               String processingTime) {
        WsOrder order = locked.order();
        if (!ObjectUtil.equals(order.getCardId(), card.getId())) {
            // 锚点卡与订单 CARD_ID 必须同时成立（同一事务写入）；缺一即是被破坏过的账
            return IRechargeCreditTx.CreditResult.unrecoverable("发行锚点与订单终态不一致，账实不符");
        }
        if (!ObjectUtil.equals(card.getDataStatus(), 0)
                || !ObjectUtil.equals(card.getUserId(), order.getUserId())
                || !ObjectUtil.equals(card.getCardType(), CARD_TYPE_VIRTUAL)
                || !StrUtil.equals(card.getCardNo(), deriveCardNo(order.getOrderNo()))) {
            return IRechargeCreditTx.CreditResult.unrecoverable("发行锚点水卡归属或身份与订单不一致");
        }
        // 权益与流水核验复用 L2-B 完成态账本核验：恰好一条本单幂等键流水、CHANGE 等于快照权益、
        // 账本自本单起逐笔连续到卡终值、有效期不低于本单可证明下限（purchase 下限即 paySuccessTime+expireDays）。
        // 不比对卡当前 PACKAGE_ID/SCOPE_JSON 与本单快照：发卡之后的 L2-B 充值会合法地覆盖最近套餐。
        RechargeCredit credit = RechargeCredit.of(locked.snapshot());
        ledgerVerifier.requireAlreadyCredited(order, card, mapper.lockCardFlows(card.getId()),
                credit, locked.snapshot(), locked.payment().getPaySuccessTime());
        completeSuccessGroup(locked.events(), processingTime);
        return IRechargeCreditTx.CreditResult.already();
    }

    // ------------------------------------------------------------------
    // 失败落痕辅助（语义对齐 RechargeCreditFailureTxImpl）
    // ------------------------------------------------------------------

    /** 订单已完成：只做只读核验与事件收敛；核验不一致时只把未完成事件转待对账，绝不动订单/卡/流水。 */
    private void settleCompletedOrder(WsPayment payment, WsOrder order, String reason, String now) {
        WsCard card = mapper.selectCardByIssueOrderId(order.getId());
        List<WsPaymentEvent> events = mapper.lockEventsByOrderNo(order.getOrderNo());
        try {
            if (card == null) {
                throw new JbkException("已完成购卡订单缺少发行锚点水卡");
            }
            if (!ObjectUtil.equals(order.getCardId(), card.getId())
                    || !ObjectUtil.equals(card.getDataStatus(), 0)
                    || !ObjectUtil.equals(card.getUserId(), order.getUserId())
                    || !ObjectUtil.equals(card.getCardType(), CARD_TYPE_VIRTUAL)
                    || !StrUtil.equals(card.getCardNo(), deriveCardNo(order.getOrderNo()))) {
                throw new JbkException("发行锚点水卡归属或身份与订单不一致");
            }
            RechargeSnapshot.Parsed snap = RechargeSnapshot.parse(order.getPackageSnap());
            if (snap.purchaseMode() == null) {
                throw new JbkException("已完成购卡订单的快照不是首次购卡快照");
            }
            ledgerVerifier.requireAlreadyCredited(order, card, mapper.lockCardFlows(card.getId()),
                    RechargeCredit.of(snap), snap, payment.getPaySuccessTime());
            processMatchingGroup(events, payment, order, now);
        } catch (RuntimeException mismatch) {
            log.error("已完成购卡订单的失败落痕核验不一致：orderNo={} reason={} verifyError={}",
                    order.getOrderNo(), reason, mismatch.getMessage());
            reconcileMatchingGroup(events, payment, order,
                    StrUtil.maxLength(reason + "；完成态核验失败：" + mismatch.getMessage(), 500), now);
        }
    }

    private void updateMatchingGroup(List<WsPaymentEvent> events, WsPayment payment, WsOrder order,
                                     boolean retryable, String reason, String now) {
        int targets = 0;
        for (WsPaymentEvent row : events) {
            if (!matches(row, payment, order) || ObjectUtil.equals(row.getProcessingStatus(), 3)) {
                continue;
            }
            targets++;
            Integer expected = row.getProcessingStatus();
            if (!ObjectUtil.equals(expected, 1) && !ObjectUtil.equals(expected, 2)
                    && !ObjectUtil.equals(expected, 4)) {
                throw new JbkException("支付事实组含不可覆盖的失败状态：" + expected);
            }
            int changed = retryable
                    ? mapper.markEventRetryWait(row.getId(), expected, plusSeconds(now, 60), reason, now)
                    : mapper.markEventReconciliation(row.getId(), expected, reason, now);
            if (changed != 1) {
                throw new JbkException("支付事实组失败落痕影响行数异常");
            }
        }
        if (targets == 0) {
            throw new JbkException("没有可落痕的匹配成功支付事实");
        }
    }

    private void processMatchingGroup(List<WsPaymentEvent> events, WsPayment payment, WsOrder order, String now) {
        for (WsPaymentEvent row : events) {
            if (!matches(row, payment, order) || ObjectUtil.equals(row.getProcessingStatus(), 3)) {
                continue;
            }
            if (mapper.markEventProcessed(row.getId(), row.getProcessingStatus(), now) != 1) {
                throw new JbkException("已完成订单事件收敛影响行数异常");
            }
        }
    }

    private void reconcileMatchingGroup(List<WsPaymentEvent> events, WsPayment payment, WsOrder order,
                                        String reason, String now) {
        for (WsPaymentEvent row : events) {
            if (!matches(row, payment, order) || ObjectUtil.equals(row.getProcessingStatus(), 3)
                    || ObjectUtil.equals(row.getProcessingStatus(), 5)) {
                continue;
            }
            if (mapper.markEventReconciliation(row.getId(), row.getProcessingStatus(), reason, now) != 1) {
                throw new JbkException("已完成订单异常事件落痕影响行数异常");
            }
        }
    }

    private boolean matches(WsPaymentEvent row, WsPayment payment, WsOrder order) {
        return RechargePayStatus.SUCCESS.equals(row.getTradeState())
                && ObjectUtil.equals(row.getDataStatus(), 0)
                && ObjectUtil.equals(row.getPaymentId(), payment.getId())
                && ObjectUtil.equals(row.getOrderId(), order.getId())
                && StrUtil.equals(row.getOrderNo(), order.getOrderNo())
                && ObjectUtil.equals(row.getPaySource(), payment.getPaySource())
                && ObjectUtil.equals(row.getPayAmount(), payment.getPayAmount())
                && "CNY".equals(row.getCurrency())
                && StrUtil.equals(row.getTransactionId(), payment.getTransactionId())
                && StrUtil.equals(row.getPaySuccessTime(), payment.getPaySuccessTime());
    }

    /** 同一支付事实组必须与发卡、流水、订单在本事务内一起收敛（同 RechargeCreditTxImpl）。 */
    private void completeSuccessGroup(List<WsPaymentEvent> events, String now) {
        for (WsPaymentEvent event : events) {
            if (!RechargePayStatus.SUCCESS.equals(event.getTradeState())
                    || ObjectUtil.equals(event.getProcessingStatus(), 3)) {
                continue;
            }
            Integer expected = event.getProcessingStatus();
            if (!ObjectUtil.equals(expected, 1) && !ObjectUtil.equals(expected, 2)
                    && !ObjectUtil.equals(expected, 4)) {
                throw new JbkException("成功支付事实组含不可收敛状态：" + expected);
            }
            if (mapper.markEventProcessed(event.getId(), expected, now) != 1) {
                throw new JbkException("支付事实组收敛影响行数异常");
            }
        }
    }

    /**
     * 虚拟卡卡号派生（<b>工程决定，非业务决策</b>——业务只要求卡号唯一且与现有 16 位卡号等长）：
     * {@code "VC" + SHA-256(orderNo) 前 14 位十六进制大写}，共 16 位。
     * 由订单号确定性派生：同一订单无论重放多少次算出的都是同一个卡号，
     * 配合 {@code uk_card_no} 与 {@code uk_card_issue_order} 双唯一键，并发建卡在数据库层天然幂等；
     * 不含日期/序列/随机数，跨重启、跨进程稳定可复算，对账时可由订单号独立验证卡号归属。
     */
    public static String deriveCardNo(String orderNo) {
        if (StrUtil.isBlank(orderNo)) {
            throw new JbkException("订单号缺失，无法派生卡号");
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(orderNo.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new JbkException("卡号派生失败");
        }
        StringBuilder hex = new StringBuilder(16);
        hex.append("VC");
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
            if (hex.length() >= 16) {
                break;
            }
        }
        return hex.substring(0, 16).toUpperCase();
    }

    private String plusSeconds(String value, long seconds) {
        try {
            return LocalDateTime.parse(value, TIME).plusSeconds(seconds).format(TIME);
        } catch (RuntimeException e) {
            throw new JbkException("业务时间格式非法，无法安排重试");
        }
    }
}

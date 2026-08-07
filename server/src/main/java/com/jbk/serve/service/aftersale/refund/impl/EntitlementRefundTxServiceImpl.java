package com.jbk.serve.service.aftersale.refund.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.aftersale.WsEntitlementAllocationMapper;
import com.jbk.serve.mapper.aftersale.WsRefundMapper;
import com.jbk.serve.mapper.trade.TradeCardMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.mapper.user.WsCardMemberMapper;
import com.jbk.serve.service.aftersale.AfterSaleTransitions;
import com.jbk.serve.service.aftersale.IAfterSaleActionTxService;
import com.jbk.serve.service.aftersale.batch.CardClosureRule;
import com.jbk.serve.service.aftersale.batch.EntitlementBatchLinkGuard;
import com.jbk.serve.service.aftersale.batch.EntitlementBatchOrder;
import com.jbk.serve.service.aftersale.batch.EntitlementRefundPlan;
import com.jbk.serve.service.aftersale.refund.IEntitlementRefundTxService;
import com.jbk.serve.service.aftersale.refund.IRefundSourceAdapter;
import com.jbk.serve.service.aftersale.refund.RefundEligibility;
import com.jbk.serve.service.mini.recharge.impl.RechargeCreditTxImpl;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.ActionStatus;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.ActionType;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.SourceType;
import com.jbk.tool.consts.aftersale.RefundEnum;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.consts.user.UserEnum;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.data.aftersale.po.WsCardEntitlementBatch;
import com.jbk.tool.data.aftersale.po.WsRefund;
import com.jbk.tool.data.aftersale.vo.AdminRechargeRefundPreviewVo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.data.user.po.WsCardMember;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 充值/购卡退款的权益批次事务实现（E2E-04 包D-5，REQ-061）。
 *
 * <h3>锁序固定：订单 → 卡 → 批次</h3>
 * <p>与包D-4 的消费路径同序（那边是「卡 → 批次」，这里在它前面多锁一个订单）。
 * 顺序反过来就会与消费事务构成交叉等待：消费持卡锁等批次锁，退款持批次锁等卡锁。</p>
 *
 * <h3>受理与结算之间隔着一个外部系统</h3>
 * <p>受理只锁批次并登记动作；真正动钱的是 {@link #settleOnRefundSuccess}，
 * 而它的触发条件是「ws_refund 已被退款事实推成成功」。两者之间的窗口里，
 * 批次处于 2退款锁定，消费侧的 {@code consume} CAS 与 {@code requireConsumable} 双闸拦住它——
 * 这就是任务书「退款审核后必须锁定对应批次，防止退款处理中继续消费」的落点。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntitlementRefundTxServiceImpl implements IEntitlementRefundTxService {

    /** 结算与落痕的操作人：由退款事实（Worker）触发，没有后台会话，与包B 的 0L 口径一致。 */
    private static final long SYSTEM_OPERATOR = 0L;

    private static final int ERROR_MAX = 497;

    /** 受理说明写进 CALC_SNAPSHOT（text 列）；仍设上限，避免一段超长文本把整个快照顶到列外。 */
    private static final int ACCEPT_REMARK_MAX = 200;

    private final WsRefundMapper refundMapper;
    private final WsAfterSaleActionMapper actionMapper;
    private final WsCardEntitlementBatchMapper batchMapper;
    private final WsEntitlementAllocationMapper allocationMapper;
    private final TradeCardMapper tradeCardMapper;
    private final WsOrderMapper orderMapper;
    private final WsWalletFlowMapper walletFlowMapper;
    private final WsCardMemberMapper cardMemberMapper;
    private final IWsDomainEventService domainEventService;
    /** 登记走包A 的统一入口（REQUIRED 传播，并入本受理事务）：售后号与状态机起点只有那一份实现。 */
    private final IAfterSaleActionTxService actionTxService;
    /** 当前退款通道只提供受信任来源常量；来源必须与原支付单严格同源。 */
    private final IRefundSourceAdapter refundSourceAdapter;

    // ------------------------------------------------------------------
    // ① 受理：锁批次 + 登记待执行动作
    // ------------------------------------------------------------------

    /**
     * 只读预览：所有金额都由 {@link EntitlementRefundPlan} 算出，页面一列不推导。
     *
     * <p>不加锁——预览不是安全边界，它的结论在受理事务里会被<b>重新算一遍</b>
     * （那次是在订单/卡/批次三把锁之内）。此处加锁只会让一次查看把消费路径堵住。</p>
     *
     * <p>业务性拒绝一律转成 {@code blockReason} 而不是异常：不可退的四类原因
     * （历史聚合权益 / 已退过 / 已用尽 / 账本断裂）各自对应不同的人工动作，
     * 全部糊成一个红条等于让运营去猜。</p>
     */
    @Override
    @Transactional(readOnly = true)
    public AdminRechargeRefundPreviewVo preview(Long orderId) {
        AdminRechargeRefundPreviewVo vo = new AdminRechargeRefundPreviewVo()
                .setOrderId(orderId == null ? null : String.valueOf(orderId))
                .setRefundable(Boolean.FALSE)
                .setCardWillClose(Boolean.FALSE);
        if (ObjectUtil.isNull(orderId) || orderId <= 0) {
            return vo.setBlockReason("充值订单ID非法");
        }
        WsOrder order = orderMapper.selectById(orderId);
        if (ObjectUtil.isNull(order)) {
            return vo.setBlockReason("订单不存在或已删除");
        }
        vo.setOrderNo(order.getOrderNo()).setOrderStatus(order.getOrderStatus());
        if (ObjectUtil.notEqual(order.getOrderType(), TradeEnum.OrderType.CARD.getValue())) {
            return vo.setBlockReason("只有购卡/充值订单可以走权益批次退款");
        }
        if (ObjectUtil.notEqual(order.getOrderStatus(), TradeEnum.OrderStatus.FINISHED.getValue())) {
            return vo.setBlockReason("订单不是已完成状态（实际 " + order.getOrderStatus()
                    + "）；已付款未入账的异常单走另一条全额退款路径");
        }
        WsCardEntitlementBatch batch = batchMapper.selectByOrderId(orderId);
        if (ObjectUtil.isNull(batch)) {
            return vo.setBlockReason("该充值订单没有权益批次（多为包D 上线前的历史充值），退款只能走人工");
        }
        vo.setBatchId(String.valueOf(batch.getId()))
                .setBatchSourceType(batch.getSourceType())
                .setBatchStatus(batch.getBatchStatus())
                .setPayAmountFen(batch.getPayAmountFen())
                .setGrantAmountFen(batch.getGrantAmountFen())
                .setGrantBonusFen(batch.getGrantBonusFen())
                .setGrantWaterMl(batch.getGrantWaterMl())
                .setRemainAmountFen(batch.getRemainAmountFen())
                .setRemainWaterMl(batch.getRemainWaterMl());
        WsCard card = tradeCardMapper.selectById(order.getCardId());
        if (ObjectUtil.isNull(card)) {
            return vo.setBlockReason("退款关联水卡不存在或已删除，转人工处理");
        }
        try {
            EntitlementBatchLinkGuard.requireLinked(
                    order, batch, card.getId(), card.getUserId(), null);
        } catch (JbkException rejected) {
            return vo.setBlockReason(rejected.getMsg());
        }
        vo.setCardId(String.valueOf(card.getId())).setCardNo(card.getCardNo());
        long batchFen = batchMapper.sumRemainFenByCard(card.getId());
        long batchMl = batchMapper.sumRemainMlByCard(card.getId());
        if (batchFen != requireAmount(card.getBalanceAmount(), "卡余额")
                || batchMl != requireAmount(card.getBalanceMl(), "卡水量")) {
            return vo.setBlockReason("权益批次剩余合计(" + batchFen + "分/" + batchMl
                    + "毫升)与水卡聚合值不符，账本断裂，退款只能走人工对账");
        }
        EntitlementRefundPlan.Plan plan;
        try {
            plan = EntitlementRefundPlan.of(batch);
        }
        catch (JbkException rejected) {
            return vo.setBlockReason(rejected.getMsg());
        }
        CardClosureRule.Decision decision = CardClosureRule.decide(
                closureEvidence(order, card, batch, plan.reverseFen(), plan.reverseMl()));
        return vo.setWaterPackage(plan.waterPackage())
                .setUsedWaterMl(plan.usedWaterMl())
                .setRefundableFen(plan.refundableFen())
                .setReverseFen(plan.reverseFen())
                .setReverseMl(plan.reverseMl())
                .setCardWillClose(decision == CardClosureRule.Decision.CLOSE)
                .setRefundable(Boolean.TRUE);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public Long prepare(Long orderId, String remark, Long opUserId, String now) {
        requireId(orderId, "充值订单ID");
        requireOperator(opUserId);
        requireTime(now);

        // ── 锁订单：受理与并发的消费/续期之间靠这把锁排队 ──
        WsOrder order = refundMapper.lockOrder(orderId);
        if (ObjectUtil.isNull(order)) {
            throw new JbkException("退款关联订单不存在");
        }
        if (ObjectUtil.notEqual(order.getOrderType(), TradeEnum.OrderType.CARD.getValue())) {
            throw new JbkException("只有购卡/充值订单可以走权益批次退款");
        }
        if (ObjectUtil.notEqual(order.getOrderStatus(), TradeEnum.OrderStatus.FINISHED.getValue())) {
            // 4已完成 是「钱收了、权益也发了」的唯一形状。6异常待补偿 走包B 的未入账全额退款，
            // 7/8 说明已经退过。放宽这一条就会出现「同一笔钱按两条路径各退一次」
            throw new JbkException("订单不是已完成状态（实际 " + order.getOrderStatus()
                    + "），已入账退款只受理已完成的充值订单");
        }
        requireId(order.getCardId(), "订单关联水卡ID");
        WsPayment payment = RefundEligibility.requireSinglePayment(refundMapper.lockPaymentsByOrder(order.getId()));
        RefundEligibility.requireMatchingSource(payment, refundSourceAdapter.currentSource());

        // ── 锁卡 → 锁批次（顺序见类注释）──
        WsCard card = requireUsableCard(order);
        WsCardEntitlementBatch batch = requireBatchOfOrder(order);
        EntitlementBatchLinkGuard.requireLinked(order, batch, card.getId(), card.getUserId(), payment.getId());
        requireLedgerIntact(card, "受理退款");

        EntitlementRefundPlan.Plan plan = EntitlementRefundPlan.of(batch);

        // ── 登记动作：额度落在「水品金额」维度，合计与它相等（createPending 由分维派生合计）──
        // 机构退款没有配送费与水量维度，两者恒 0；这样 markSuccess 的四列 WHERE 才有确定期望值。
        WsAfterSaleAction draft = new WsAfterSaleAction()
                .setSourceType(SourceType.RECHARGE_REFUND.getValue())
                .setSourceId(order.getId())
                .setOrderId(order.getId())
                .setUserId(order.getUserId())
                .setCardId(card.getId())
                .setActionType(ActionType.GATEWAY_REFUND.getValue())
                .setRefundProductFen(plan.refundableFen())
                .setRefundServiceFen(0L)
                .setRefundProductMl(0L)
                .setCalcSnapshot(buildSnapshot(order, batch, plan, card, remark, opUserId, now));
        draft.setCreateBy(opUserId);
        draft.setUpdateBy(opUserId);
        WsAfterSaleAction action = actionTxService.createPending(draft, now);

        // ── 锁批次：1可用 → 2退款锁定。幂等命中既有动作时批次可能已经锁过 ──
        int locked = batchMapper.lockForRefund(batch.getId(), batch.getVersion(), action.getId(), opUserId, now);
        if (locked != 1) {
            WsCardEntitlementBatch current = batchMapper.lockById(batch.getId());
            boolean alreadyMine = ObjectUtil.isNotNull(current)
                    && ObjectUtil.equals(current.getBatchStatus(), EntitlementBatchOrder.BatchStatus.REFUND_LOCKED)
                    && ObjectUtil.equals(current.getRefundLockedBy(), action.getId());
            if (!alreadyMine) {
                throw new JbkException("权益批次锁定失败：状态或版本已变，可能已有另一笔退款在处理中");
            }
            log.info("权益批次已被本动作锁定，按幂等继续：batchId={} actionId={}", batch.getId(), action.getId());
        }

        // 幂等键前缀必须留够位置：ws_domain_event.BIZ_IDEMPOTENCY_KEY 是 varchar(64)，
        // 而售后号本身占 32 位（AS + 30）。前缀超过 32 位就会在写审计时撞 data truncation，
        // 把一次本该成功的受理整体回滚——隔离验收第一轮就是这样红的。
        domainEventService.recordReliableOnce(OpsEnum.EventType.AFTER_SALE, action.getAfterSaleNo(),
                "AFTERSALE_ACCEPT:" + action.getAfterSaleNo(), null,
                "充值退款受理：" + JSONUtil.createObj()
                        .set("afterSaleNo", action.getAfterSaleNo())
                        .set("orderNo", order.getOrderNo())
                        .set("batchId", batch.getId())
                        .set("refundableFen", plan.refundableFen())
                        .set("reverseFen", plan.reverseFen())
                        .set("reverseMl", plan.reverseMl()));
        return action.getId();
    }

    // ------------------------------------------------------------------
    // ② 成功结算：批次冲正 + 卡冲减 + 流水 + 订单终态 + 卡处置
    // ------------------------------------------------------------------

    /**
     * <b>READ_COMMITTED 与包A 的资金事务同理</b>：本方法先读退款单与动作行，再锁卡；
     * RR 下那份锁卡前的一致性读视图会让后续的批次/卡读取停在旧版本上，
     * 而结算的每一步 CAS 都以这些读到的前态为条件，读旧即恒 0 行、整笔结算永远推不动。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public void settleOnRefundSuccess(Long refundId, String now) {
        requireId(refundId, "退款单ID");
        requireTime(now);

        WsRefund refund = refundMapper.selectById(refundId);
        if (ObjectUtil.isNull(refund)) {
            throw new JbkException("退款单不存在，无法结算");
        }
        if (ObjectUtil.notEqual(refund.getRefundStatus(), RefundEnum.RefundStatus.SUCCESS.getValue())) {
            // 只有服务方事实确认成功的退款才允许动权益。这条断言是本方法唯一的资格来源，
            // 绝不接受调用方「我知道它成功了」的口头保证
            throw new JbkException("退款单不是成功状态，拒绝结算权益");
        }
        WsAfterSaleAction action = actionMapper.selectByIdIncludingDeleted(refund.getAfterSaleId());
        if (ObjectUtil.isNull(action)) {
            throw new JbkException("退款单未关联售后动作，无法结算");
        }
        if (ObjectUtil.equals(action.getActionStatus(), ActionStatus.SUCCESS.getValue())) {
            return;
        }
        if (ObjectUtil.notEqual(action.getActionType(), ActionType.GATEWAY_REFUND.getValue())) {
            throw new JbkException("非机构退款动作不走权益批次结算");
        }

        // 认领：PENDING/RETRY_WAIT → PROCESSING。抢不到即他人正在结算，本次放手
        int processing = ActionStatus.PROCESSING.getValue();
        if (actionMapper.claimForExecute(action.getId(), action.getVersion(), processing,
                AfterSaleTransitions.requireSources(processing), SYSTEM_OPERATOR, now) != 1) {
            WsAfterSaleAction current = actionMapper.selectByIdIncludingDeleted(action.getId());
            if (ObjectUtil.isNotNull(current)
                    && ObjectUtil.equals(current.getActionStatus(), ActionStatus.SUCCESS.getValue())) {
                return;
            }
            throw new JbkException("售后动作认领落空，结算稍后重试");
        }
        // claim 已把 VERSION +1，后续 CAS 必须用重读值（包A 的 markTerminal 踩过这个坑）
        WsAfterSaleAction claimed = actionMapper.selectByIdIncludingDeleted(action.getId());
        if (ObjectUtil.isNull(claimed)) {
            throw new JbkException("售后动作认领后读不回，结算中止");
        }

        WsOrder order = refundMapper.lockOrder(action.getOrderId());
        if (ObjectUtil.isNull(order)) {
            throw new JbkException("退款关联订单不存在，结算中止");
        }
        WsPayment payment = RefundEligibility.requireSinglePayment(refundMapper.lockPaymentsByOrder(order.getId()));
        if (ObjectUtil.notEqual(refund.getPaymentId(), payment.getId())) {
            throw new JbkException("退款单与原支付单共键错位，拒绝结算");
        }
        RefundEligibility.requireMatchingSource(payment, refund.getRefundSource());
        long expectedFen = requireAmount(claimed.getRefundProductFen(), "退款额度");
        if (ObjectUtil.notEqual(refund.getRefundAmount(), expectedFen)) {
            // 退款单金额与受理时算定的额度不一致：账实不符，绝不按任一侧继续
            throw new JbkException("退款单金额(" + refund.getRefundAmount() + ")与受理额度("
                    + expectedFen + ")不一致，拒绝结算");
        }

        RefundEligibility.RefundPath refundPath = ObjectUtil.equals(
                claimed.getSourceType(), SourceType.RECHARGE_REFUND.getValue())
                ? RefundEligibility.requireRefundPath(claimed)
                : RefundEligibility.RefundPath.UNSETTLED_FULL;
        WsCardEntitlementBatch batch = batchMapper.selectByOrderId(order.getId());
        if (refundPath == RefundEligibility.RefundPath.UNSETTLED_FULL) {
            if (ObjectUtil.isNotNull(batch)) {
                throw new JbkException("未入账退款在受理后出现权益批次，拒绝按原路径结算，转人工对账");
            }
            long stillRefundable = RefundEligibility.requireUnsettledFullRefund(
                    new RefundEligibility.Evidence(order, payment,
                            refundMapper.countRechargeFlowByBizKey(RechargeCreditTxImpl.bizKey(order.getOrderNo())) > 0,
                            refundMapper.countIssuedCardByOrder(order.getId()) > 0));
            if (stillRefundable != expectedFen) {
                throw new JbkException("未入账退款金额在结算期漂移，转人工对账");
            }
            settleWithoutEntitlement(claimed, order, expectedFen, now);
            return;
        }
        if (ObjectUtil.isNull(batch)) {
            throw new JbkException("权益批次退款在结算期缺少批次，转人工对账");
        }
        settleWithEntitlement(claimed, order, batch, refund.getPaymentId(), expectedFen, now);
    }

    /** 有权益可冲正：批次冲正 → 卡冲减 → 唯一流水 → 订单终态 → 卡处置 → 盖成功章。 */
    private void settleWithEntitlement(WsAfterSaleAction action, WsOrder order,
                                       WsCardEntitlementBatch batchHint, Long paymentId,
                                       long refundedFen, String now) {
        WsCard card = requireUsableCard(order);
        WsCardEntitlementBatch batch = batchMapper.lockById(batchHint.getId());
        if (ObjectUtil.isNull(batch)) {
            throw new JbkException("权益批次读不回，结算中止");
        }
        EntitlementBatchLinkGuard.requireLinked(
                order, batch, action.getCardId(), action.getUserId(), paymentId);
        if (ObjectUtil.notEqual(batch.getBatchStatus(), EntitlementBatchOrder.BatchStatus.REFUND_LOCKED)
                || ObjectUtil.notEqual(batch.getRefundLockedBy(), action.getId())) {
            // 结算只允许作用在「本动作锁定的批次」上。少了这条，任意一笔退款成功都能冲正别人的批次
            throw new JbkException("权益批次未被本次退款锁定（状态 " + batch.getBatchStatus()
                    + "，锁定方 " + batch.getRefundLockedBy() + "），拒绝结算");
        }
        requireLedgerIntact(card, "退款结算");

        long reverseFen = requireAmount(batch.getRemainAmountFen(), "批次剩余余额");
        long reverseMl = requireAmount(batch.getRemainWaterMl(), "批次剩余水量");
        long oldAmount = requireAmount(card.getBalanceAmount(), "卡余额前态");
        long oldMl = requireAmount(card.getBalanceMl(), "卡水量前态");

        // ① 批次冲正：2退款锁定 → 3已退款，剩余清零并累计已退金额
        if (batchMapper.settleRefunded(batch.getId(), batch.getVersion(), action.getId(),
                refundedFen, SYSTEM_OPERATOR, now) != 1) {
            throw new JbkException("权益批次冲正影响行数异常，转人工对账");
        }
        // ② 分摊冲正：这批次上的消费已被退款抵消，不再计入「净消费」
        allocationMapper.reverseByBatch(batch.getId(), SYSTEM_OPERATOR, now);

        // ③ 卡聚合冲减：与批次剩余逐维相等；卡余额不足即 0 行整体回滚（绝不扣成负数）
        if (reverseFen > 0 || reverseMl > 0) {
            if (tradeCardMapper.reverseCardAssets(card.getId(), reverseFen, reverseMl,
                    oldAmount, oldMl, card.getUserId(), SYSTEM_OPERATOR, now) != 1) {
                throw new JbkException("水卡权益冲减影响行数异常：卡前态漂移或余额不足，账本断裂");
            }
            // ④ 唯一流水：负向变动 + AFTERSALE:<售后号> 幂等键（撞键即整事务回滚）
            long amountAfter = Math.subtractExact(oldAmount, reverseFen);
            long mlAfter = Math.subtractExact(oldMl, reverseMl);
            WsWalletFlow flow = new WsWalletFlow()
                    .setCardId(card.getId())
                    .setUserId(order.getUserId())
                    .setFlowType(TradeEnum.FlowType.REFUND.getValue())
                    .setAmountChange(-reverseFen)
                    .setMlChange(-reverseMl)
                    .setAmountAfter(amountAfter)
                    .setMlAfter(mlAfter)
                    .setOrderId(order.getId())
                    .setFlowRemark("充值退款权益冲减 " + order.getOrderNo())
                    .setBizIdempotencyKey("AFTERSALE:" + action.getAfterSaleNo());
            flow.setCreateBy(SYSTEM_OPERATOR);
            flow.setCreateTime(now);
            flow.setUpdateTime(now);
            if (walletFlowMapper.insert(flow) != 1) {
                throw new JbkException("充值退款流水插入影响行数异常");
            }
        }

        // ⑤ 订单终态：整批分文未动 → 7已退款；被用过一部分 → 8部分退款。
        //    判据取<b>本事务锁内重读</b>的批次行，不沿用受理时算出的计划：受理与结算之间隔着一个
        //    外部系统，那时的「未动」到这里可能已经不成立（批次在受理前被消费过、或被并发恢复入账改形）。
        boolean fullyUnused = ObjectUtil.equals(batch.getGrantAmountFen(), batch.getRemainAmountFen())
                && ObjectUtil.equals(batch.getGrantWaterMl(), batch.getRemainWaterMl());
        moveOrderToRefunded(order, fullyUnused, now);

        // ⑥ 卡处置（R0 规则）：证据在锁内查，判定问 CardClosureRule
        disposeCard(order, card, batch, reverseFen, reverseMl, now);

        // ⑦ 盖成功章：四元额度在 WHERE 里二次钉死
        markActionSuccess(action, refundedFen, now);
        log.info("充值退款结算完成：afterSaleNo={} orderNo={} batchId={} 退款 {} 分，冲减 {} 分 / {} 毫升",
                action.getAfterSaleNo(), order.getOrderNo(), batch.getId(), refundedFen, reverseFen, reverseMl);
    }

    /** 无权益可冲正（包B 已付款未入账路径）：零资金写入，只推订单终态并盖成功章。 */
    private void settleWithoutEntitlement(WsAfterSaleAction action, WsOrder order,
                                          long refundedFen, String now) {
        moveOrderToRefunded(order, true, now);
        markActionSuccess(action, refundedFen, now);
        log.info("未入账订单退款结算完成（零权益冲正）：afterSaleNo={} orderNo={} 退款 {} 分",
                action.getAfterSaleNo(), order.getOrderNo(), refundedFen);
    }

    /**
     * 订单终态 CAS：前态取当前实际状态，影响行必须 1。
     *
     * <p>前态集合刻意只含 4已完成 与 6异常待补偿 两个——这是两条退款路径各自的合法起点。
     * 允许从任意状态推进，等于给「把一个已取消或已退款的单再退一次」放行。</p>
     */
    private void moveOrderToRefunded(WsOrder order, boolean fullyUnused, String now) {
        int target = fullyUnused
                ? TradeEnum.OrderStatus.REFUNDED.getValue()
                : TradeEnum.OrderStatus.PART_REFUNDED.getValue();
        int moved = orderMapper.update(null, Wrappers.lambdaUpdate(WsOrder.class)
                .set(WsOrder::getOrderStatus, target)
                .set(WsOrder::getFinishTime, now)
                .set(WsOrder::getUpdateBy, SYSTEM_OPERATOR)
                .set(WsOrder::getUpdateTime, now)
                .eq(WsOrder::getId, order.getId())
                .in(WsOrder::getOrderStatus, TradeEnum.OrderStatus.FINISHED.getValue(),
                        TradeEnum.OrderStatus.ABNORMAL.getValue()));
        if (moved != 1) {
            throw new JbkException("订单退款终态推进影响行数异常：状态已变（实际 "
                    + order.getOrderStatus() + "），拒绝结算");
        }
    }

    /**
     * 首购退款后的卡处置：注销或保留并重算聚合有效期。
     *
     * <p>证据全部在锁内查（其他批次 / 成员授权 / 未完成订单 / 冲减后余量），判定问
     * {@link CardClosureRule}。成员授权按「非撤销即算存在」的<b>保守</b>口径计数：
     * 时间窗判定的唯一实现在 {@code CardMemberRule}（Java 侧），在 SQL 里复制一份时间窗
     * 就会出现两份真相；而保守计数的偏差方向是「多保留一张空卡」，不是「注销一张还有人在用的卡」。</p>
     */
    private void disposeCard(WsOrder order, WsCard card, WsCardEntitlementBatch batch,
                             long reverseFen, long reverseMl, String now) {
        CardClosureRule.Decision decision = CardClosureRule.decide(
                closureEvidence(order, card, batch, reverseFen, reverseMl));
        if (decision == CardClosureRule.Decision.CLOSE) {
            if (tradeCardMapper.closeEmptyCard(card.getId(), card.getUserId(), SYSTEM_OPERATOR, now) != 1) {
                // 判定说可注销、SQL 却发现卡上仍有余额或状态已变：两侧读到的不是同一刻的账本
                throw new JbkException("水卡注销影响行数异常：卡上仍有权益或状态已变，转人工对账");
            }
            log.info("首购退款后水卡转注销：cardId={} orderNo={}", card.getId(), order.getOrderNo());
            return;
        }
        resetAggregateExpiry(card, now);
    }

    /**
     * 注销判定证据——<b>预览与结算共用这一份</b>。
     *
     * <p>两处各查一遍是本包最容易埋雷的地方：预览说「退完会注销」而结算按另一套口径保留了卡
     * （或反过来），运营就会按一个不成立的预期做决定。故四项证据只在这里查。
     * 预览时无锁、结算时在卡行锁内——查询口径相同，隔离强度不同，这是刻意的。</p>
     *
     * <p>成员授权按「非解除即算存在」的<b>保守</b>口径计数：时间窗判定的唯一实现在
     * {@code CardMemberRule}（Java 侧），在 SQL 里复制一份时间窗就会出现两份真相；
     * 而保守计数的偏差方向是「多保留一张空卡」，不是「注销一张还有人在用的卡」。</p>
     */
    private CardClosureRule.Evidence closureEvidence(WsOrder order, WsCard card,
                                                     WsCardEntitlementBatch batch,
                                                     long reverseFen, long reverseMl) {
        long remainFen = Math.subtractExact(requireAmount(card.getBalanceAmount(), "卡余额"), reverseFen);
        long remainMl = Math.subtractExact(requireAmount(card.getBalanceMl(), "卡水量"), reverseMl);
        boolean firstPurchase = ObjectUtil.equals(batch.getSourceType(),
                EntitlementBatchOrder.SourceType.FIRST_PURCHASE);
        long otherBatches = batchMapper.selectCount(Wrappers.lambdaQuery(WsCardEntitlementBatch.class)
                .eq(WsCardEntitlementBatch::getCardId, card.getId())
                .ne(WsCardEntitlementBatch::getId, batch.getId())
                .ne(WsCardEntitlementBatch::getBatchStatus, EntitlementBatchOrder.BatchStatus.REFUNDED)
                .and(w -> w.gt(WsCardEntitlementBatch::getRemainAmountFen, 0)
                        .or().gt(WsCardEntitlementBatch::getRemainWaterMl, 0)));
        long activeMembers = cardMemberMapper.selectCount(Wrappers.lambdaQuery(WsCardMember.class)
                .eq(WsCardMember::getCardId, card.getId())
                .eq(WsCardMember::getMemberStatus, UserEnum.CardMemberStatus.ACTIVE.getValue()));
        long unfinishedOrders = orderMapper.selectCount(Wrappers.lambdaQuery(WsOrder.class)
                .eq(WsOrder::getCardId, card.getId())
                .ne(WsOrder::getId, order.getId())
                .in(WsOrder::getOrderStatus, TradeEnum.OrderStatus.UNPAID.getValue(),
                        TradeEnum.OrderStatus.PAID.getValue(),
                        TradeEnum.OrderStatus.DISPENSING.getValue(),
                        TradeEnum.OrderStatus.ABNORMAL.getValue()));
        return new CardClosureRule.Evidence(firstPurchase, (int) otherBatches, (int) activeMembers,
                (int) unfinishedOrders, remainFen, remainMl);
    }

    /**
     * 重算聚合有效期：取剩余可消费批次里最晚的那个；含永久批次则为永久（NULL）。
     *
     * <p>没有剩余批次时不动有效期——那种卡要么刚被判定为保留（还有成员/在途单），
     * 要么权益已空，此时把有效期改成 NULL（永久）反而是放宽。</p>
     */
    private void resetAggregateExpiry(WsCard card, String now) {
        List<WsCardEntitlementBatch> remaining = batchMapper.lockConsumableByCard(card.getId());
        if (remaining.isEmpty()) {
            return;
        }
        String newExpire = null;
        for (WsCardEntitlementBatch b : remaining) {
            if (StrUtil.isBlank(b.getExpireTime())) {
                // 永久批次在场，聚合有效期即永久，无需再比
                newExpire = null;
                break;
            }
            if (newExpire == null || b.getExpireTime().compareTo(newExpire) > 0) {
                newExpire = b.getExpireTime();
            }
        }
        String oldExpire = card.getExpireTime();
        boolean unchanged = StrUtil.isBlank(newExpire) ? StrUtil.isBlank(oldExpire)
                : StrUtil.equals(newExpire, oldExpire);
        if (unchanged) {
            return;
        }
        if (tradeCardMapper.resetAggregateExpireTime(card.getId(), newExpire, oldExpire,
                card.getUserId(), SYSTEM_OPERATOR, now) != 1) {
            throw new JbkException("水卡聚合有效期重算影响行数异常：有效期在结算期被改动，转人工对账");
        }
        log.info("退款后重算水卡聚合有效期：cardId={} {} → {}", card.getId(), oldExpire, newExpire);
    }

    private void markActionSuccess(WsAfterSaleAction action, long refundedFen, String now) {
        int success = ActionStatus.SUCCESS.getValue();
        if (actionMapper.markSuccess(action.getId(), action.getVersion(), success,
                AfterSaleTransitions.requireSources(success),
                refundedFen, 0L, 0L, refundedFen, SYSTEM_OPERATOR, now) != 1) {
            throw new JbkException("售后动作标记完成影响行数异常：状态或额度在结算期漂移");
        }
        domainEventService.recordReliableOnce(OpsEnum.EventType.AFTER_SALE, action.getAfterSaleNo(),
                "AFTERSALE_DONE:" + action.getAfterSaleNo(), ActionStatus.PROCESSING.getDesc(),
                "充值退款结算完成：" + JSONUtil.createObj()
                        .set("afterSaleNo", action.getAfterSaleNo())
                        .set("actionType", ActionType.GATEWAY_REFUND.getValue())
                        .set("refundAmount", refundedFen));
    }

    // ------------------------------------------------------------------
    // ③ 失败落痕
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void parkOnRefundFailure(Long refundId, String reason, String now) {
        requireId(refundId, "退款单ID");
        requireTime(now);
        WsRefund refund = refundMapper.selectById(refundId);
        if (ObjectUtil.isNull(refund) || ObjectUtil.isNull(refund.getAfterSaleId())) {
            return;
        }
        WsAfterSaleAction action = actionMapper.selectByIdIncludingDeleted(refund.getAfterSaleId());
        if (ObjectUtil.isNull(action)
                || ObjectUtil.equals(action.getActionStatus(), ActionStatus.SUCCESS.getValue())) {
            // 已成功的动作绝不被迟到的失败事实降级（R0-7）
            return;
        }
        // 5需人工对账 只接受 2执行中 / 4可重试 两个前态（状态机唯一那份矩阵）。
        // 而受理成功后的动作停在 1待执行——退款期间它没有「执行中」这一步，动钱的是结算。
        // 故先按状态机认领一次（1→2），再落终态；两步都在本 REQUIRES_NEW 事务内，
        // 中途失败一起回滚，绝不会留下一个卡在 2执行中却没有任何在途退款的动作。
        WsAfterSaleAction pending = action;
        if (ObjectUtil.equals(pending.getActionStatus(), ActionStatus.PENDING.getValue())) {
            int processing = ActionStatus.PROCESSING.getValue();
            if (actionMapper.claimForExecute(pending.getId(), pending.getVersion(), processing,
                    AfterSaleTransitions.requireSources(processing), SYSTEM_OPERATOR, now) != 1) {
                log.error("退款失败落痕前的认领落空：refundId={} actionId={}", refundId, pending.getId());
                return;
            }
            pending = actionMapper.selectByIdIncludingDeleted(pending.getId());
            if (ObjectUtil.isNull(pending)) {
                return;
            }
        }
        int target = ActionStatus.RECONCILIATION_REQUIRED.getValue();
        int rows = actionMapper.markTerminal(pending.getId(), pending.getVersion(), target,
                AfterSaleTransitions.requireSources(target), null, null,
                StrUtil.maxLength(StrUtil.blankToDefault(reason, "退款失败"), ERROR_MAX),
                SYSTEM_OPERATOR, now);
        if (rows != 1) {
            log.error("退款失败落痕影响 0 行：refundId={} actionId={} status={}",
                    refundId, pending.getId(), pending.getActionStatus());
        }
        // 批次锁定<b>刻意不解除</b>：任务书 3.4「退款永久失败时不得自动恢复为未退款，进入人工处理」。
        // 自动解锁会让一笔可能已经在服务方侧出款的退款重新变成「可消费」，那是双花窗口。
        log.warn("充值退款失败，权益批次保持退款锁定待人工处理：refundId={} afterSaleNo={} 原因={}",
                refundId, action.getAfterSaleNo(), reason);
    }

    // ------------------------------------------------------------------
    // 私有辅助
    // ------------------------------------------------------------------

    /** 锁卡并核验存在性、归属与可操作状态（注销卡不再参与任何权益变动）。 */
    private WsCard requireUsableCard(WsOrder order) {
        WsCard card = tradeCardMapper.selectByIdForUpdate(order.getCardId());
        if (ObjectUtil.isNull(card) || ObjectUtil.notEqual(card.getDataStatus(), 0)) {
            throw new JbkException("退款关联水卡不存在或已删除，转人工处理");
        }
        if (ObjectUtil.notEqual(card.getUserId(), order.getUserId())) {
            throw new JbkException("退款关联水卡已换主，拒绝结算");
        }
        if (ObjectUtil.equals(card.getCardStatus(), UserEnum.CardStatus.CANCELLED.getValue())) {
            throw new JbkException("退款关联水卡已注销，转人工处理");
        }
        return card;
    }

    private WsCardEntitlementBatch requireBatchOfOrder(WsOrder order) {
        WsCardEntitlementBatch batch = batchMapper.selectByOrderId(order.getId());
        if (ObjectUtil.isNull(batch)) {
            throw new JbkException("该充值订单没有权益批次（多为包D 上线前的历史充值），退款只能走人工");
        }
        return batchMapper.lockById(batch.getId());
    }

    /**
     * 账本自洽性闸：逐卡「批次剩余合计 == 卡聚合值」。
     *
     * <p>这是包D-4 维护的不变式，也是折算能成立的前提。此刻不等说明这张卡上有一部分权益
     * 不属于任何批次（或反之），此时无论按哪一侧算退款都可能错，故 fail-closed。
     * <b>只在退款路径上校验</b>：消费路径不校验，因为包D-4 上线前的存量漂移（批次高于卡）
     * 一旦在消费处 fail-closed，会让那些卡直接不能用；而退款是钱真正流出系统的地方，
     * 这里必须严格。</p>
     */
    private void requireLedgerIntact(WsCard card, String stage) {
        long batchFen = batchMapper.sumRemainFenByCard(card.getId());
        long batchMl = batchMapper.sumRemainMlByCard(card.getId());
        long cardFen = requireAmount(card.getBalanceAmount(), "卡余额");
        long cardMl = requireAmount(card.getBalanceMl(), "卡水量");
        if (batchFen != cardFen || batchMl != cardMl) {
            throw new JbkException(stage + "被拒：权益批次剩余合计(" + batchFen + "分/" + batchMl
                    + "毫升)与水卡聚合值(" + cardFen + "分/" + cardMl + "毫升)不符，账本断裂，转人工对账");
        }
    }

    private String buildSnapshot(WsOrder order, WsCardEntitlementBatch batch,
                                 EntitlementRefundPlan.Plan plan, WsCard card, String remark,
                                 Long opUserId, String now) {
        return JSONUtil.createObj()
                .set(RefundEligibility.REFUND_PATH_FIELD, RefundEligibility.RefundPath.ENTITLEMENT.name())
                .set("orderNo", order.getOrderNo())
                .set("batchId", batch.getId())
                .set("batchSourceType", batch.getSourceType())
                .set("payAmountFen", batch.getPayAmountFen())
                .set("grantAmountFen", batch.getGrantAmountFen())
                .set("grantBonusFen", batch.getGrantBonusFen())
                .set("grantWaterMl", batch.getGrantWaterMl())
                .set("remainAmountFen", batch.getRemainAmountFen())
                .set("remainWaterMl", batch.getRemainWaterMl())
                .set("waterPackage", plan.waterPackage())
                .set("usedWaterMl", plan.usedWaterMl())
                .set("refundableFen", plan.refundableFen())
                .set("reverseFen", plan.reverseFen())
                .set("reverseMl", plan.reverseMl())
                .set("cardId", card.getId())
                .set("cardExpireTime", card.getExpireTime())
                .set("acceptRemark", StrUtil.maxLength(StrUtil.blankToDefault(remark, ""), ACCEPT_REMARK_MAX))
                .set("acceptedBy", opUserId)
                .set("acceptedAt", now)
                .toString();
    }

    private void requireId(Long id, String label) {
        if (ObjectUtil.isNull(id) || id <= 0) {
            throw new JbkException(label + "非法");
        }
    }

    private void requireOperator(Long opUserId) {
        if (ObjectUtil.isNull(opUserId) || opUserId <= 0) {
            throw new JbkException("退款受理人身份非法");
        }
    }

    private void requireTime(String now) {
        if (StrUtil.isBlank(now) || now.length() != 14) {
            throw new JbkException("业务时间格式非法，拒绝退款结算");
        }
    }

    private long requireAmount(Number value, String label) {
        if (ObjectUtil.isNull(value)) {
            throw new JbkException(label + "缺失");
        }
        long amount = value.longValue();
        if (amount < 0) {
            throw new JbkException(label + "不能为负：" + amount);
        }
        return amount;
    }
}

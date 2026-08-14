package com.jbk.serve.service.aftersale.refund.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jbk.serve.mapper.aftersale.WsAfterSaleActionMapper;
import com.jbk.serve.mapper.aftersale.WsCardEntitlementBatchMapper;
import com.jbk.serve.mapper.aftersale.WsRefundMapper;
import com.jbk.serve.service.aftersale.IAfterSaleActionTxService;
import com.jbk.serve.service.aftersale.refund.IRefundRequestTxService;
import com.jbk.serve.service.aftersale.batch.EntitlementBatchLinkGuard;
import com.jbk.serve.service.aftersale.refund.IRefundSourceAdapter;
import com.jbk.serve.service.aftersale.refund.RefundEligibility;
import com.jbk.serve.service.aftersale.refund.RefundNo;
import com.jbk.serve.service.mini.recharge.impl.RechargeCreditTxImpl;
import com.jbk.tool.consts.aftersale.AfterSaleEnum;
import com.jbk.tool.consts.aftersale.RefundEnum;
import com.jbk.tool.data.aftersale.po.WsAfterSaleAction;
import com.jbk.tool.data.aftersale.po.WsCardEntitlementBatch;
import com.jbk.tool.data.aftersale.po.WsRefund;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 外部退款请求事务实现（E2E-04 包B）。依赖清单即安全边界：不注入事实收件箱与卡/流水写入能力，
 * 只能建 1退款中 的退款单与回填受理凭据；「退款成功」必须由收件箱事实经 Worker 推进（R0-8）。
 *
 * @author dakang
 * @since 2026-07-29
 */
@Service
@RequiredArgsConstructor
public class RefundRequestTxServiceImpl implements IRefundRequestTxService {

    private static final int TIME_LEN = 14;
    private static final String CURRENCY_CNY = "CNY";
    private static final int REASON_MAX = 500;

    /** 2退款锁定（字典 1374）。与 EntitlementBatchOrder.BatchStatus 同值；此处只读比对，不引入写能力。 */
    private static final int BATCH_STATUS_REFUND_LOCKED = 2;

    private final WsRefundMapper refundMapper;
    private final WsAfterSaleActionMapper actionMapper;
    private final IRefundSourceAdapter refundSourceAdapter;
    /** 只读回查权益批次的锁定状态（包D-5）：本类没有批次写能力，锁定发生在受理事务里。 */
    private final WsCardEntitlementBatchMapper batchMapper;
    /** 售后动作沿用包A的确定性编号与唯一键；REQUIRED 传播保证动作与本地退款单同生共死。 */
    private final IAfterSaleActionTxService actionTxService;

    /**
     * 从 PC 订单行受理「已付款未入账异常充值单」全额退款。动作与本地退款单必须同事务提交
     * （缺一即幽灵待办或事实成功后无终态落点）；外部受理由编排层在本事务提交后调用。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public Long createUnsettledRechargePending(Long orderId, String remark, Long opUserId, String now) {
        requireArgs(orderId, opUserId, now);
        if (StrUtil.isBlank(remark) || remark.trim().length() > 200) {
            throw new JbkException("退款受理说明不能为空且不得超过200字");
        }

        // 能力闸必须早于锁与写入；微信未接入时不能先留动作再失败，更不能自动退化到 Sim
        refundSourceAdapter.requireOperable();

        WsOrder order = refundMapper.lockOrder(orderId);
        if (ObjectUtil.isNull(order)) {
            throw new JbkException("退款关联订单不存在");
        }
        WsPayment payment = RefundEligibility.requireSinglePayment(refundMapper.lockPaymentsByOrder(order.getId()));
        RefundEligibility.requireMatchingSource(payment, refundSourceAdapter.currentSource());

        // 准入证据在任何写入之前一次判完，失败时本事务零持久副作用
        long refundable = requireUnsettledPath(order, payment);
        WsAfterSaleAction draft = new WsAfterSaleAction()
                .setSourceType(AfterSaleEnum.SourceType.RECHARGE_REFUND.getValue())
                .setSourceId(order.getId())
                .setOrderId(order.getId())
                .setUserId(order.getUserId())
                .setCardId(order.getCardId())
                .setActionType(AfterSaleEnum.ActionType.GATEWAY_REFUND.getValue())
                .setRefundProductFen(refundable)
                .setRefundServiceFen(0L)
                .setRefundProductMl(0L)
                .setApproveBy(opUserId)
                .setApproveTime(now)
                .setCalcSnapshot(JSONUtil.createObj()
                        .set(RefundEligibility.REFUND_PATH_FIELD,
                                RefundEligibility.RefundPath.UNSETTLED_FULL.name())
                        .set("orderNo", order.getOrderNo())
                        .set("paymentId", payment.getId())
                        .set("paidAmountFen", refundable)
                        .set("acceptRemark", remark.trim())
                        .set("acceptedBy", opUserId)
                        .set("acceptedAt", now)
                        .toString());
        draft.setCreateBy(opUserId);
        draft.setUpdateBy(opUserId);
        WsAfterSaleAction action = actionTxService.createPending(draft, now);
        requireUnsettledActionCompatible(action, order, refundable);

        return createRefundForLockedEvidence(action, order, payment,
                RefundEligibility.RefundPath.UNSETTLED_FULL, opUserId, now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Long createPending(Long afterSaleId, Long opUserId, String now) {
        requireArgs(afterSaleId, opUserId, now);

        // ── ① 能力闸：必须早于任何写库（见 IRefundSourceAdapter#requireOperable）
        refundSourceAdapter.requireOperable();

        WsAfterSaleAction action = actionMapper.selectByIdIncludingDeleted(afterSaleId);
        if (ObjectUtil.isNull(action)) {
            throw new JbkException("售后动作不存在，拒绝发起退款");
        }
        if (ObjectUtil.notEqual(action.getDataStatus(), 0)) {
            throw new JbkException("售后动作已被逻辑删除，拒绝发起退款");
        }
        // 只有「机构退款」走外部退款；任务书 3.2 明令内部返还不得伪造 ws_refund，此闸即执行点
        if (ObjectUtil.notEqual(action.getActionType(), AfterSaleEnum.ActionType.GATEWAY_REFUND.getValue())) {
            throw new JbkException("该售后动作不是机构退款类型，卡内返还不得写退款单");
        }

        // ── ② 锁定原单与支付单后再取证据：并发的入账事务可能在判定与建单之间把权益发出去
        WsOrder order = refundMapper.lockOrder(action.getOrderId());
        if (ObjectUtil.isNull(order)) {
            throw new JbkException("退款关联订单不存在");
        }
        WsPayment payment = RefundEligibility.requireSinglePayment(refundMapper.lockPaymentsByOrder(order.getId()));
        RefundEligibility.requireMatchingSource(payment, refundSourceAdapter.currentSource());

        RefundEligibility.RefundPath path = ObjectUtil.equals(
                action.getSourceType(), AfterSaleEnum.SourceType.RECHARGE_REFUND.getValue())
                ? RefundEligibility.requireRefundPath(action)
                : RefundEligibility.RefundPath.UNSETTLED_FULL;

        return createRefundForLockedEvidence(action, order, payment, path, opUserId, now);
    }

    /** 已锁定订单与支付单后的统一建单内核；两条充值退款路径不得各复制一份封顶与共键逻辑。 */
    private Long createRefundForLockedEvidence(WsAfterSaleAction action, WsOrder order, WsPayment payment,
                                               RefundEligibility.RefundPath path,
                                               Long opUserId, String now) {
        // 幂等裁决必须核验既有行的不可变共键。逻辑删除或被改写的资金行不能当作成功重放。
        Long afterSaleId = action.getId();
        WsRefund existing = refundMapper.lockByAfterSaleId(afterSaleId);
        if (ObjectUtil.isNotNull(existing)) {
            requireExistingRefundCompatible(existing, action, order, payment, path);
            return existing.getId();
        }

        long refundable = path == RefundEligibility.RefundPath.ENTITLEMENT
                ? requireEntitlementPath(action, order, payment)
                : requireUnsettledPath(order, payment);

        // ── ④ 累计封顶。聚合读带 FOR UPDATE，理由见 WsRefundMapper#sumSucceededByPaymentForUpdate
        long succeeded = refundMapper.sumSucceededByPaymentForUpdate(payment.getId());
        RefundEligibility.requireWithinPaidAmount(payment.getPayAmount(), succeeded, refundable);

        // ── ⑤ 建单。REFUND_SOURCE 取自适配器常量，绝不取自入参或报文
        WsRefund refund = new WsRefund()
                .setRefundNo(RefundNo.derive(afterSaleId))
                .setOrderId(order.getId())
                .setOrderNo(order.getOrderNo())
                .setPaymentId(payment.getId())
                .setAfterSaleId(afterSaleId)
                .setRefundAmount(refundable)
                .setRefundSource(refundSourceAdapter.currentSource())
                .setCurrency(CURRENCY_CNY)
                .setRefundReason(buildReason(action, order, path))
                .setRefundStatus(RefundEnum.RefundStatus.PROCESSING.getValue())
                .setVersion(1)
                .setRetryCount(0);
        refund.setDataStatus(0);
        refund.setCreateBy(opUserId);
        refund.setCreateTime(now);
        refund.setUpdateBy(opUserId);
        refund.setUpdateTime(now);
        // 撞 uk_refund_after_sale / uk_refund_no 即并发重放，整事务回滚，天然幂等
        if (refundMapper.insert(refund) != 1 || refund.getId() == null) {
            throw new JbkException("退款单创建失败");
        }
        return refund.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void fillAcceptance(Long refundId, String providerRefundId, Long opUserId, String now) {
        if (refundId == null || refundId <= 0 || StrUtil.isBlank(providerRefundId)) {
            throw new JbkException("退款受理回填入参非法");
        }
        if (opUserId == null || opUserId <= 0 || now == null || now.length() != TIME_LEN) {
            throw new JbkException("退款受理回填的操作人或业务时间非法");
        }
        // 前态版本必须在本 REQUIRES_NEW 事务内重读：调用方手上是提交前快照，拿它做 CAS 恒 0 行
        WsRefund current = refundMapper.lockById(refundId);
        if (ObjectUtil.isNull(current)) {
            throw new JbkException("退款单不存在，无法回填受理凭据");
        }
        if (StrUtil.isNotBlank(current.getProviderRefundId())) {
            if (StrUtil.equals(current.getProviderRefundId(), providerRefundId)) {
                return;
            }
            throw new JbkException("退款受理的服务方退款单号不一致，转人工核对");
        }
        if (refundMapper.fillAcceptance(refundId, current.getVersion(), providerRefundId, opUserId, now) != 1) {
            throw new JbkException("退款受理凭据回填失败：状态或版本已变");
        }
    }

    /**
     * 权益批次路径（包D-5）：金额取受理时冻结的计划，本方法只核验资格与封顶。
     * 批次只读回查、确认「确实被这笔动作锁着」——锁定动作在受理事务里已做完，本类无批次写能力。
     */
    private long requireEntitlementPath(WsAfterSaleAction action, WsOrder order, WsPayment payment) {
        WsCardEntitlementBatch batch = batchMapper.selectByOrderId(order.getId());
        EntitlementBatchLinkGuard.requireLinked(
                order, batch, action.getCardId(), action.getUserId(), payment.getId());
        boolean lockedByMe = ObjectUtil.isNotNull(batch)
                && ObjectUtil.equals(batch.getBatchStatus(), BATCH_STATUS_REFUND_LOCKED)
                && ObjectUtil.equals(batch.getRefundLockedBy(), action.getId());
        long planned = action.getRefundProductFen() == null ? 0L : action.getRefundProductFen();
        return RefundEligibility.requireEntitlementRefund(
                new RefundEligibility.BatchEvidence(order, payment, lockedByMe, planned));
    }

    /** 已付款未入账异常单的全额退款路径（包B 原有口径，一个字都不放宽）。 */
    private long requireUnsettledPath(WsOrder order, WsPayment payment) {
        String rechargeKey = RechargeCreditTxImpl.bizKey(order.getOrderNo());
        boolean rechargeFlowSeen = refundMapper.countRechargeFlowByBizKey(rechargeKey) > 0;
        boolean issuedCardSeen = refundMapper.countIssuedCardByOrder(order.getId()) > 0;
        return RefundEligibility.requireUnsettledFullRefund(
                new RefundEligibility.Evidence(order, payment, rechargeFlowSeen, issuedCardSeen));
    }

    /** 已有退款单只能作为“同一业务事实”的幂等命中，任何不可变字段漂移都必须拒绝。 */
    private void requireExistingRefundCompatible(WsRefund existing, WsAfterSaleAction action,
                                                 WsOrder order, WsPayment payment,
                                                 RefundEligibility.RefundPath path) {
        long expectedAmount = ObjectUtil.equals(
                action.getSourceType(), AfterSaleEnum.SourceType.RECHARGE_REFUND.getValue())
                ? (action.getRefundProductFen() == null ? 0L : action.getRefundProductFen())
                : (payment.getPayAmount() == null ? 0L : payment.getPayAmount());
        boolean linked = ObjectUtil.equals(existing.getDataStatus(), 0)
                && ObjectUtil.equals(existing.getAfterSaleId(), action.getId())
                && ObjectUtil.equals(existing.getOrderId(), order.getId())
                && StrUtil.equals(existing.getOrderNo(), order.getOrderNo())
                && ObjectUtil.equals(existing.getPaymentId(), payment.getId())
                && StrUtil.equals(existing.getRefundNo(), RefundNo.derive(action.getId()))
                && ObjectUtil.equals(existing.getRefundAmount(), expectedAmount)
                && ObjectUtil.equals(existing.getRefundSource(), refundSourceAdapter.currentSource())
                && StrUtil.equals(existing.getCurrency(), CURRENCY_CNY)
                && existing.getRefundStatus() != null
                && existing.getRefundStatus() >= RefundEnum.RefundStatus.PROCESSING.getValue()
                && existing.getRefundStatus() <= RefundEnum.RefundStatus.RECONCILIATION_REQUIRED.getValue();
        if (!linked) {
            if (ObjectUtil.notEqual(existing.getDataStatus(), 0)) {
                throw new JbkException("既有退款单已删除，拒绝复用");
            }
            throw new JbkException("既有退款单与售后动作、订单或支付证据不一致，转人工核对");
        }
    }

    /** 新入口创建或幂等读回的动作必须与锁内订单、金额和冻结路径逐列一致。 */
    private void requireUnsettledActionCompatible(WsAfterSaleAction action, WsOrder order, long refundable) {
        boolean linked = ObjectUtil.isNotNull(action)
                && ObjectUtil.equals(action.getDataStatus(), 0)
                && ObjectUtil.equals(action.getSourceType(), AfterSaleEnum.SourceType.RECHARGE_REFUND.getValue())
                && ObjectUtil.equals(action.getSourceId(), order.getId())
                && ObjectUtil.equals(action.getOrderId(), order.getId())
                && ObjectUtil.equals(action.getUserId(), order.getUserId())
                && ObjectUtil.equals(action.getCardId(), order.getCardId())
                && ObjectUtil.equals(action.getActionType(), AfterSaleEnum.ActionType.GATEWAY_REFUND.getValue())
                && ObjectUtil.equals(action.getRefundProductFen(), refundable)
                && ObjectUtil.equals(action.getRefundServiceFen(), 0L)
                && ObjectUtil.equals(action.getRefundProductMl(), 0L)
                && ObjectUtil.equals(action.getRefundAmount(), refundable)
                && RefundEligibility.requireRefundPath(action) == RefundEligibility.RefundPath.UNSETTLED_FULL;
        if (!linked) {
            throw new JbkException("既有充值退款动作与订单、支付金额或退款路径不一致，转人工核对");
        }
    }

    private String buildReason(WsAfterSaleAction action, WsOrder order, RefundEligibility.RefundPath path) {
        String head = path == RefundEligibility.RefundPath.ENTITLEMENT
                ? "已入账充值按权益批次折算退款"
                : "已付款未入账异常单全额退款";
        String reason = head + "；售后号 " + action.getAfterSaleNo() + "，订单 " + order.getOrderNo();
        return StrUtil.maxLength(reason, REASON_MAX);
    }

    private void requireArgs(Long afterSaleId, Long opUserId, String now) {
        if (afterSaleId == null || afterSaleId <= 0) {
            throw new JbkException("售后动作ID非法");
        }
        if (opUserId == null || opUserId <= 0) {
            throw new JbkException("退款发起人缺失，拒绝退款");
        }
        if (now == null || now.length() != TIME_LEN) {
            // 不做取当前时间的兜底：会让退款单、事实与审计出现两套时钟
            throw new JbkException("业务时间格式非法，拒绝退款");
        }
    }
}

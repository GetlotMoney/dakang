package com.jbk.serve.service.aftersale.refund.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.jbk.serve.mapper.aftersale.WsRefundEventMapper;
import com.jbk.serve.mapper.aftersale.WsRefundMapper;
import com.jbk.serve.service.aftersale.refund.IEntitlementRefundTxService;
import com.jbk.serve.service.aftersale.refund.IRefundFactService;
import com.jbk.serve.service.aftersale.refund.RefundFact;
import com.jbk.tool.consts.aftersale.RefundEnum;
import com.jbk.tool.data.aftersale.po.WsRefund;
import com.jbk.tool.data.aftersale.po.WsRefundEvent;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款事实收件箱实现（E2E-04 包B，R0-8）。
 *
 * <h3>这条管道要挡住的五类事实</h3>
 * <ol>
 *   <li><b>重复</b>：三元幂等键唯一索引挡住；同键不同正文由摘要比对挡住并转人工。</li>
 *   <li><b>乱序</b>：一条 PROCESSING 在 SUCCESS 之后到达，不得把已成功的单拖回处理中——
 *       {@code markNonSuccess} 的 {@code REFUND_STATUS <> 2} 前态负责。</li>
 *   <li><b>错金额</b>：{@code markSuccess} 把事实金额放进 WHERE，不符即影响 0 行 → 转人工，
 *       而不是用本地金额顶替后记成成功。</li>
 *   <li><b>错订单</b>：事实携带的 ORDER_NO 与本地退款单交叉核对，不符即转人工。</li>
 *   <li><b>迟到失败</b>：R0-7「已成功动作不得被迟到失败事实降级」，同样由
 *       {@code REFUND_STATUS <> 2} 前态负责。</li>
 * </ol>
 *
 * <h3>本类没有创建退款单的能力</h3>
 * <p>只注入了退款单与事实两个 Mapper，且只调用它们的推进方法。
 * 一条找不到对应退款单的事实一律转人工，绝不「顺手建一张」——
 * 那会让外部报文获得凭空创建出账记录的能力。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundFactServiceImpl implements IRefundFactService {

    /** 认领租约：处理者崩溃后，超过这个时长其它处理者可以接手。 */
    private static final long LEASE_SECONDS = 120L;

    /** 可重试失败的退避间隔。 */
    private static final long RETRY_DELAY_SECONDS = 60L;

    /** 重试上限，超过转人工。防的是一条毒事实被无限重放。 */
    private static final int MAX_RETRY = 5;

    private static final int ERROR_MAX = 500;

    private final WsRefundEventMapper eventMapper;
    private final WsRefundMapper refundMapper;
    /** 拒绝证据必须独立事务落库，理由见 RefundAnomalyRecorder 的类注释。 */
    private final RefundAnomalyRecorder anomalyRecorder;
    /**
     * 权益结算（包D-5）：本类依旧<b>没有</b>动卡的能力，只在退款单确实成功之后委托它。
     * 结算自带 REQUIRES_NEW 事务与全套前态断言，失败会抛出，由下面的 catch 分流重试/人工。
     */
    private final IEntitlementRefundTxService entitlementRefundTxService;
    /**
     * 分润冲减（D-420 R2）：与权益结算同位并列委托——退款成功后驱动动作级 outbox
     * 执行（REQUIRES_NEW 对账后动账）。结构性失败抛出走 park 分流重试；证据不合格
     * 在执行段内部整动作转人工；两种情况退款成功事实都不回退。
     */
    private final com.jbk.serve.service.settlement.ISplitClawbackTxService splitClawbackTxService;

    // ==================== 落库 ====================

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Long ingest(RefundFact fact, String now) {
        if (fact == null || now == null || now.length() != 14) {
            throw new JbkException("退款事实落库入参非法");
        }
        String digest = SecureUtil.sha256(fact.rawBody());

        WsRefundEvent event = new WsRefundEvent()
                .setRefundSource(fact.source())
                .setFactChannel(fact.factChannel())
                .setProviderEventKey(fact.providerEventKey())
                .setRefundNo(fact.refundNo())
                .setOrderNo(fact.orderNo())
                .setRefundState(fact.state())
                .setProviderRefundId(fact.providerRefundId())
                .setRefundAmount(fact.amountFen())
                .setCurrency(fact.currency())
                .setRefundSuccessTime(fact.successTime())
                .setRawBody(fact.rawBody())
                .setRawBodySha256(digest)
                .setVerifyMethod(fact.verifyMethod())
                .setProcessingStatus(RefundEnum.ProcessingStatus.PENDING)
                .setRetryCount(0)
                .setReceivedTime(now);
        event.setDataStatus(0);
        event.setCreateBy(0L);
        event.setCreateTime(now);
        event.setUpdateBy(0L);
        event.setUpdateTime(now);

        try {
            eventMapper.insert(event);
            return event.getId();
        } catch (DuplicateKeyException duplicate) {
            // 幂等命中：同一事实此前已到达过。返回既有行，但必须先确认「同键同正文」。
            WsRefundEvent existing = eventMapper.selectByProviderKey(
                    fact.source(), fact.factChannel(), fact.providerEventKey());
            if (ObjectUtil.isNull(existing)) {
                // 撞了键却查不到行：唯一索引与查询口径不一致，属结构性问题，不能猜
                throw new JbkException("退款事实幂等键冲突但查不到既有事实，拒绝继续");
            }
            if (eventMapper.countMatchingDigest(existing.getId(), digest) != 1) {
                // 同一个事实号承载了两份不同内容——伪造、改写或上游串号，本次到达不可信。
                //
                // 只拒绝本次到达并留痕，**不改既有事实的处理状态**：若允许在这里把既有事实
                // park 成需人工对账，任何知道事实键的人发一份垃圾正文就能把一笔正在推进的
                // 合法退款冻住，那是一条现成的拒绝服务路径。既有事实此前已通过摘要校验，
                // 它的可信度不该被一次可疑到达降级。
                // 独立事务留痕：本方法随后抛出，同事务的写入会被一起回滚，
                // 结果就是「拒绝生效了、证据没了」（铁律④）
                anomalyRecorder.record(existing.getId(),
                        trim("收到同键不同正文的可疑事实，已拒绝该次到达；本行内容未受影响"), now);
                throw new JbkException("退款事实同键不同正文，已拒绝本次到达并留痕");
            }
            return existing.getId();
        }
    }

    // ==================== 消费 ====================

    @Override
    public Outcome process(Long eventId, String now) {
        if (eventId == null || eventId <= 0 || now == null || now.length() != 14) {
            throw new JbkException("退款事实消费入参非法");
        }
        // 认领是并发裁决点：抢不到就必须放弃，绝不继续推进退款单
        if (eventMapper.claim(eventId, now, DateUtils.plusSeconds(now, LEASE_SECONDS), 0L) != 1) {
            return Outcome.NOT_CLAIMED;
        }
        WsRefundEvent event = eventMapper.selectDetailById(eventId);
        if (ObjectUtil.isNull(event)) {
            return Outcome.NOT_CLAIMED;
        }
        try {
            return advance(event, now);
        } catch (RuntimeException failure) {
            // 消费失败不得让事实停在 2处理中：那样它只能等租约到期才被别人捡起，
            // 而失败原因也就丢了。可重试的排期，超限或业务性失败转人工。
            boolean retryable = event.getRetryCount() != null && event.getRetryCount() < MAX_RETRY;
            if (retryable) {
                eventMapper.park(eventId, RefundEnum.ProcessingStatus.RETRY_WAIT,
                        DateUtils.plusSeconds(now, RETRY_DELAY_SECONDS), trim(failure.getMessage()), 0L, now);
                return Outcome.RETRY_SCHEDULED;
            }
            parkForReconciliation(eventId, trim(failure.getMessage()), now);
            return Outcome.RECONCILIATION;
        }
    }

    /** 关联 → 交叉核对 → 推进退款单。任一环对不上即转人工，绝不猜。 */
    private Outcome advance(WsRefundEvent event, String now) {
        WsRefund refund = refundMapper.selectByRefundNo(event.getRefundNo());
        if (ObjectUtil.isNull(refund)) {
            // 找不到对应退款单：可能是伪造事实、串号或本地单被删。绝不建单。
            parkForReconciliation(event.getId(), "事实对应的退款单不存在：" + event.getRefundNo(), now);
            return Outcome.RECONCILIATION;
        }
        eventMapper.linkKeys(event.getId(), refund.getId(), refund.getAfterSaleId(), refund.getOrderId(), 0L, now);

        String mismatch = crossCheck(event, refund);
        if (mismatch != null) {
            parkForReconciliation(event.getId(), mismatch, now);
            return Outcome.RECONCILIATION;
        }

        if (RefundEnum.FactState.SUCCESS.equals(event.getRefundState())) {
            return advanceSuccess(event, refund, now);
        }
        if (RefundEnum.FactState.CLOSED.equals(event.getRefundState())
                || RefundEnum.FactState.ABNORMAL.equals(event.getRefundState())) {
            return advanceFailure(event, refund, now);
        }
        // PROCESSING / UNKNOWN：不改退款单状态。
        // PROCESSING 是重复的中间态通知，UNKNOWN 是我们看不懂的东西——
        // 后者刻意不猜也不丢，标记已处理后留在库里供人工检索（LAST_ERROR 已记原因）。
        if (RefundEnum.FactState.UNKNOWN.equals(event.getRefundState())) {
            parkForReconciliation(event.getId(), "无法归类的退款事实状态，转人工判读", now);
            return Outcome.RECONCILIATION;
        }
        eventMapper.markProcessed(event.getId(), 0L, now);
        return Outcome.PROCESSED;
    }

    private Outcome advanceSuccess(WsRefundEvent event, WsRefund refund, String now) {
        if (ObjectUtil.equal(refund.getRefundStatus(), RefundEnum.RefundStatus.SUCCESS.getValue())) {
            // 已成功：重复的成功事实是正常现象（通知重推）。
            // 结算仍要再调一次（它自身幂等）——上一次的结算可能因为锁等待、卡前态漂移等
            // 基础设施原因失败过，退款单却已经是成功。不在这里补，那笔退款的权益冲正
            // 会永远停在「钱退了、卡上权益还在」，而重推的事实是唯一还会经过这里的机会。
            entitlementRefundTxService.settleOnRefundSuccess(refund.getId(), now);
            // 冲减执行段（D-420 R1）：登记已随结算成功事务完成（outbox），此处驱动执行；
            // uk(ACTION_ID,SPLIT_ID)+事实状态机保证重放零副作用
            if (refund.getAfterSaleId() != null) {
                splitClawbackTxService.processAction(refund.getAfterSaleId(), now);
            }
            eventMapper.markProcessed(event.getId(), 0L, now);
            return Outcome.PROCESSED;
        }
        int rows = refundMapper.markSuccess(refund.getId(), refund.getVersion(),
                event.getRefundAmount(), event.getProviderRefundId(),
                event.getRefundSuccessTime(), 0L, now);
        if (rows != 1) {
            // 影响 0 行的可能原因：金额不符、服务方单号不符、状态已变、版本已变。
            // 前两者是账实不符必须人工；后两者是并发，重跑一次即可分辨——
            // 但两类都不该在这里猜，统一转人工比「猜错一次退两次钱」便宜得多。
            parkForReconciliation(event.getId(),
                    "退款成功推进影响 0 行（金额/服务方单号/状态任一不符），拒绝记成成功", now);
            return Outcome.RECONCILIATION;
        }
        // 退款单已成功 → 结算权益：批次冲正、卡聚合冲减、唯一流水、订单终态与卡处置。
        // 它抛出时本方法的调用方会把事实 park 成可重试/人工，而退款单保持成功——
        // 「钱已退」是既成事实，绝不因为结算失败被改回去（R0-7）。
        entitlementRefundTxService.settleOnRefundSuccess(refund.getId(), now);
        // 冲减执行段（D-420 R1）：登记随结算成功事务落 ws_split_clawback（outbox），
        // 此处独立事务执行扣回。抛出走 park 待重试/人工——退款成功事实不回退，
        // 冲减失败停留在事实行状态机（可重试可转人工），绝不伪装退款未发生
        if (refund.getAfterSaleId() != null) {
            splitClawbackTxService.processAction(refund.getAfterSaleId(), now);
        }
        eventMapper.markProcessed(event.getId(), 0L, now);
        return Outcome.PROCESSED;
    }

    private Outcome advanceFailure(WsRefundEvent event, WsRefund refund, String now) {
        if (ObjectUtil.equal(refund.getRefundStatus(), RefundEnum.RefundStatus.SUCCESS.getValue())) {
            // R0-7：已成功不得被迟到失败事实降级。这条事实本身不是错误，只是来晚了，
            // 故标记已处理并留痕，而不是转人工把运营叫醒。
            log.warn("迟到的失败退款事实落在已成功的退款单上，忽略降级：eventId={} refundId={}",
                    event.getId(), refund.getId());
            eventMapper.markProcessed(event.getId(), 0L, now);
            return Outcome.PROCESSED;
        }
        int rows = refundMapper.markNonSuccess(refund.getId(), refund.getVersion(),
                RefundEnum.RefundStatus.FAILED.getValue(), null,
                trim("服务方退款事实：" + event.getRefundState()), 0L, now);
        if (rows != 1) {
            parkForReconciliation(event.getId(), "退款失败推进影响 0 行，状态或版本已变", now);
            return Outcome.RECONCILIATION;
        }
        // 退款失败：售后动作转需人工对账，权益批次<b>保持退款锁定不自动解除</b>
        // （任务书 3.4：退款永久失败时不得自动恢复为未退款）。自动解锁会让一笔
        // 可能已在服务方侧出款的退款重新变成可消费，那是双花窗口。
        entitlementRefundTxService.parkOnRefundFailure(refund.getId(),
                "服务方退款事实：" + event.getRefundState(), now);
        eventMapper.markProcessed(event.getId(), 0L, now);
        return Outcome.PROCESSED;
    }

    /**
     * 事实与本地退款单的交叉核对。
     *
     * <p>只核对<b>事实真的携带了</b>的字段：服务方在非成功态下可能不返回金额与订单号，
     * 此时缺失是正常的，把缺失当成不符会把大量正常事实推进人工队列。
     * 而 SUCCESS 态的这些字段已由 {@link RefundFact} 在入口强制非空，不会走到宽松分支。</p>
     */
    private String crossCheck(WsRefundEvent event, WsRefund refund) {
        if (ObjectUtil.notEqual(event.getRefundSource(), refund.getRefundSource())) {
            return "事实来源与退款单来源不一致（事实 " + event.getRefundSource()
                    + "，退款单 " + refund.getRefundSource() + "）";
        }
        if (StrUtil.isNotBlank(event.getOrderNo()) && StrUtil.isNotBlank(refund.getOrderNo())
                && !StrUtil.equals(event.getOrderNo(), refund.getOrderNo())) {
            return "事实携带的订单号与退款单不一致";
        }
        if (event.getRefundAmount() != null && refund.getRefundAmount() != null
                && !event.getRefundAmount().equals(refund.getRefundAmount())) {
            return "事实退款金额与退款单不一致（事实 " + event.getRefundAmount()
                    + "，退款单 " + refund.getRefundAmount() + "）";
        }
        if (StrUtil.isNotBlank(event.getCurrency()) && StrUtil.isNotBlank(refund.getCurrency())
                && !StrUtil.equals(event.getCurrency(), refund.getCurrency())) {
            return "事实币种与退款单不一致";
        }
        if (StrUtil.isNotBlank(event.getProviderRefundId()) && StrUtil.isNotBlank(refund.getProviderRefundId())
                && !StrUtil.equals(event.getProviderRefundId(), refund.getProviderRefundId())) {
            return "事实服务方退款单号与受理时回填的不一致";
        }
        return null;
    }

    private void parkForReconciliation(Long eventId, String reason, String now) {
        eventMapper.park(eventId, RefundEnum.ProcessingStatus.RECONCILIATION_REQUIRED,
                null, trim(reason), 0L, now);
    }

    /** 错因写库前统一截断：LAST_ERROR 是 varchar(500)，超长会整条 UPDATE 失败而把真正的错因也丢掉。 */
    private String trim(String reason) {
        return StrUtil.maxLength(StrUtil.blankToDefault(reason, "未知原因"), ERROR_MAX);
    }
}

package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.jbk.serve.mapper.mall.WsMallRefundFactMapper;
import com.jbk.serve.mapper.mall.WsMallRefundMapper;
import com.jbk.serve.service.mall.IMallPayApplyTx;
import com.jbk.serve.service.mall.IMallRefundApplyTx;
import com.jbk.serve.service.mall.IMallRefundFactService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.data.mall.po.WsMallRefund;
import com.jbk.tool.data.mall.po.WsMallRefundFact;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商城退款事实服务实现（事务A，E2E-09 S4）。
 *
 * <p>结构与支付事实完全同形：事实先落库留证，推进交给事务B；同键重放复用原行并逐字核对
 * 正文，改参一律拒绝——同键携带被篡改的金额或交易号时静默返回旧行，等于我们自己给篡改盖章。</p>
 *
 * <p>本段**不改**订单、库存与售后状态：渠道说退成功只是一个事实，它是否该推进由事务B
 * 在锁内重读全部共键后决定。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
@Slf4j
@Service
public class MallRefundFactServiceImpl implements IMallRefundFactService {

    private static final int LEASE_SECONDS = 300;
    private static final int RETRY_BACKOFF_SECONDS = 60;
    private static final int MAX_ERROR_LEN = 500;
    /** 未分类/瞬时失败的重试上限：到顶转人工，绝不无限循环。 */
    private static final int MAX_RETRY = 5;

    @Autowired
    private WsMallRefundFactMapper factMapper;
    @Autowired
    private WsMallRefundMapper refundMapper;
    @Autowired
    private IMallRefundApplyTx applyTx;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class,
            isolation = Isolation.READ_COMMITTED)
    public WsMallRefundFact recordFact(Integer refundSource, Integer factChannel,
                                       String providerEventKey, String refundNo, String orderNo,
                                       String refundState, String refundTransactionId,
                                       Long refundAmountFen, String refundSuccessTime,
                                       int verifyMethod, String rawBody) {
        if (StrUtil.isBlank(refundState)) {
            // REFUND_STATE 是 NOT NULL 列：没有状态的事实存下去也只会被推进段当成"不是成功"
            throw new JbkException("退款事实缺状态，已拒绝（请人工核查）");
        }
        String body = StrUtil.blankToDefault(rawBody, "");
        String bodySha = DigestUtil.sha256Hex(body);
        WsMallRefundFact existed = factMapper.selectByKeyIncludingDeleted(
                refundSource, factChannel, providerEventKey);
        if (ObjectUtil.isNotNull(existed)) {
            requireSameFact(existed, refundNo, orderNo, refundState, refundTransactionId,
                    refundAmountFen, verifyMethod, bodySha);
            return existed;
        }

        String now = DateUtils.time();
        WsMallRefund refund = refundMapper.selectByNoIncludingDeleted(refundNo);
        String rejection = MallEnum.RefundState.isKnown(refundState)
                ? rejectionOfSuccessFact(refundState, refundSource, refundTransactionId,
                        refundAmountFen, refundSuccessTime, refund)
                : "未知退款状态，不做语义猜测：" + refundState;

        WsMallRefundFact fact = new WsMallRefundFact()
                .setRefundSource(refundSource)
                .setFactChannel(factChannel)
                .setProviderEventKey(providerEventKey)
                .setOrderNo(orderNo)
                .setRefundNo(refundNo)
                .setRefundState(refundState)
                .setRefundTransactionId(refundTransactionId)
                .setRefundAmountFen(refundAmountFen)
                .setCurrency(ObjectUtil.isNull(refund) ? null : refund.getCurrency())
                .setRefundSuccessTime(refundSuccessTime)
                .setRawBody(body)
                .setRawBodySha256(bodySha)
                .setVerifyMethod(verifyMethod)
                .setProcessingStatus(rejection == null
                        ? MallEnum.RefundFactStatus.PENDING.getValue()
                        : MallEnum.RefundFactStatus.NEED_RECONCILE.getValue())
                .setLastError(rejection)
                .setRetryCount(0)
                .setReceivedTime(now);
        if (ObjectUtil.isNotNull(refund)) {
            fact.setRefundId(refund.getId()).setAfterSaleId(refund.getAfterSaleId());
        }
        if (rejection != null) {
            fact.setProcessedTime(now);
        }
        try {
            factMapper.insert(fact);
        }
        catch (DuplicateKeyException race) {
            WsMallRefundFact winner = factMapper.selectByKeyIncludingDeleted(
                    refundSource, factChannel, providerEventKey);
            if (ObjectUtil.isNull(winner)) {
                throw new JbkException("退款事实写入冲突，请重试");
            }
            requireSameFact(winner, refundNo, orderNo, refundState, refundTransactionId,
                    refundAmountFen, verifyMethod, bodySha);
            return winner;
        }
        if (rejection != null) {
            log.warn("商城退款事实不可推进，已留证转对账 factId={} reason={}", fact.getId(), rejection);
        }
        return fact;
    }

    /**
     * 成功退款事实的准入判据；返回 null 表示可推进。
     *
     * <p>这些校验必须发生在事实被标为可推进之前：一条金额不符的伪造事实如果先被标成待处理，
     * 事务B 还要再拦一次，而中间那段时间它在收件箱里看起来是"正常待处理"。</p>
     */
    private static String rejectionOfSuccessFact(String refundState, Integer refundSource,
                                                 String transactionId, Long amountFen,
                                                 String successTime, WsMallRefund refund) {
        if (!MallEnum.RefundState.SUCCESS.equals(refundState)) {
            return null;
        }
        if (ObjectUtil.isNull(refund) || !ObjectUtil.equal(refund.getDataStatus(), 0)) {
            return "退款单不存在或已删除";
        }
        if (!ObjectUtil.equal(refundSource, refund.getRefundSource())) {
            return "退款来源与退款单不一致";
        }
        if (StrUtil.isBlank(transactionId)) {
            return "成功退款事实缺渠道交易号";
        }
        if (ObjectUtil.isNull(amountFen)
                || !ObjectUtil.equal(amountFen, refund.getRefundAmountFen())) {
            return "退款金额与退款单不一致";
        }
        if (!DateUtils.isCanonicalBusinessTime(successTime)) {
            return "成功退款事实的时间不是合法业务时间";
        }
        return null;
    }

    /** 同键重放的正文一致性：不可变内容必须逐字相同。 */
    private static void requireSameFact(WsMallRefundFact existed, String refundNo, String orderNo,
                                        String refundState, String transactionId,
                                        Long amountFen, int verifyMethod, String bodySha) {
        boolean same = ObjectUtil.equal(existed.getRefundNo(), refundNo)
                && ObjectUtil.equal(existed.getOrderNo(), orderNo)
                && ObjectUtil.equal(existed.getRefundState(), refundState)
                && ObjectUtil.equal(existed.getRefundTransactionId(), transactionId)
                && ObjectUtil.equal(existed.getRefundAmountFen(), amountFen)
                && ObjectUtil.equal(existed.getVerifyMethod(), verifyMethod)
                && ObjectUtil.equal(existed.getRawBodySha256(), bodySha);
        if (!same) {
            throw new JbkException("同一退款事实键携带了不一致的内容，已拒绝（请人工核查）");
        }
    }

    @Override
    public WsMallRefundFact findFact(Integer refundSource, Integer factChannel,
                                     String providerEventKey) {
        return factMapper.selectByKeyIncludingDeleted(refundSource, factChannel, providerEventKey);
    }

    @Override
    public IMallPayApplyTx.Outcome process(Long refundFactId) {
        String now = DateUtils.time();
        String leaseUntil = DateUtils.plusSeconds(now, LEASE_SECONDS);
        if (factMapper.claimFact(refundFactId, now, leaseUntil) != 1) {
            return describeUnclaimable(refundFactId);
        }
        IMallPayApplyTx.Outcome outcome;
        try {
            outcome = applyTx.apply(refundFactId);
        }
        catch (RuntimeException failure) {
            outcome = classifyFailure(refundFactId, failure);
        }
        String done = DateUtils.time();
        switch (outcome.code()) {
            case APPLIED, ALREADY -> factMapper.markProcessed(refundFactId, done);
            case RECONCILE -> factMapper.markNeedReconcile(refundFactId,
                    StrUtil.maxLength(outcome.message(), MAX_ERROR_LEN), done);
            case RETRY -> factMapper.markRetry(refundFactId,
                    DateUtils.plusSeconds(done, RETRY_BACKOFF_SECONDS),
                    StrUtil.maxLength(outcome.message(), MAX_ERROR_LEN), done);
            default -> throw new JbkException("未知退款推进结论");
        }
        return outcome;
    }

    /**
     * 失败分类：确定性失败直接转人工，只有瞬时失败才排重试。
     *
     * <p>把两者混为一谈的代价是：一条永远不会自愈的错误会被重试到上限才被人看见，
     * 中间每一轮都在浪费租约并掩盖真正的原因。</p>
     */
    private IMallPayApplyTx.Outcome classifyFailure(Long factId, RuntimeException failure) {
        if (failure instanceof JbkException
                || failure instanceof DataIntegrityViolationException) {
            return IMallPayApplyTx.Outcome.reconcile("确定性失败：" + failure.getMessage());
        }
        WsMallRefundFact fact = factMapper.selectById(factId);
        int retried = ObjectUtil.isNull(fact) || fact.getRetryCount() == null
                ? 0 : fact.getRetryCount();
        if (retried >= MAX_RETRY) {
            return IMallPayApplyTx.Outcome.reconcile("重试已达上限，转人工：" + failure.getMessage());
        }
        log.warn("商城退款推进瞬时失败，将重试 factId={}", factId, failure);
        return IMallPayApplyTx.Outcome.retry(failure.getMessage());
    }

    /** 认领失败：区分「已终态」与「被别人持有」，两者结论不同。 */
    private IMallPayApplyTx.Outcome describeUnclaimable(Long factId) {
        WsMallRefundFact fact = factMapper.selectById(factId);
        if (ObjectUtil.isNull(fact)) {
            return IMallPayApplyTx.Outcome.reconcile("退款事实不存在");
        }
        int status = fact.getProcessingStatus() == null ? 0 : fact.getProcessingStatus();
        if (status == MallEnum.RefundFactStatus.PROCESSED.getValue()) {
            return IMallPayApplyTx.Outcome.already("退款事实已处理");
        }
        if (status == MallEnum.RefundFactStatus.NEED_RECONCILE.getValue()) {
            return IMallPayApplyTx.Outcome.reconcile(
                    StrUtil.blankToDefault(fact.getLastError(), "退款事实待人工对账"));
        }
        return IMallPayApplyTx.Outcome.retry("退款事实正被其他处理者持有");
    }
}

package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.jbk.serve.mapper.mall.WsMallOrderMapper;
import com.jbk.serve.mapper.mall.WsMallPaymentFactMapper;
import com.jbk.serve.mapper.mall.WsMallPaymentMapper;
import com.jbk.serve.service.mall.IMallPayApplyTx;
import com.jbk.serve.service.mall.IMallPayFactService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.data.mall.po.WsMallOrder;
import com.jbk.tool.data.mall.po.WsMallPayment;
import com.jbk.tool.data.mall.po.WsMallPaymentFact;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商城支付事实收件箱实现（E2E-09 S2）。
 *
 * <p>认领租约 {@value #LEASE_SECONDS} 秒：进程崩溃后事实不会永久卡在"处理中"，
 * 租约到期即可被别的实例重新捞起。重试退避 {@value #RETRY_BACKOFF_SECONDS} 秒，
 * 避免瞬时故障时把数据库打满。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Slf4j
@Service
public class MallPayFactServiceImpl implements IMallPayFactService {

    private static final int LEASE_SECONDS = 300;
    private static final int RETRY_BACKOFF_SECONDS = 60;
    private static final int MAX_ERROR_LEN = 500;
    /** 未分类/瞬时失败的重试上限：到顶转人工，绝不无限循环。 */
    private static final int MAX_RETRY = 5;
    /** 支付回调与 Pay-Sim 推进属系统动作，审计人写 0；订单主键不是操作人。 */
    private static final long SYSTEM_OPERATOR = 0L;

    @Autowired
    private WsMallPaymentFactMapper factMapper;
    @Autowired
    private WsMallPaymentMapper paymentMapper;
    @Autowired
    private WsMallOrderMapper orderMapper;
    @Autowired
    private IMallPayApplyTx applyTx;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class,
            isolation = Isolation.READ_COMMITTED)
    public WsMallPaymentFact recordFact(Integer paySource, Integer factChannel,
                                        String providerEventKey, String orderNo, String tradeState,
                                        String transactionId, Long payAmountFen,
                                        String paySuccessTime, int verifyMethod, String rawBody) {
        if (StrUtil.isBlank(tradeState)) {
            // TRADE_STATE 是 NOT NULL 列，空白值连"留证"都做不到——存下去也只是一条没有
            // 状态的事实，随后会被推进段当成"反正不是成功"而盖成已处理。整笔拒绝更诚实。
            throw new JbkException("支付事实缺交易状态，已拒绝（请人工核查）");
        }
        String body = StrUtil.blankToDefault(rawBody, "");
        String bodySha = DigestUtil.sha256Hex(body);
        WsMallPaymentFact existed = factMapper.selectByKeyIncludingDeleted(
                paySource, factChannel, providerEventKey);
        if (ObjectUtil.isNotNull(existed)) {
            // 重复事实：逐字核对不可变内容后原样复用，绝不改写已存档的外部证据
            requireSameFact(existed, factChannel, orderNo, tradeState, transactionId, payAmountFen,
                    paySuccessTime, verifyMethod, bodySha);
            return existed;
        }

        String now = DateUtils.time();
        WsMallPayment payment = paymentMapper.selectByOrderNoIncludingDeleted(orderNo);
        WsMallOrder order = ObjectUtil.isNull(payment) ? null
                : orderMapper.selectByOrderNoIncludingDeleted(orderNo);
        boolean success = MallEnum.TradeState.SUCCESS.equals(tradeState);
        // 未知状态先于一切判据拦下：留证转人工，绝不因为"它不是 SUCCESS"就当作无需处理
        String rejection;
        if (!MallEnum.TradeState.isKnown(tradeState)) {
            rejection = "未知交易状态，不做语义猜测：" + tradeState;
        }
        else if (success) {
            // 成功事实的准入判据：不合格的事实照样留证，但绝不允许它把支付单推成成功
            rejection = rejectionOfSuccessFact(paySource, transactionId, payAmountFen,
                    paySuccessTime, order, payment);
        }
        else {
            rejection = null;
        }

        WsMallPaymentFact fact = new WsMallPaymentFact()
                .setPaySource(paySource)
                .setFactChannel(factChannel)
                .setProviderEventKey(providerEventKey)
                .setOrderNo(orderNo)
                .setTradeState(tradeState)
                .setTransactionId(transactionId)
                .setPayAmountFen(payAmountFen)
                .setCurrency(success ? "CNY" : null)
                .setPaySuccessTime(paySuccessTime)
                .setRawBody(body)
                .setRawBodySha256(bodySha)
                .setVerifyMethod(verifyMethod)
                .setProcessingStatus(rejection == null
                        ? MallEnum.PayFactStatus.PENDING.getValue()
                        : MallEnum.PayFactStatus.NEED_RECONCILE.getValue())
                .setLastError(rejection)
                .setRetryCount(0)
                .setReceivedTime(now);
        if (ObjectUtil.isNotNull(payment)) {
            fact.setPaymentId(payment.getId()).setOrderId(payment.getOrderId());
        }
        if (rejection != null) {
            fact.setProcessedTime(now);
        }
        try {
            factMapper.insert(fact);
        }
        catch (DuplicateKeyException race) {
            // 并发同事实：复用先写者那一行，但同样要核对内容一致
            WsMallPaymentFact winner = factMapper.selectByKeyIncludingDeleted(
                    paySource, factChannel, providerEventKey);
            if (ObjectUtil.isNull(winner)) {
                throw new JbkException("支付事实写入冲突，请重试");
            }
            requireSameFact(winner, factChannel, orderNo, tradeState, transactionId, payAmountFen,
                    paySuccessTime, verifyMethod, bodySha);
            return winner;
        }
        if (rejection != null) {
            log.warn("商城支付事实不可推进，已留证转对账 factId={} reason={}", fact.getId(), rejection);
            return fact;
        }
        // 只有通过准入的成功事实才推进支付单 1→2
        if (success) {
            int moved = paymentMapper.casSuccess(payment.getId(), transactionId, paySuccessTime,
                    SYSTEM_OPERATOR, now);
            if (moved != 1) {
                // 0 行只有一种合法解释：同一笔交易的精确重放。其余（已被别的交易号置成功、
                // 已关闭、已失败）都意味着这笔钱与这张支付单对不上，必须留证转人工。
                WsMallPayment latest = paymentMapper.selectByOrderNoIncludingDeleted(orderNo);
                if (!isExactIdempotentSuccess(latest, paySource, transactionId, paySuccessTime)) {
                    String reason = "支付单状态与本次成功事实不一致（交易号或来源不符），需人工对账";
                    factMapper.markNeedReconcileFromPending(fact.getId(), reason, now);
                    fact.setProcessingStatus(MallEnum.PayFactStatus.NEED_RECONCILE.getValue())
                            .setLastError(reason);
                    log.warn("商城支付单推进被拒 orderNo={} factId={}", orderNo, fact.getId());
                }
            }
        }
        return fact;
    }

    /**
     * 成功事实的准入判据；返回 null 表示可推进，否则为拒绝原因。
     *
     * <p>这些校验必须发生在支付单被改动之前：一条金额不符的伪造事实如果先把支付单写成
     * 成功，即便后续实销被拦下，账面上这张单也已经是"已支付"了。</p>
     *
     * <p>判据本体在 {@link MallPayFactGate}，与事务B 共用一份。新事实此刻尚未落库，
     * 故 fact 侧的 ORDER_ID/PAYMENT_ID 传 null——它们本就是从支付单反推出来的。</p>
     */
    private String rejectionOfSuccessFact(Integer paySource, String transactionId,
                                          Long payAmountFen, String paySuccessTime,
                                          WsMallOrder order, WsMallPayment payment) {
        return MallPayFactGate.linkMismatch(null, null, paySource, payAmountFen, "CNY",
                transactionId, paySuccessTime, order, payment);
    }

    /** 支付单是否已经是"同一笔交易"的成功态——只有这一种情况允许把 0 行当作幂等。 */
    private static boolean isExactIdempotentSuccess(WsMallPayment latest, Integer paySource,
                                                    String transactionId, String paySuccessTime) {
        return ObjectUtil.isNotNull(latest)
                && ObjectUtil.equal(latest.getDataStatus(), 0)
                && ObjectUtil.equal(latest.getPayStatus(), MallEnum.PayStatus.SUCCESS.getValue())
                && ObjectUtil.equal(latest.getPaySource(), paySource)
                && ObjectUtil.equal(latest.getTransactionId(), transactionId)
                && ObjectUtil.equal(latest.getPaySuccessTime(), paySuccessTime);
    }

    /**
     * 同键重放的正文一致性：不可变内容必须逐字相同。
     *
     * <p>事实键只保证"同一个外部事件"，不保证"同样的内容"。同键携带被篡改的金额、
     * 交易号或订单号时，若静默当作合法重放返回旧行，篡改就被我们自己盖章确认了。</p>
     */
    private static void requireSameFact(WsMallPaymentFact existed, Integer factChannel,
                                        String orderNo, String tradeState,
                                        String transactionId, Long payAmountFen,
                                        String paySuccessTime, int verifyMethod, String bodySha) {
        boolean same = ObjectUtil.equal(existed.getOrderNo(), orderNo)
                && ObjectUtil.equal(existed.getTradeState(), tradeState)
                && ObjectUtil.equal(existed.getTransactionId(), transactionId)
                && ObjectUtil.equal(existed.getPayAmountFen(), payAmountFen)
                && ObjectUtil.equal(existed.getVerifyMethod(), verifyMethod);

        // 微信签名通知：正文摘要**不参与**同一性判定。微信重投会重新加密同一笔交易
        // （resource.nonce 变化则密文与整个信封字节全变），按摘要比对会把合法重投判成篡改，
        // 于是持续回 500、微信重试三天后放弃，一笔已收的款永远进不了账。
        // 判定改用解密后的不可变语义字段——它们才是"同一事实"的定义。
        // 严格限定在 NOTIFY + 微信签名：其余渠道的正文一致性规则一个字都不放宽。
        boolean wechatSignedNotify =
                ObjectUtil.equal(factChannel, MallEnum.FactChannel.NOTIFY.getValue())
                        && verifyMethod == MallEnum.VerifyMethod.WECHAT_SIGNATURE;
        if (same && !wechatSignedNotify) {
            same = ObjectUtil.equal(existed.getRawBodySha256(), bodySha);
        }

        // 支付成功时间只对**外部**事实是不可变证据。Pay-Sim 是"支付方即我方"，那个时间由
        // 我们自己在调用时刻取，两次并发调用跨过整秒就会得到不同的秒值——那不是篡改，
        // 把它判成篡改会让合法重试拿到"请人工核查"。外部渠道（含微信通知）仍逐字比对。
        boolean selfIssued = ObjectUtil.equal(factChannel, MallEnum.FactChannel.PAY_SIM.getValue());
        if (same && !selfIssued) {
            same = ObjectUtil.equal(existed.getPaySuccessTime(), paySuccessTime);
        }
        if (!same) {
            throw new JbkException("同一支付事实键携带了不一致的内容，已拒绝（请人工核查）");
        }
    }

    @Override
    public WsMallPaymentFact findFact(Integer paySource, Integer factChannel,
                                      String providerEventKey) {
        return factMapper.selectByKeyIncludingDeleted(paySource, factChannel, providerEventKey);
    }

    @Override
    public IMallPayApplyTx.Outcome process(Long factId) {
        String now = DateUtils.time();
        String leaseUntil = DateUtils.plusSeconds(now, LEASE_SECONDS);
        if (factMapper.claimFact(factId, now, leaseUntil) != 1) {
            return describeUnclaimable(factId);
        }
        IMallPayApplyTx.Outcome outcome;
        try {
            outcome = applyTx.apply(factId);
        }
        catch (RuntimeException failure) {
            return classifyFailure(factId, failure);
        }
        String finished = DateUtils.time();
        switch (outcome.code()) {
            case APPLIED, ALREADY -> factMapper.markProcessed(factId, finished);
            case RECONCILE -> {
                factMapper.markNeedReconcile(factId, safeText(outcome.message()), finished);
                log.warn("商城支付事实需人工对账 factId={} reason={}", factId, outcome.message());
            }
            default -> throw new IllegalStateException("未知推进结果：" + outcome.code());
        }
        return outcome;
    }

    /**
     * 失败分类：确定性失败直接转人工，瞬时失败才重试，且重试有上限。
     *
     * <p>不分类的代价很具体：预占证据损坏这类失败重试一万次也不会自愈，Worker 会以
     * 30 秒一轮的节奏永久空转，把真正需要重试的事实挤在后面，而运维看到的只是
     * "一直在重试"，没有任何人被叫去处理。</p>
     */
    private IMallPayApplyTx.Outcome classifyFailure(Long factId, RuntimeException failure) {
        String now = DateUtils.time();
        String message = safeMessage(failure);
        if (isDeterministic(failure)) {
            factMapper.markNeedReconcile(factId, message, now);
            log.warn("商城支付事实确定性失败转人工 factId={} reason={}", factId, message);
            return IMallPayApplyTx.Outcome.reconcile(message);
        }
        WsMallPaymentFact current = factMapper.selectById(factId);
        int retried = ObjectUtil.isNull(current) || ObjectUtil.isNull(current.getRetryCount())
                ? 0 : current.getRetryCount();
        if (retried + 1 >= MAX_RETRY) {
            String reason = "重试 " + MAX_RETRY + " 次仍未成功：" + message;
            factMapper.markNeedReconcile(factId, safeText(reason), now);
            log.error("商城支付事实重试耗尽转人工 factId={} reason={}", factId, reason);
            return IMallPayApplyTx.Outcome.reconcile(reason);
        }
        factMapper.markRetry(factId, DateUtils.plusSeconds(now, RETRY_BACKOFF_SECONDS),
                message, now);
        log.warn("商城支付事实瞬时失败待重试 factId={} 第{}次 reason={}", factId, retried + 1, message);
        return IMallPayApplyTx.Outcome.retry(message);
    }

    /**
     * 确定性失败=重试不可能改变结果：业务共键错误、预占证据不足、明细损坏（JbkException），
     * 以及约束冲突（DataIntegrityViolationException）。
     * 瞬时失败（死锁、锁超时、连接中断）都是 Spring 的 TransientDataAccessException 家族。
     * 两者都不是时按未分类处理——走有限重试，超上限同样转人工。
     */
    private static boolean isDeterministic(RuntimeException failure) {
        return failure instanceof JbkException
                || failure instanceof org.springframework.dao.DataIntegrityViolationException;
    }

    /** 认领失败时如实描述当前状态，不臆断成功。 */
    private IMallPayApplyTx.Outcome describeUnclaimable(Long factId) {
        WsMallPaymentFact current = factMapper.selectById(factId);
        if (ObjectUtil.isNull(current)) {
            return IMallPayApplyTx.Outcome.reconcile("支付事实不存在");
        }
        int status = current.getProcessingStatus() == null ? 0 : current.getProcessingStatus();
        if (status == MallEnum.PayFactStatus.PROCESSED.getValue()) {
            return IMallPayApplyTx.Outcome.already("该支付事实已处理完成");
        }
        if (status == MallEnum.PayFactStatus.NEED_RECONCILE.getValue()) {
            return IMallPayApplyTx.Outcome.reconcile(
                    StrUtil.blankToDefault(current.getLastError(), "该支付事实待人工对账"));
        }
        return IMallPayApplyTx.Outcome.already("该支付事实正在处理中");
    }

    private static String safeMessage(RuntimeException e) {
        return safeText(e instanceof JbkException ? e.getMessage() : e.getClass().getSimpleName());
    }

    private static String safeText(String raw) {
        String text = StrUtil.blankToDefault(raw, "未知原因");
        return text.length() > MAX_ERROR_LEN ? text.substring(0, MAX_ERROR_LEN) : text;
    }
}

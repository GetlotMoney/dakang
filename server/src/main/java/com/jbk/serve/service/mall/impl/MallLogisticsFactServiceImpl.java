package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.jbk.serve.mapper.mall.WsMallLogisticsEventMapper;
import com.jbk.serve.mapper.mall.WsMallShipmentMapper;
import com.jbk.serve.service.mall.IMallLogisticsApplyTx;
import com.jbk.serve.service.mall.IMallLogisticsFactService;
import com.jbk.serve.service.mall.IMallPayApplyTx;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.data.mall.po.WsMallLogisticsEvent;
import com.jbk.tool.data.mall.po.WsMallShipment;
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
 * 物流事实服务实现（事务A，E2E-09 L1）。
 *
 * <p>结构与支付/退款事实完全同形：事实先落库留证，推进交给事务B；同键重放复用原行
 * 并逐字核对正文，改参一律拒绝——同键携带被篡改的运单号或状态时静默返回旧行，
 * 等于我们自己给篡改盖章。</p>
 *
 * <p>本段**不改**包裹与履约状态：承运方说送达了只是一个事实，它是否该推进由事务B
 * 在锁内重读全部共键后决定。</p>
 *
 * @author dakang
 * @since 2026-08-11
 */
@Slf4j
@Service
public class MallLogisticsFactServiceImpl implements IMallLogisticsFactService {

    private static final int LEASE_SECONDS = 300;
    private static final int MAX_ERROR_LEN = 500;
    /** 未分类/瞬时失败的重试上限：到顶转人工，绝不无限循环。 */
    private static final int MAX_RETRY = 5;

    @Autowired
    private WsMallLogisticsEventMapper eventMapper;
    @Autowired
    private WsMallShipmentMapper shipmentMapper;
    @Autowired
    private IMallLogisticsApplyTx applyTx;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class,
            isolation = Isolation.READ_COMMITTED)
    public WsMallLogisticsEvent recordFact(String providerCode, int factChannel,
                                           String providerEventKey, String waybillNo,
                                           String eventState, String eventTime, String eventDesc,
                                           int verifyMethod, String rawBody) {
        if (StrUtil.isBlank(eventState)) {
            // EVENT_STATE 是 NOT NULL 列：没有状态的事实存下去也只会被推进段当成「不认识」
            throw new JbkException("物流事实缺状态，已拒绝（请人工核查）");
        }
        if (StrUtil.isBlank(waybillNo)) {
            throw new JbkException("物流事实缺运单号，已拒绝（请人工核查）");
        }
        String body = StrUtil.blankToDefault(rawBody, "");
        String bodySha = DigestUtil.sha256Hex(body);
        WsMallLogisticsEvent existed = eventMapper.selectByKeyIncludingDeleted(
                providerCode, factChannel, providerEventKey);
        if (ObjectUtil.isNotNull(existed)) {
            requireSameFact(existed, waybillNo, eventState, eventTime, verifyMethod, bodySha);
            return existed;
        }

        String now = DateUtils.time();
        WsMallShipment shipment = shipmentMapper.selectByWaybill(providerCode, waybillNo);
        String rejection = rejectionOf(eventState, eventTime, shipment);

        WsMallLogisticsEvent event = new WsMallLogisticsEvent()
                .setProviderCode(providerCode)
                .setFactChannel(factChannel)
                .setProviderEventKey(providerEventKey)
                .setShipmentId(ObjectUtil.isNull(shipment) ? null : shipment.getId())
                .setWaybillNo(waybillNo)
                .setEventState(eventState)
                .setEventTime(eventTime)
                .setEventDesc(eventDesc)
                .setRawBody(body)
                .setRawBodySha256(bodySha)
                .setVerifyMethod(verifyMethod)
                .setProcessingStatus(rejection == null
                        ? MallEnum.LogisticsProcessing.PENDING.getValue()
                        : MallEnum.LogisticsProcessing.NEED_MANUAL.getValue())
                .setLastError(rejection)
                .setRetryCount(0)
                .setReceivedTime(now);
        event.setCreateTime(now);
        event.setUpdateTime(now);
        if (rejection != null) {
            event.setProcessedTime(now);
        }
        try {
            eventMapper.insert(event);
        }
        catch (DuplicateKeyException race) {
            WsMallLogisticsEvent winner = eventMapper.selectByKeyIncludingDeleted(
                    providerCode, factChannel, providerEventKey);
            if (ObjectUtil.isNull(winner)) {
                throw new JbkException("物流事实写入冲突，请重试");
            }
            requireSameFact(winner, waybillNo, eventState, eventTime, verifyMethod, bodySha);
            return winner;
        }
        if (rejection != null) {
            log.warn("物流事实不可推进，已留证转对账 eventId={} reason={}", event.getId(), rejection);
        }
        return event;
    }

    /**
     * 事实准入判据；返回 null 表示可推进。
     *
     * <p>这些校验必须发生在事实被标为可推进之前：一条运单号对不上任何包裹的伪造事实
     * 如果先被标成待处理，中间那段时间它在收件箱里看起来是「正常待处理」。</p>
     */
    private static String rejectionOf(String eventState, String eventTime,
                                      WsMallShipment shipment) {
        if (!MallEnum.LogisticsEventState.isKnown(eventState)) {
            return "未知物流事件状态，不做语义猜测：" + eventState;
        }
        if (!DateUtils.isCanonicalBusinessTime(eventTime)) {
            return "物流事实的事件时间不是合法业务时间";
        }
        if (ObjectUtil.isNull(shipment) || !ObjectUtil.equal(shipment.getDataStatus(), 0)) {
            return "运单号未匹配到任何有效包裹";
        }
        if (!ObjectUtil.equal(shipment.getFulfillMode(),
                MallEnum.FulfillMode.THIRD_PARTY.getValue())) {
            return "自营包裹不接受第三方物流事实";
        }
        return null;
    }

    /** 同键重放的正文一致性：不可变内容必须逐字相同。 */
    private static void requireSameFact(WsMallLogisticsEvent existed, String waybillNo,
                                        String eventState, String eventTime, int verifyMethod,
                                        String bodySha) {
        boolean same = ObjectUtil.equal(existed.getWaybillNo(), waybillNo)
                && ObjectUtil.equal(existed.getEventState(), eventState)
                && ObjectUtil.equal(existed.getEventTime(), eventTime)
                && ObjectUtil.equal(existed.getVerifyMethod(), verifyMethod)
                && ObjectUtil.equal(existed.getRawBodySha256(), bodySha);
        if (!same) {
            throw new JbkException("同一物流事实键携带了不一致的内容，已拒绝（请人工核查）");
        }
    }

    @Override
    public IMallPayApplyTx.Outcome process(Long eventId) {
        String now = DateUtils.time();
        String leaseUntil = DateUtils.plusSeconds(now, LEASE_SECONDS);
        if (eventMapper.claimEvent(eventId, now, leaseUntil) != 1) {
            return describeUnclaimable(eventId);
        }
        IMallPayApplyTx.Outcome outcome;
        try {
            outcome = applyTx.apply(eventId);
        }
        catch (RuntimeException failure) {
            outcome = classifyFailure(eventId, failure);
        }
        String done = DateUtils.time();
        int marked = switch (outcome.code()) {
            case APPLIED, ALREADY -> eventMapper.markProcessed(eventId, done);
            case RECONCILE -> eventMapper.markNeedManual(eventId,
                    StrUtil.maxLength(outcome.message(), MAX_ERROR_LEN), done);
            case RETRY -> eventMapper.markRetry(eventId,
                    StrUtil.maxLength(outcome.message(), MAX_ERROR_LEN), done);
            default -> throw new JbkException("未知物流推进结论");
        };
        if (marked != 1) {
            // 三个 mark 都带「本人仍持有」前态。影响 0 行只有一种解释：本次认领的租约
            // 已在处理期间到期并被他人接管，那边已经写下了它自己的结论。此时本次结论作废——
            // 强行覆盖会把别人刚标成「需人工」的错位事实改回「已处理、无错误」，
            // 而它随后从扫描面与人工对账面双双消失，再没有入口能发现它。
            log.warn("物流事实结论未落库（租约已被接管）eventId={} outcome={}", eventId, outcome.code());
        }
        return outcome;
    }

    /** 失败分类：确定性失败直接转人工，只有瞬时失败才排重试，上限兜底。 */
    private IMallPayApplyTx.Outcome classifyFailure(Long eventId, RuntimeException failure) {
        if (failure instanceof JbkException
                || failure instanceof DataIntegrityViolationException) {
            return IMallPayApplyTx.Outcome.reconcile("确定性失败：" + failure.getMessage());
        }
        WsMallLogisticsEvent event = eventMapper.selectById(eventId);
        int retried = ObjectUtil.isNull(event) || event.getRetryCount() == null
                ? 0 : event.getRetryCount();
        if (retried >= MAX_RETRY) {
            return IMallPayApplyTx.Outcome.reconcile("重试已达上限，转人工：" + failure.getMessage());
        }
        log.warn("物流事实推进瞬时失败，将重试 eventId={}", eventId, failure);
        return IMallPayApplyTx.Outcome.retry(failure.getMessage());
    }

    /** 认领失败：区分「已终态」与「被别人持有」，两者结论不同。 */
    private IMallPayApplyTx.Outcome describeUnclaimable(Long eventId) {
        WsMallLogisticsEvent event = eventMapper.selectById(eventId);
        if (ObjectUtil.isNull(event)) {
            return IMallPayApplyTx.Outcome.reconcile("物流事实不存在");
        }
        int status = event.getProcessingStatus() == null ? 0 : event.getProcessingStatus();
        if (status == MallEnum.LogisticsProcessing.PROCESSED.getValue()) {
            return IMallPayApplyTx.Outcome.already("物流事实已处理");
        }
        if (status == MallEnum.LogisticsProcessing.NEED_MANUAL.getValue()) {
            return IMallPayApplyTx.Outcome.reconcile(
                    StrUtil.blankToDefault(event.getLastError(), "物流事实待人工对账"));
        }
        return IMallPayApplyTx.Outcome.retry("物流事实正被其他处理者持有");
    }
}

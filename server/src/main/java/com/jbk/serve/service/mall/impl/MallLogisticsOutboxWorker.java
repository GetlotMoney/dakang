package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jbk.serve.mapper.mall.WsMallLogisticsOutboxMapper;
import com.jbk.serve.mapper.mall.WsMallShipmentMapper;
import com.jbk.serve.service.mall.ILogisticsProviderAdapter;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.data.mall.po.WsMallLogisticsOutbox;
import com.jbk.tool.data.mall.po.WsMallShipment;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 物流出站动作 Worker（E2E-09 L1）——唯一允许调用承运商适配器的地方：业务事务里外呼
 * 会拖锁，且对方超时本地回滚而运单可能已建成，重试造出第二张。「重试只产生一个运单」
 * 由适配器确定性派生 + 包裹精确前态 CAS 共同保证；认领用 CAS + 租约，崩溃后租约到期
 * 可重新领取，同 bizActionKey 恒得同一运单号。
 *
 * @author dakang
 * @since 2026-08-11
 */
@Slf4j
@Component
public class MallLogisticsOutboxWorker {

    private static final int LEASE_SECONDS = 300;
    private static final int RETRY_BACKOFF_SECONDS = 60;
    private static final int MAX_RETRY = 5;
    private static final int BATCH = 50;
    private static final int MAX_ERROR_LEN = 500;
    private static final long SYSTEM_OPERATOR = 0L;

    @Autowired
    private WsMallLogisticsOutboxMapper outboxMapper;
    @Autowired
    private WsMallShipmentMapper shipmentMapper;
    @Autowired
    private ObjectProvider<ILogisticsProviderAdapter> adapters;

    @Scheduled(fixedDelayString = "${mall.logistics-outbox.fixed-delay:30000}",
            initialDelayString = "${mall.logistics-outbox.initial-delay:45000}")
    public void scheduled() {
        try {
            runOnce();
        }
        catch (RuntimeException e) {
            // 定时任务抛出会让 fixedDelay 链条断掉，此后再也不跑——必须吞在这里并留日志
            log.error("物流出站动作扫描失败", e);
        }
    }

    /** 扫描并处理一批；返回本轮成功推进的动作数（供测试断言）。 */
    public int runOnce() {
        String now = DateUtils.time();
        List<Long> ids = outboxMapper.scanClaimableIds(now, BATCH);
        int done = 0;
        for (Long id : ids) {
            if (processOne(id)) {
                done++;
            }
        }
        return done;
    }

    /** 处理一条动作；返回是否成功完成。 */
    public boolean processOne(Long id) {
        String now = DateUtils.time();
        if (outboxMapper.claimAction(id, now, DateUtils.plusSeconds(now, LEASE_SECONDS)) != 1) {
            // 已终态或被别人持有：两种都不该在本轮重复处理
            return false;
        }
        WsMallLogisticsOutbox action = outboxMapper.selectById(id);
        if (ObjectUtil.isNull(action)) {
            return false;
        }
        try {
            if (ObjectUtil.equal(action.getActionType(),
                    MallEnum.LogisticsAction.CREATE_ORDER.getValue())) {
                createOrder(action);
            }
            else {
                // 取消运单本轮只建模不实现：承运方取消口径未定，凭空实现会造出一个
                // 平台以为取消了、承运方仍在送的包裹
                throw new JbkException("该出站动作类型本轮未实现：" + action.getActionType());
            }
            requireStillHeld(id, outboxMapper.markProcessed(id, DateUtils.time()), "已完成");
            return true;
        }
        catch (JbkException deterministic) {
            // 确定性失败（承运商未启用、包裹错位、状态不符）重试多少次都一样，直接转人工
            requireStillHeld(id, outboxMapper.markNeedManual(id,
                    StrUtil.maxLength("确定性失败：" + deterministic.getMessage(), MAX_ERROR_LEN),
                    DateUtils.time()), "确定性失败转人工");
            return false;
        }
        catch (RuntimeException transientFailure) {
            int retried = action.getRetryCount() == null ? 0 : action.getRetryCount();
            String done = DateUtils.time();
            if (retried >= MAX_RETRY) {
                requireStillHeld(id, outboxMapper.markNeedManual(id,
                        StrUtil.maxLength("重试已达上限：" + transientFailure.getMessage(),
                                MAX_ERROR_LEN), done), "重试上限转人工");
            }
            else {
                requireStillHeld(id, outboxMapper.markRetry(id,
                        DateUtils.plusSeconds(done, RETRY_BACKOFF_SECONDS),
                        StrUtil.maxLength(transientFailure.getMessage(), MAX_ERROR_LEN), done),
                        "排入重试");
            }
            log.warn("物流出站动作失败 id={} retried={}", id, retried, transientFailure);
            return false;
        }
    }

    /**
     * 结论落库的影响行数判读。
     *
     * <p>三个 mark 都带「本人仍持有」前态。影响 0 行只有一种解释：本次认领的租约在处理
     * 期间到期并被他人接管，那边已经写下了自己的结论。此时本次结论作废，只记日志——
     * 强行覆盖会把别人刚标成「需人工」的动作改回「已处理」，或对已受理的运单再次外呼。</p>
     */
    private void requireStillHeld(Long id, int affected, String what) {
        if (affected != 1) {
            log.warn("出站动作结论未落库（租约已被接管）id={} 本次结论={}", id, what);
        }
    }

    /**
     * 建单：调适配器取回执，再把运单号 CAS 写回包裹。
     *
     * <p>写回用「前态必须精确是待发运」的 CAS：重试重放时第二次影响 0 行，
     * 于是「超时重试只产生一个运单」有两道保证——适配器确定性派生同一个号，
     * 且第二次写回本来就写不进去。</p>
     */
    private void createOrder(WsMallLogisticsOutbox action) {
        WsMallShipment shipment = shipmentMapper.selectById(action.getShipmentId());
        if (ObjectUtil.isNull(shipment) || !ObjectUtil.equal(shipment.getDataStatus(), 0)) {
            throw new JbkException("出站动作对应的包裹不存在或已删除");
        }
        if (!ObjectUtil.equal(shipment.getFulfillMode(),
                MallEnum.FulfillMode.THIRD_PARTY.getValue())) {
            throw new JbkException("自营包裹不得走第三方建单");
        }
        JSONObject snap = JSONUtil.parseObj(StrUtil.blankToDefault(action.getRequestSnap(), "{}"));
        // 只按登记时冻结的快照调用，不回读当前业务态——回读会让慢重试发出去的东西
        // 与登记时说好的东西悄悄分叉
        String providerCode = snap.getStr("providerCode");
        if (!ObjectUtil.equal(providerCode, shipment.getProviderCode())) {
            throw new JbkException("动作快照的承运商与包裹不一致，拒绝下发");
        }
        ILogisticsProviderAdapter adapter = adapters.stream()
                .filter(a -> a.providerCode().equals(providerCode))
                .findFirst()
                .orElseThrow(() -> new JbkException("承运商适配器未启用：" + providerCode));

        ILogisticsProviderAdapter.CreateResult result =
                adapter.createOrder(action.getBizActionKey(), action.getRequestSnap());
        if (StrUtil.isBlank(result.waybillNo())) {
            throw new JbkException("承运方未返回运单号");
        }
        String now = DateUtils.time();
        int moved = shipmentMapper.casAccept(shipment.getId(),
                MallEnum.ShipmentStatus.PENDING.getValue(),
                MallEnum.ShipmentStatus.ACCEPTED.getValue(), shipment.getVersion(),
                result.providerOrderNo(), result.waybillNo(), SYSTEM_OPERATOR, now);
        if (moved != 1) {
            // 0 行 = 已被上一轮写过（重放）。运单号由适配器确定性派生，故这里不算失败；
            // 但必须核对已写入的号与本次回执一致，否则说明有第二张运单混进来了
            WsMallShipment fresh = shipmentMapper.selectById(shipment.getId());
            if (ObjectUtil.isNull(fresh)
                    || !ObjectUtil.equal(fresh.getWaybillNo(), result.waybillNo())) {
                throw new JbkException("包裹已写入不同的运单号，拒绝覆盖（请人工核查）");
            }
            log.info("运单已存在，按重放处理 shipmentId={} waybill={}",
                    shipment.getId(), result.waybillNo());
        }
    }
}

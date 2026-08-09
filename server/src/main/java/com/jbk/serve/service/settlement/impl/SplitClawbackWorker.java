package com.jbk.serve.service.settlement.impl;

import com.jbk.serve.mapper.settlement.WsSplitClawbackActionMapper;
import com.jbk.serve.service.settlement.ISplitClawbackTxService;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分润冲减执行兜底 Worker（D-420 R2 段2 驱动方）。
 *
 * <p>发现源=<b>动作级 outbox</b>（ws_split_clawback_action，R2-P0-2）：登记随退款
 * 成功事务单行落库，Worker 不依赖明细事实存在——明细全部丢失/被改的动作照样被
 * 发现并进入执行段对账（对不上=整动作转人工）。主驱动是退款事实管道（机构退款）
 * 与本 Worker（卡内退款及一切失败重试）。逐动作隔离：单个动作执行失败只记日志
 * 等下轮；outbox 状态机（1待处理/2已完成/3需人工）保证失败可重试、证据不合格
 * 转人工、重放零副作用。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Slf4j
@Component
public class SplitClawbackWorker {

    @Autowired
    private WsSplitClawbackActionMapper actionOutboxMapper;
    @Autowired
    private ISplitClawbackTxService clawbackTxService;

    @Scheduled(fixedDelay = 30_000)
    public void processPending() {
        runOnce(DateUtils.time());
    }

    /** 可测入口：与调度方法只差时钟来源。 */
    int runOnce(String now) {
        List<Long> actionIds = actionOutboxMapper.scanPendingActionIds();
        int processed = 0;
        for (Long actionId : actionIds) {
            try {
                clawbackTxService.processAction(actionId, now);
                processed++;
            }
            catch (RuntimeException isolated) {
                // 单动作失败不阻塞其它动作；outbox 保持待处理，下轮自然重试
                log.error("分润冲减执行失败，待下轮重试：action={} {}", actionId, isolated.getMessage());
            }
        }
        return processed;
    }
}

package com.jbk.serve.service.settlement.impl;

import com.jbk.serve.service.settlement.IReconcileService;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 日对账定时器（E2E-08 包C）：每日 01:30 对「昨日」账期跑批（Asia/Shanghai，
 * 与业务时间 varchar14 同一时区语义）；当日账期由 PC 手动触发口（包E）覆盖。
 * 失败只记日志——下一日重跑或人工触发即可，任务行/差异台账天然幂等（整批替换）。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Slf4j
@Component
public class ReconcileWorker {

    @Autowired
    private IReconcileService reconcileService;

    @Scheduled(cron = "0 30 1 * * ?")
    public void reconcileYesterday() {
        // 昨日 = 今日 varchar14 前 8 位对应日期减一天；复用 DateUtils 统一时钟，不另写时间算法
        String yesterday = DateUtils.timeTransition(
                new java.util.Date(System.currentTimeMillis() - 24L * 3600 * 1000)).substring(0, 8);
        try {
            var task = reconcileService.runFor(yesterday);
            log.info("日对账完成：bizDate={} status={} checks={} diffs={}",
                    yesterday, task.getTaskStatus(), task.getCheckTotal(), task.getDiffTotal());
        }
        catch (Exception e) {
            log.error("日对账失败（可人工触发重跑）：bizDate={} {}", yesterday, e.getMessage());
        }
    }
}

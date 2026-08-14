package com.jbk.serve.service.aftersale.refund.impl;

import com.jbk.serve.mapper.aftersale.WsRefundEventMapper;
import com.jbk.serve.service.aftersale.refund.IRefundFactService;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 退款事实重试 Worker（E2E-04 包B，与 {@code RechargePayFactRetryWorker} 同构）：
 * 补「事实已落库但没人继续处理」的洞，候选为 1待处理、到点的 4待重试、2处理中且租约过期。
 * 三条铁律：不直接改退款单（只调 {@link IRefundFactService#process}，无第二套推进逻辑）；
 * 并发裁决在 process() 内的 claim CAS，扫描只是宽松预筛；单条失败不得阻断批次。
 *
 * @author dakang
 * @since 2026-07-29
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundFactRetryWorker {

    private final WsRefundEventMapper eventMapper;
    private final IRefundFactService factService;

    /** 30s 一轮，初始延迟错开应用启动高峰；批量上限 50 由扫描 SQL 兜住。 */
    @Scheduled(fixedDelay = 30000, initialDelay = 50000)
    public void drainDueEvents() {
        runOnce(DateUtils.time());
    }

    /** 拆出可测主体：调度层只负责触发，批次语义全部在这里。 */
    void runOnce(String now) {
        List<Long> due = eventMapper.selectDueEventIds(now);
        if (due == null || due.isEmpty()) {
            return;
        }
        int ok = 0;
        int failed = 0;
        for (Long eventId : due) {
            try {
                factService.process(eventId, now);
                ok++;
            } catch (RuntimeException e) {
                // 只记日志继续下一条：process() 已保证异常时事实转待重试/待对账，抛出只会拖累同批
                failed++;
                log.error("退款事实重试失败，跳过继续：eventId={}", eventId, e);
            }
        }
        if (failed > 0 || ok > 0) {
            log.info("退款事实重试批次完成：候选={} 处理={} 失败={}", due.size(), ok, failed);
        }
    }
}

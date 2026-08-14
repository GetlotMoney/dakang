package com.jbk.serve.service.mall.impl;

import com.jbk.serve.mapper.mall.WsMallRefundFactMapper;
import com.jbk.serve.service.mall.IMallRefundFactService;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商城退款事实重试 Worker（E2E-09 S4）。
 *
 * <p>扫描面是「待处理 / 到点待重试 / 租约过期」三类。租约过期这一类是关键：处理进程崩溃时
 * 事实会永久停在「处理中」，没有它就再也没人把这笔退款推完。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MallRefundFactRetryWorker {

    private static final int BATCH = 50;

    private final WsMallRefundFactMapper factMapper;
    private final IMallRefundFactService refundFactService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void retry() {
        runOnce(DateUtils.time());
    }

    /** 可测入口：返回本轮实际推进的事实数。 */
    int runOnce(String now) {
        List<Long> ids = factMapper.scanClaimableIds(now, BATCH);
        int processed = 0;
        for (Long id : ids) {
            try {
                refundFactService.process(id);
                processed++;
            }
            catch (RuntimeException isolated) {
                // 单条失败不拖累整批：它自己的重试计数与终态由 process 内部落定
                log.error("商城退款事实推进异常 factId={}", id, isolated);
            }
        }
        return processed;
    }
}

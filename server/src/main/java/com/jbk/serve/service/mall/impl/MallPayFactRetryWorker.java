package com.jbk.serve.service.mall.impl;

import com.jbk.serve.mapper.mall.WsMallPaymentFactMapper;
import com.jbk.serve.service.mall.IMallPayFactService;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商城支付事实重放 Worker（E2E-09 S2）。
 *
 * <p>三条铁律与一期事实重试 Worker 一致：</p>
 * <ol>
 *   <li>调度方法只取一次时钟并交给可测入口 {@link #runOnce(String)}，整轮沿用同一时刻；</li>
 *   <li>扫描只是宽松预筛，并发安全靠 {@code claimFact} 的 CAS 认领；</li>
 *   <li>逐条隔离：单条毒数据不得阻断整批。</li>
 * </ol>
 *
 * <p>它存在的意义只有一个：事务A 已经收下了外部支付事实，事务B 却因为锁冲突或
 * 瞬时故障没推进完。没有这个 Worker，用户就会停在"已付款、订单还是待支付"。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MallPayFactRetryWorker {

    private static final int BATCH = 50;

    private final WsMallPaymentFactMapper factMapper;
    private final IMallPayFactService payFactService;

    @Scheduled(fixedDelay = 30_000, initialDelay = 55_000)
    public void retryPending() {
        runOnce(DateUtils.time());
    }

    /** 可测入口：与调度方法只差时钟来源。返回本轮实际处理条数。 */
    int runOnce(String now) {
        List<Long> factIds = factMapper.scanClaimableIds(now, BATCH);
        int handled = 0;
        for (Long factId : factIds) {
            try {
                payFactService.process(factId);
                handled++;
            }
            catch (RuntimeException isolated) {
                log.error("商城支付事实重放异常 factId={}", factId, isolated);
            }
        }
        return handled;
    }
}

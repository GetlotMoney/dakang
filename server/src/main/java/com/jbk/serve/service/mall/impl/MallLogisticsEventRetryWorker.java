package com.jbk.serve.service.mall.impl;

import com.jbk.serve.service.mall.IMallLogisticsFactService;
import com.jbk.tool.utils.DateUtils;
import com.jbk.serve.mapper.mall.WsMallLogisticsEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 物流事实重投 Worker（E2E-09 L1）。
 *
 * <p>回调只负责把事实落库；推进失败、进程崩溃、租约过期的事实都由本 Worker 重投。
 * 没有它，一次瞬时失败就会让一条已经收到的物流事实永远停在「处理中」——
 * 而承运方不会再推一遍。</p>
 *
 * @author dakang
 * @since 2026-08-11
 */
@Slf4j
@Component
public class MallLogisticsEventRetryWorker {

    private static final int BATCH = 50;

    @Autowired
    private WsMallLogisticsEventMapper eventMapper;
    @Autowired
    private IMallLogisticsFactService factService;

    @Scheduled(fixedDelayString = "${mall.logistics-event.fixed-delay:60000}",
            initialDelayString = "${mall.logistics-event.initial-delay:90000}")
    public void scheduled() {
        try {
            runOnce();
        }
        catch (RuntimeException e) {
            // 定时任务抛出会打断 fixedDelay 链条，此后再也不跑
            log.error("物流事实重投扫描失败", e);
        }
    }

    /** 扫一批可重投的事实；返回本轮处理条数（供测试断言）。 */
    public int runOnce() {
        List<Long> ids = eventMapper.scanClaimableIds(DateUtils.time(), BATCH);
        int done = 0;
        for (Long id : ids) {
            factService.process(id);
            done++;
        }
        return done;
    }
}

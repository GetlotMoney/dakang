package com.jbk.serve.service.aftersale.refund.impl;

import com.jbk.serve.mapper.aftersale.WsRefundEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款事实异常留痕器（E2E-04 包B）。必须独立事务（铁律④）：ingest 拒绝时抛出回滚，
 * 同事务留痕会「拒绝生效了、证据没了」。拆独立 Bean 而非同类 REQUIRES_NEW 方法：
 * Spring 事务代理对自调用不生效，那样写看着有新事务、实际仍在原事务里。
 *
 * @author dakang
 * @since 2026-07-29
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundAnomalyRecorder {

    private final WsRefundEventMapper eventMapper;

    /**
     * 记录一条可疑到达，不改动事实的处理状态。吞掉自身异常：
     * 留痕失败不该把「拒绝本次到达」变成「留痕组件炸了」，失败只记日志。
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void record(Long eventId, String reason, String now) {
        try {
            eventMapper.flagAnomaly(eventId, reason, 0L, now);
        } catch (RuntimeException e) {
            log.error("退款事实异常留痕失败：eventId={} reason={}", eventId, reason, e);
        }
    }
}

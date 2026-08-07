package com.jbk.serve.service.aftersale.refund.impl;

import com.jbk.serve.mapper.aftersale.WsRefundEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款事实异常留痕器（E2E-04 包B）。
 *
 * <h3>为什么必须是<b>独立</b>事务，而不是在 ingest 里顺手写一行</h3>
 * <p>铁律④：正向审计同事务，<b>拒绝/失败证据独立事务</b>。
 * {@code ingest} 检测到「同键不同正文」后要抛出以拒绝本次到达，
 * 而抛出会让它自己的事务整体回滚——如果留痕写在同一个事务里，
 * 那行 LAST_ERROR 会连同拒绝一起被回滚掉，结果是<b>拒绝生效了、证据没了</b>。
 * 这条在包A 已经用同样的形式踩过一次，此处不重复。</p>
 *
 * <p>拆成独立 Bean 而不是给 {@code RefundFactServiceImpl} 加一个 REQUIRES_NEW 方法：
 * Spring 的事务代理对<b>自调用</b>不生效，同类内部调自己的方法拿不到新事务，
 * 那样写出来的代码看着有 REQUIRES_NEW、实际仍在原事务里，
 * 是一种在测试里都未必暴露的静默失效。</p>
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
     * 记录一条可疑到达，不改动事实的处理状态。
     *
     * <p>本方法<b>吞掉自身异常</b>：留痕失败不该把调用方要表达的「拒绝本次到达」变成
     * 「留痕组件炸了」——那会让排查方向从安全事件跑偏到基础设施。失败只记日志。</p>
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

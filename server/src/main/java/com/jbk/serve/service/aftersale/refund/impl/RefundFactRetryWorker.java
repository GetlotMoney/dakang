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
 * 退款事实重试 Worker（E2E-04 包B），与 {@code RechargePayFactRetryWorker} 逐条同构。
 *
 * <p>补的是「事实已落库但没人继续处理」这个洞，三类候选：</p>
 * <ol>
 *   <li><b>1待处理</b>：落库后同步调用 process() 前进程中断。</li>
 *   <li><b>4待重试</b>：上一轮遇到可重试失败并已排期，到点重来。</li>
 *   <li><b>2处理中且租约过期</b>：处理者在认领之后、落终态之前崩溃。
 *       没有这一支，那条事实与它对应的退款单会永远停住 ——
 *       这也正是包A 遗留的「孤儿 PROCESSING」问题，在退款侧由租约一次性解决。</li>
 * </ol>
 *
 * <p>三条铁律，与支付侧完全一致：</p>
 * <ol>
 *   <li><b>本类不直接改退款单</b>：每条事实只调用 {@link IRefundFactService#process}，
 *       与同步路径完全同路，不存在第二套推进逻辑。</li>
 *   <li>并发安全不靠扫描判定：扫描只是宽松预筛，真正的裁决在 process() 内部的 claim CAS，
 *       抢不到的影响 0 行自然放弃。</li>
 *   <li><b>单条失败不得阻断批次</b>：一条毒事实若能卡住整个队列，其后所有正常退款都会陪葬。</li>
 * </ol>
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
                // 只记日志继续下一条：process() 内部已保证异常时事实转待重试或待对账留痕，
                // 这里再抛出去只会让同批其余事实无人处理
                failed++;
                log.error("退款事实重试失败，跳过继续：eventId={}", eventId, e);
            }
        }
        if (failed > 0 || ok > 0) {
            log.info("退款事实重试批次完成：候选={} 处理={} 失败={}", due.size(), ok, failed);
        }
    }
}

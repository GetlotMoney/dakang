package com.jbk.serve.service.mini.recharge.impl;

import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.serve.service.mini.recharge.IRechargePayFactService;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 支付事实重试 Worker，负责恢复待处理或租约过期的支付事实。
 *
 * <p>补的是「事实已落库但没人继续处理」这个洞：/pay 与 /query 同步调用 process() 后进程中断、
 * 事实进入 RETRY_WAIT（如卡冻结）、或处理者崩溃留下过期租约——这三类事实此前会永远停在原地，
 * 钱收了却无人入账。卡解冻后也靠本 Worker 自动完成入账，不需要用户再点一次支付。</p>
 *
 * <p>三条铁律：</p>
 * <ol>
 *   <li><b>本类不直接修改水卡、订单或流水</b>——每条事实只调用既有
 *       {@link IRechargePayFactService#process}，与同步路径完全同路，不存在第二套入账逻辑。</li>
 *   <li>并发安全不靠扫描判定：扫描只是宽松预筛，同步调用与多实例 Worker 抢同一条事实时，
 *       由 process() 内部的 claim CAS 决出唯一处理者，抢不到的影响 0 行自然放弃。</li>
 *   <li>单条失败不得阻断批次——一条毒事实若能卡住整个队列，其后所有正常订单都会陪葬。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RechargePayFactRetryWorker {

    private final RechargeCreditMapper creditMapper;
    private final IRechargePayFactService factService;

    /** 30s 一轮，错开应用启动高峰；批量上限 50 由扫描 SQL 兜住。 */
    @Scheduled(fixedDelay = 30000, initialDelay = 45000)
    public void drainDueEvents() {
        runOnce(DateUtils.time());
    }

    /** 拆出可测主体：调度层只负责触发，批次语义全部在这里。 */
    void runOnce(String now) {
        List<Long> due = creditMapper.selectDueEventIds(now);
        if (due == null || due.isEmpty()) {
            return;
        }
        int ok = 0;
        int failed = 0;
        for (Long eventId : due) {
            try {
                factService.process(eventId);
                ok++;
            } catch (RuntimeException e) {
                // 只记日志继续下一条：process() 内部已保证异常时事实转待对账留痕，
                // 这里再抛出去只会让同批其余事实无人处理
                failed++;
                log.error("支付事实重试失败，跳过继续：eventId={}", eventId, e);
            }
        }
        if (failed > 0 || ok > 0) {
            log.info("支付事实重试批次完成：候选={} 处理={} 失败={}", due.size(), ok, failed);
        }
    }
}

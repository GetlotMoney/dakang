package com.jbk.serve.service.delivery.impl;

import com.jbk.serve.service.delivery.IDeliveryOrderService;
import com.jbk.tool.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 自动补货固定周期触发方（B08-S1）：把「用户显式配置的固定补货周期」这条已实现的规则
 * 接上生产调度入口。
 *
 * <p><b>本类不含任何业务逻辑。</b>调度层只负责取时钟、调一次
 * {@link IDeliveryOrderService#generateAutoRefillDueOrders(String)}，
 * 创单、扣款、配送任务与流水一律复用既有事务；在 Worker 里复制哪怕一行创单或扣款代码，
 * 就等于让「定时触发」与「用户下单」各持一份资金口径，迟早分叉。</p>
 *
 * <p><b>默认关闭</b>（{@code delivery.auto-refill.enabled}，无 matchIfMissing）：这个 Worker 一旦跑起来
 * 就会按周期真实扣用户水卡余额。开发环境随手启动后台就静默扣款是不可接受的，
 * 因此必须由验收环境显式置 true 才装配，主部署不得自动开启。</p>
 *
 * <p>只处理当前到期期序：久停机后不追补历史期，理由与生成器一致——用户停机三个月回来
 * 被连扣三期钱，比漏一期严重得多。</p>
 *
 * @author dakang
 * @since 2026-08-04
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "delivery.auto-refill.enabled", havingValue = "true")
public class DeliveryAutoRefillWorker {

    @Autowired
    private IDeliveryOrderService deliveryOrderService;

    /**
     * 固定周期扫描。间隔与首次延迟均可配，缺省 5 分钟一轮、启动后 60 秒开跑
     * （避开启动期的库连接与缓存预热）。
     */
    @Scheduled(
            fixedDelayString = "${delivery.auto-refill.fixed-delay:300000}",
            initialDelayString = "${delivery.auto-refill.initial-delay:60000}")
    public void scanDueRules() {
        // 时钟只取一次并整轮沿用：同一轮里反复取 now 会让期序判定在跨秒/跨天边界上左右横跳，
        // 出现「同一轮扫描里前一条算第 3 期、后一条算第 4 期」。
        runOnce(DateUtils.time());
    }

    /**
     * 可测入口：与 {@link #scanDueRules()} 的差别只有时钟来源。
     *
     * <p>整轮兜异常——扫描失败必须留日志并等下一轮，绝不能让一次异常把
     * {@code @Scheduled} 的 fixedDelay 链条打断（Spring 的定时任务抛出后本任务不再续跑）。
     * 逐条规则的隔离在 {@code generateAutoRefillDueOrders} 内部完成，这里兜的是整轮性失败
     * （如库不可用）。</p>
     *
     * @return 本轮真实生成的订单数；本轮整体失败返回 0
     */
    int runOnce(String now) {
        try {
            int generated = deliveryOrderService.generateAutoRefillDueOrders(now);
            if (generated > 0) {
                log.info("自动补货本轮生成 {} 单 now={}", generated, now);
            }
            return generated;
        }
        catch (RuntimeException e) {
            log.error("自动补货扫描整轮失败，等待下一轮 now={}", now, e);
            return 0;
        }
    }
}

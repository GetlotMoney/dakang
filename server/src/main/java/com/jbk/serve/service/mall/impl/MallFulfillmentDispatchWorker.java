package com.jbk.serve.service.mall.impl;

import com.jbk.serve.mapper.mall.WsMallFulfillmentMapper;
import com.jbk.serve.service.mall.IMallFulfillmentService;
import com.jbk.tool.consts.mall.MallEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商城履约任务生成 Worker（E2E-09 S3）。
 *
 * <p><b>为什么不挂在支付事务B 里。</b>那一段是资金链（订单 CAS + 库存实销），把履约建表
 * 塞进去会让两条链共享失败面：履约表一个约束冲突就能把已经收到的钱回滚掉。这里改为
 * 支付完成后由本 Worker 补齐——{@code ensureTask} 幂等，扫多少次都只会有一个任务。</p>
 *
 * <p>扫描面是「订单状态精确为 2 且尚无任务」，天然收敛：任务一旦生成就不再被捞。</p>
 *
 * @author dakang
 * @since 2026-08-09
 */
@Slf4j
@Component
public class MallFulfillmentDispatchWorker {

    private static final int BATCH = 50;

    @Autowired
    private WsMallFulfillmentMapper fulfillMapper;
    @Autowired
    private IMallFulfillmentService fulfillmentService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 80_000)
    public void dispatch() {
        runOnce();
    }

    /** 可测入口：返回本轮实际生成的任务数。 */
    int runOnce() {
        List<String> orderNos = fulfillMapper.scanPaidOrdersWithoutTask(
                MallEnum.OrderStatus.PAID.getValue(), BATCH);
        int created = 0;
        for (String orderNo : orderNos) {
            try {
                fulfillmentService.ensureTask(orderNo);
                created++;
            }
            catch (RuntimeException isolated) {
                // 单条失败不拖累整批：订单可能刚被推进或被删，下一轮扫描面自然不再包含它
                log.error("商城履约任务生成异常 orderNo={}", orderNo, isolated);
            }
        }
        return created;
    }
}

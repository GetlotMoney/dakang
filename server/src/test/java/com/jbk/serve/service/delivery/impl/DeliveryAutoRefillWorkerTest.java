package com.jbk.serve.service.delivery.impl;

import com.jbk.serve.service.delivery.IDeliveryOrderService;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B08-S1 调度层单测：只钉「装配门控」与「调度层不含业务逻辑」两件事。
 *
 * <p>资金、并发与幂等性质一律不在这里断言——那些必须走真库真事务，见
 * {@link DeliveryAutoRefillDbTest}。本类刻意不碰数据库：开关误开的后果是**真实扣用户余额**，
 * 这道门必须有一条不依赖 Docker 的用例守着，任何环境跑 mvn test 都能挡住。</p>
 */
class DeliveryAutoRefillWorkerTest {

    @Configuration
    static class StubCtx {
        @Bean
        IDeliveryOrderService deliveryOrderService() {
            return Mockito.mock(IDeliveryOrderService.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(StubCtx.class, DeliveryAutoRefillWorker.class);

    // 1. 开关缺省：Worker 不装配（缺省不写 = 关闭，绝不能靠 matchIfMissing 兜成开启）
    @Test
    void workerIsNotAssembledWhenPropertyMissing() {
        runner.run(context -> assertEquals(0,
                context.getBeanNamesForType(DeliveryAutoRefillWorker.class).length,
                "开关缺省时 Worker 必须不装配——它一跑就真扣用户余额"));
    }

    // 1b. 显式 false 同样不装配
    @Test
    void workerIsNotAssembledWhenPropertyFalse() {
        runner.withPropertyValues("delivery.auto-refill.enabled=false")
                .run(context -> assertEquals(0,
                        context.getBeanNamesForType(DeliveryAutoRefillWorker.class).length));
    }

    // 2. 显式 true 才装配
    @Test
    void workerIsAssembledOnlyWhenExplicitlyEnabled() {
        runner.withPropertyValues("delivery.auto-refill.enabled=true")
                .run(context -> assertEquals(1,
                        context.getBeanNamesForType(DeliveryAutoRefillWorker.class).length));
    }

    // 2b. 开启后 runOnce 只把时钟转交既有生成服务，自己不做任何业务动作
    @Test
    void runOnceDelegatesToExistingGeneratorOnly() {
        IDeliveryOrderService service = Mockito.mock(IDeliveryOrderService.class);
        DeliveryAutoRefillWorker worker = new DeliveryAutoRefillWorker();
        ReflectionTestUtils.setField(worker, "deliveryOrderService", service);
        when(service.generateAutoRefillDueOrders("20260804090000")).thenReturn(2);

        assertEquals(2, worker.runOnce("20260804090000"));

        verify(service).generateAutoRefillDueOrders("20260804090000");
        // 调度层禁止复制创单/扣款/任务/流水逻辑：除了那一个方法，服务上不许再被调到别的
        Mockito.verifyNoMoreInteractions(service);
    }

    // 整轮异常必须被吞并等下一轮：@Scheduled 的任务一旦抛出，fixedDelay 链就断了，
    // 症状是「自动补货某天起再也没跑过」，且没有任何人会注意到。
    @Test
    void runOnceSwallowsRoundFailureSoTheScheduleKeepsTicking() {
        IDeliveryOrderService service = Mockito.mock(IDeliveryOrderService.class);
        DeliveryAutoRefillWorker worker = new DeliveryAutoRefillWorker();
        ReflectionTestUtils.setField(worker, "deliveryOrderService", service);
        when(service.generateAutoRefillDueOrders(anyString()))
                .thenThrow(new JbkException("数据库不可用"));

        assertEquals(0, worker.runOnce("20260804090000"), "整轮失败返回 0，不向调度器抛出");
    }

    // 12（调度侧）：非法业务时间不允许进入生成服务由服务自身拒绝，Worker 不得自行"修正"时间
    @Test
    void runOncePassesClockThroughVerbatim() {
        IDeliveryOrderService service = Mockito.mock(IDeliveryOrderService.class);
        DeliveryAutoRefillWorker worker = new DeliveryAutoRefillWorker();
        ReflectionTestUtils.setField(worker, "deliveryOrderService", service);
        when(service.generateAutoRefillDueOrders("bad-time"))
                .thenThrow(new JbkException("当前时间格式非法"));

        assertEquals(0, worker.runOnce("bad-time"));
        // 原样透传：Worker 不许兜底改写时钟，否则非法时间会被悄悄"修好"后真的扣款
        verify(service).generateAutoRefillDueOrders("bad-time");
        verify(service, never()).generateAutoRefillDueOrders("20260804090000");
    }
}

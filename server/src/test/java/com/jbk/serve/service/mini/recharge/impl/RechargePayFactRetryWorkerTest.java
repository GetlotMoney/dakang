package com.jbk.serve.service.mini.recharge.impl;

import com.jbk.serve.mapper.trade.RechargeCreditMapper;
import com.jbk.serve.service.mini.recharge.IRechargePayFactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付事实重试 Worker 回归测试。
 *
 * <p>Worker 的价值判定只有三条：每条候选都调 process、单条失败不阻断批次、
 * 除 process 外不碰任何资金对象。真正的并发唯一性由 claim CAS 的真库测试覆盖，不在这里重复。</p>
 */
class RechargePayFactRetryWorkerTest {

    private RechargeCreditMapper creditMapper;
    private IRechargePayFactService factService;
    private RechargePayFactRetryWorker worker;

    @BeforeEach
    void setup() {
        creditMapper = Mockito.mock(RechargeCreditMapper.class);
        factService = Mockito.mock(IRechargePayFactService.class);
        worker = new RechargePayFactRetryWorker(creditMapper, factService);
        when(factService.process(anyLong()))
                .thenReturn(new IRechargePayFactService.Outcome("CREDITED", "入账完成"));
    }

    // 每条候选都必须被处理，且按扫描顺序（毒事实定位靠日志顺序）
    @Test
    void processesEveryDueEventInOrder() {
        when(creditMapper.selectDueEventIds(anyString())).thenReturn(List.of(11L, 12L, 13L));
        worker.runOnce("20260722120000");
        InOrder order = inOrder(factService);
        order.verify(factService).process(11L);
        order.verify(factService).process(12L);
        order.verify(factService).process(13L);
    }

    /** 中间一条抛异常其余必须继续：否则一条毒事实卡死整个重试队列。 */
    @Test
    void poisonEventDoesNotBlockTheBatch() {
        when(creditMapper.selectDueEventIds(anyString())).thenReturn(List.of(21L, 22L, 23L));
        doThrow(new IllegalStateException("毒事实")).when(factService).process(22L);
        worker.runOnce("20260722120000");
        verify(factService).process(21L);
        verify(factService).process(23L);
    }

    // 空批次零动作；Worker 自身绝不触碰资金对象（只允许扫描一次 + 逐条委托）
    @Test
    void emptyBatchTouchesNothing() {
        when(creditMapper.selectDueEventIds(anyString())).thenReturn(List.of());
        worker.runOnce("20260722120000");
        verify(factService, never()).process(anyLong());
        verify(creditMapper).selectDueEventIds(anyString());
        Mockito.verifyNoMoreInteractions(creditMapper);
    }
}

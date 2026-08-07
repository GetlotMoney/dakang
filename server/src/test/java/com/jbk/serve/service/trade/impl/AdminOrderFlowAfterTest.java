package com.jbk.serve.service.trade.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.device.IWsCommandService;
import com.jbk.serve.service.mini.recharge.RechargeDetailVerifier;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.trade.vo.AdminOrderItemVo;
import com.jbk.tool.data.trade.vo.AdminOrderTraceVo;
import com.jbk.tool.data.trade.vo.FlowTraceVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * P1-B「订单终值」证据口径回归：
 * 「本单结算后余额/水量」只能来自该订单流水写入时冻结的 AMOUNT_AFTER/ML_AFTER 快照；
 * 「当前水卡余额/水量」是 ws_card 当前只读值。订单 A 完成后又发生充值/取水时，
 * 回看 A 的追溯必须两者并存且不混用——AFTER 保持 A 的历史值，卡面值显示最新值。
 */
class AdminOrderFlowAfterTest {

    private static final Long ORDER_ID = 61L;
    /** 订单 A 终笔流水（补偿）后的卡面快照：这是「本单结算后」的唯一合法证据源。 */
    private static final long A_SETTLED_ML_AFTER = 20_020L;
    private static final long A_SETTLED_AMOUNT_AFTER = 5_000L;
    /** 订单 A 之后又充值 500L：ws_card 当前值已经漂移，绝不能被标成 A 的终值。 */
    private static final long CARD_CURRENT_ML = 520_020L;
    private static final long CARD_CURRENT_FEN = 5_000L;

    private WsOrderMapper orderMapper;
    private WsWalletFlowMapper walletFlowMapper;
    private AdminOrderServiceImpl service;

    static {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WsWalletFlow.class);
    }

    @BeforeEach
    void setup() {
        orderMapper = Mockito.mock(WsOrderMapper.class);
        walletFlowMapper = Mockito.mock(WsWalletFlowMapper.class);
        service = new AdminOrderServiceImpl(orderMapper, walletFlowMapper,
                Mockito.mock(IWsCommandService.class),
                Mockito.mock(RechargeIdentityMapper.class),
                Mockito.mock(RechargeDetailVerifier.class),
                Mockito.mock(com.jbk.serve.service.delivery.IAdminDeliveryService.class));
    }

    private AdminOrderItemVo waterOrderA() {
        AdminOrderItemVo order = new AdminOrderItemVo();
        order.setId(ORDER_ID);
        order.setOrderNo("WOFLOWAFTER0001");
        order.setOrderType(1);
        order.setOrderStatus(4);
        order.setUserId(9L);
        order.setCardId(100L);
        order.setPlanMl(10_000L);
        order.setActualMl(4_980L);
        order.setOrderAmount(0L);
        order.setPayWay(3);
        // 追溯时点的 ws_card 当前值：A 完成后又充值过，已不等于 A 的结算后快照
        order.setCardBalanceFen(CARD_CURRENT_FEN);
        order.setCardBalanceMl(CARD_CURRENT_ML);
        return order;
    }

    private WsWalletFlow flow(long id, int type, long mlChange, long amountAfter, long mlAfter, String time) {
        WsWalletFlow f = new WsWalletFlow()
                .setCardId(100L)
                .setUserId(9L)
                .setFlowType(type)
                .setAmountChange(0L)
                .setMlChange(mlChange)
                .setAmountAfter(amountAfter)
                .setMlAfter(mlAfter)
                .setOrderId(ORDER_ID);
        f.setCreateTime(time);
        org.springframework.test.util.ReflectionTestUtils.setField(f, "id", id);
        return f;
    }

    @Test
    void flowsCarryFrozenAfterSnapshotsWhileOrderCarriesCurrentCardValue() {
        when(orderMapper.selectAdminOrderById(eq(ORDER_ID), anyInt(), anyInt())).thenReturn(waterOrderA());
        // A 的账本：预扣 10000（AFTER=15020）→ 结算补偿 +5020（AFTER=20020，即 A 的「结算后」）
        when(walletFlowMapper.selectList(any())).thenReturn(List.of(
                flow(501L, 2, -10_000L, A_SETTLED_AMOUNT_AFTER, 15_020L, "20260723100000"),
                flow(502L, 4, 5_020L, A_SETTLED_AMOUNT_AFTER, A_SETTLED_ML_AFTER, "20260723100100")));

        AdminOrderTraceVo trace = service.getOrderTrace(ORDER_ID);

        assertEquals(2, trace.getFlows().size());
        FlowTraceVo last = trace.getFlows().get(1);
        // 「本单结算后」＝最后一条有效流水的 AFTER：即便卡面值之后被充值改写，这里必须保持 A 的历史快照
        assertEquals(A_SETTLED_ML_AFTER, last.getMlAfter());
        assertEquals(A_SETTLED_AMOUNT_AFTER, last.getAmountAfter());
        // 「当前水卡余额/水量」＝ws_card 当前值：两者并存、语义分离
        assertEquals(CARD_CURRENT_ML, trace.getOrder().getCardBalanceMl());
        assertEquals(CARD_CURRENT_FEN, trace.getOrder().getCardBalanceFen());
        // 证伪锚：若实现把 ws_card 当前值回填进流水 AFTER（或反向），本断言立刻变红
        assertNotEquals(trace.getOrder().getCardBalanceMl(), last.getMlAfter());
        // 首笔流水的 AFTER 同样保持写入时快照
        assertEquals(15_020L, trace.getFlows().get(0).getMlAfter());
    }

    /** 历史/异常流水缺 AFTER 时保持 null 下发——前端据此隐藏「本单结算后」，禁止伪造 0。 */
    @Test
    void missingAfterSnapshotStaysNull() {
        when(orderMapper.selectAdminOrderById(eq(ORDER_ID), anyInt(), anyInt())).thenReturn(waterOrderA());
        WsWalletFlow legacy = flow(503L, 2, -10_000L, 0L, 0L, "20260723100000")
                .setAmountAfter(null)
                .setMlAfter(null);
        when(walletFlowMapper.selectList(any())).thenReturn(List.of(legacy));

        AdminOrderTraceVo trace = service.getOrderTrace(ORDER_ID);

        assertNull(trace.getFlows().get(0).getAmountAfter());
        assertNull(trace.getFlows().get(0).getMlAfter());
    }
}

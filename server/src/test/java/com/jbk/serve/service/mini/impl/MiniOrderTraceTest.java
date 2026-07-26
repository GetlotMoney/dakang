package com.jbk.serve.service.mini.impl;

import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.trade.bo.MiniOrderDetailBo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.vo.OrderDetailVo;
import com.jbk.tool.data.trade.vo.OrderTraceNodeVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 订单轨迹按 ORDER_TYPE 分支（链路一 S0-B）：非取水单绝不套用取水文案；
 * 支付节点用显式状态集合判定，不再用 {@code status >= PAID} 数值序比较。
 */
class MiniOrderTraceTest {

    private WsOrderMapper wsOrderMapper;
    private WsWalletFlowMapper walletFlowMapper;
    private RechargeIdentityMapper rechargeIdentityMapper;
    private MiniOrderServiceImpl service;

    private static final Long USER_ID = 100L;

    @BeforeEach
    void setup() {
        wsOrderMapper = Mockito.mock(WsOrderMapper.class);
        walletFlowMapper = Mockito.mock(WsWalletFlowMapper.class);
        rechargeIdentityMapper = Mockito.mock(RechargeIdentityMapper.class);
        service = new MiniOrderServiceImpl();
        ReflectionTestUtils.setField(service, "wsOrderMapper", wsOrderMapper);
        ReflectionTestUtils.setField(service, "walletFlowMapper", walletFlowMapper);
        ReflectionTestUtils.setField(service, "rechargeIdentityMapper", rechargeIdentityMapper);
        // E2E-03 包B：详情为配送单补挂任务号/申诉ID；本测试聚焦轨迹语义，两 mapper 置空返回即可
        ReflectionTestUtils.setField(service, "deliveryTaskMapper",
                Mockito.mock(com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper.class));
        ReflectionTestUtils.setField(service, "deliveryAppealMapper",
                Mockito.mock(com.jbk.serve.mapper.delivery.WsDeliveryAppealMapper.class));
        when(walletFlowMapper.selectCount(any())).thenReturn(0L);
    }

    /** stationId/deviceId 置空以跳过站点/设备装配，聚焦轨迹语义。 */
    private WsOrder order(int orderType, int status) {
        WsOrder po = new WsOrder()
                .setId(1L)
                .setOrderNo("WO20260721TEST")
                .setUserId(USER_ID)
                .setOrderType(orderType)
                .setOrderStatus(status)
                .setPayWay(TradeEnum.PayWay.CARD_BALANCE.getValue());
        // BaseEntity 的时间字段非链式（@Data），单独设置
        po.setCreateTime("20260721000000");
        po.setUpdateTime("20260721000100");
        return po;
    }

    private OrderDetailVo detailOf(WsOrder po) {
        when(wsOrderMapper.selectById(1L)).thenReturn(po);
        MiniOrderDetailBo bo = new MiniOrderDetailBo();
        bo.setOrderId(1L);
        return service.getMyOrderDetail(bo, USER_ID);
    }

    private List<String> texts(List<OrderTraceNodeVo> trace) {
        return trace.stream()
                .map(n -> n.getLabel() + "|" + n.getDetail())
                .collect(Collectors.toList());
    }

    // S0-B 核心：充值单（ORDER_TYPE=2）绝不返回取水轨迹文案
    @Test
    void rechargeOrderHasNoWaterTrace() {
        WsOrder order = order(TradeEnum.OrderType.CARD.getValue(), TradeEnum.OrderStatus.PAID.getValue());
        WsPayment payment = new WsPayment().setId(2L).setOrderId(1L).setOrderNo(order.getOrderNo())
                .setPayStatus(2).setPaySuccessTime("20260721000030");
        payment.setDataStatus(0);
        when(rechargeIdentityMapper.selectPaymentsByOrderIdIncludingDeleted(1L)).thenReturn(List.of(payment));
        @SuppressWarnings("unchecked")
        List<OrderTraceNodeVo> trace = ReflectionTestUtils.invokeMethod(service, "buildTrace", order);

        String joined = String.join(";", texts(trace));
        assertTrue(joined.contains("支付成功"), "充值单应返回类型感知的支付/权益轨迹");
        assertFalse(joined.contains("扫码取水"), "充值单不得出现『扫码取水』文案");
        assertFalse(joined.contains("取水完成"), "充值单不得出现取水完成文案");
    }

    // 配送单（ORDER_TYPE=3）同样不得复用取水语义
    @Test
    void deliveryOrderHasNoWaterTrace() {
        OrderDetailVo vo = detailOf(order(TradeEnum.OrderType.DELIVERY.getValue(), TradeEnum.OrderStatus.FINISHED.getValue()));
        assertTrue(vo.getTrace().isEmpty(), "配送单不得返回取水轨迹");
    }

    // 取水单保持既有语义
    @Test
    void waterOrderKeepsTrace() {
        OrderDetailVo vo = detailOf(order(TradeEnum.OrderType.WATER.getValue(), TradeEnum.OrderStatus.FINISHED.getValue()));
        String joined = String.join(";", texts(vo.getTrace()));
        assertTrue(joined.contains("扫码取水下单"), "取水单应保留下单节点");
        assertTrue(joined.contains("扣款成功"), "已完成的取水单应含支付节点");
        assertTrue(joined.contains("取水完成"), "已完成的取水单应含完成节点");
    }

    // 数值序比较遗留缺陷：已取消(5) 可能从未支付，不得再自动出现“扣款成功”
    @Test
    void cancelledWaterOrderHasNoPaidNode() {
        OrderDetailVo vo = detailOf(order(TradeEnum.OrderType.WATER.getValue(), TradeEnum.OrderStatus.CANCELLED.getValue()));
        String joined = String.join(";", texts(vo.getTrace()));
        assertFalse(joined.contains("扣款成功"), "已取消订单不得凭数值序推出支付节点");
        assertTrue(joined.contains("已取消"), "已取消订单应含取消节点");
    }

    // 已退款(7) 曾经支付过 → 支付节点仍应保留
    @Test
    void refundedWaterOrderKeepsPaidNode() {
        OrderDetailVo vo = detailOf(order(TradeEnum.OrderType.WATER.getValue(), TradeEnum.OrderStatus.REFUNDED.getValue()));
        assertTrue(String.join(";", texts(vo.getTrace())).contains("扣款成功"), "已退款订单曾支付，应含支付节点");
    }

    // commandNo/commandStatus 当前恒为 null（未接 ws_command），契约上必须显式为空
    @Test
    void commandFieldsAreExplicitlyNull() {
        OrderDetailVo vo = detailOf(order(TradeEnum.OrderType.WATER.getValue(), TradeEnum.OrderStatus.PAID.getValue()));
        assertNull(vo.getCommandNo());
        assertNull(vo.getCommandStatus());
        assertEquals(0, vo.getFlowCount());
    }
}

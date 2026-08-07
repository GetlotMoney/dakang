package com.jbk.serve.service.mini.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.serve.service.mini.recharge.RechargeCredit;
import com.jbk.serve.service.mini.recharge.RechargeDetailVerifier;
import com.jbk.serve.service.mini.recharge.RechargeRefundEvidenceVerifier;
import com.jbk.serve.service.mini.card.WaterCardScope;
import com.jbk.serve.service.mini.recharge.RechargeSnapshot;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.product.po.WsPackage;
import com.jbk.tool.data.trade.bo.MiniOrderDetailBo;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsPaymentEvent;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.data.trade.vo.MiniRechargeDetailVo;
import com.jbk.tool.data.trade.vo.OrderDetailVo;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2 充值订单详情的购卡/已有卡分流契约。
 *
 * <p>首次购卡在订单完成前没有水卡是合法事实；完成后则必须通过
 * {@code ws_card.ISSUE_ORDER_ID = ws_order.ID} 证明返回的是本订单发行的卡。
 * 已有卡充值始终保留目标卡必填约束。</p>
 */
class MiniRechargeOrderDetailTest {

    private static final long USER_ID = 9L;
    private static final long ORDER_ID = 701L;
    private static final long PAYMENT_ID = 702L;
    private static final long CARD_ID = 703L;
    private static final String ORDER_NO = "RCDETAIL000000000000000000000001";
    private static final String CARD_NO = "VCDETAIL00000001";
    private static final String UUID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String CREATE_TIME = "20260722100000";
    private static final String PAY_SUCCESS_TIME = "20260722100500";
    private static final String FINISH_TIME = "20260722100600";
    private static final String SCOPE = "{\"scopeType\":\"specified\",\"stationIds\":[1]}";

    private WsOrderMapper orderMapper;
    private WsWalletFlowMapper walletFlowMapper;
    private RechargeIdentityMapper identityMapper;
    private RechargeRefundEvidenceVerifier refundEvidenceVerifier;
    private MiniOrderServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WsWalletFlow.class);
    }

    @BeforeEach
    void setup() {
        orderMapper = Mockito.mock(WsOrderMapper.class);
        walletFlowMapper = Mockito.mock(WsWalletFlowMapper.class);
        identityMapper = Mockito.mock(RechargeIdentityMapper.class);
        refundEvidenceVerifier = Mockito.mock(RechargeRefundEvidenceVerifier.class);
        service = new MiniOrderServiceImpl();
        ReflectionTestUtils.setField(service, "wsOrderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "walletFlowMapper", walletFlowMapper);
        ReflectionTestUtils.setField(service, "rechargeIdentityMapper", identityMapper);
        ReflectionTestUtils.setField(service, "rechargeDetailVerifier",
                new RechargeDetailVerifier(identityMapper, refundEvidenceVerifier));
        when(walletFlowMapper.selectCount(any())).thenReturn(0L);
        when(identityMapper.selectEventsByOrderNoIncludingDeleted(ORDER_NO)).thenReturn(List.of());
        when(identityMapper.selectFlowsByOrderIdIncludingDeleted(ORDER_ID)).thenReturn(List.of());
    }

    @Test
    void pendingPurchaseWithoutCardReturnsStructuredDetail() {
        WsOrder order = purchaseOrder(TradeEnum.OrderStatus.UNPAID.getValue(), null);
        stubOrderAndPayment(order, pendingPayment(order));

        MiniRechargeDetailVo recharge = detail().getOrder().getRecharge();

        assertEquals(RechargeSnapshot.PURCHASE_MODE_FIRST_CARD, recharge.getPurchaseMode());
        assertNull(recharge.getCardId());
        assertNull(recharge.getCardNo());
        assertTrue(recharge.getSnapshotValid());
        verify(identityMapper, never()).selectCardsByIdIncludingDeleted(any());
    }

    @Test
    void completedPurchaseReturnsIssuedCardIdentity() {
        WsOrder order = purchaseOrder(TradeEnum.OrderStatus.FINISHED.getValue(), CARD_ID);
        WsPayment payment = successfulPayment(order);
        WsPaymentEvent event = processedSuccessEvent(order, payment);
        WsWalletFlow flow = completedFlow(order);
        WsCard card = issuedCard(ORDER_ID);
        stubCompleted(order, payment, event, flow, card);

        MiniRechargeDetailVo recharge = detail().getOrder().getRecharge();

        assertEquals(RechargeSnapshot.PURCHASE_MODE_FIRST_CARD, recharge.getPurchaseMode());
        assertEquals(CARD_ID, recharge.getCardId());
        assertEquals(CARD_NO, recharge.getCardNo());
        assertEquals(1, recharge.getCardType());
        assertEquals(ORDER_ID, recharge.getIssueOrderId());
        assertEquals("限定范围：水站 1 个", recharge.getScopeDescription());
        assertEquals(flow.getAmountAfter(), recharge.getCardBalanceFen());
        assertEquals(flow.getMlAfter(), recharge.getCardBalanceMl());
    }

    @Test
    void refundedPurchaseKeepsOriginalCreditEvidenceAndReturnsCurrentCardState() {
        WsOrder order = purchaseOrder(TradeEnum.OrderStatus.REFUNDED.getValue(), CARD_ID);
        order.setFinishTime(FINISH_TIME);
        WsPayment payment = successfulPayment(order);
        WsPaymentEvent event = processedSuccessEvent(order, payment);
        WsWalletFlow credit = completedFlow(order);
        WsWalletFlow refund = completedFlow(order)
                .setId(706L)
                .setFlowType(TradeEnum.FlowType.REFUND.getValue())
                .setAmountChange(0L)
                .setMlChange(-500000L)
                .setAmountAfter(0L)
                .setMlAfter(0L)
                .setBizIdempotencyKey("AFTERSALE:AS-DETAIL");
        WsCard refundedCard = issuedCard(ORDER_ID).setBalanceMl(0L).setCardStatus(4);
        stubOrderAndPayment(order, payment);
        when(walletFlowMapper.selectCount(any())).thenReturn(2L);
        when(identityMapper.selectEventsByOrderNoIncludingDeleted(ORDER_NO)).thenReturn(List.of(event));
        when(identityMapper.selectFlowsByOrderIdIncludingDeleted(ORDER_ID)).thenReturn(List.of(credit, refund));
        when(identityMapper.selectCardsByIdIncludingDeleted(CARD_ID)).thenReturn(List.of(refundedCard));

        MiniRechargeDetailVo recharge = detail().getOrder().getRecharge();

        assertEquals(credit.getMlChange(), recharge.getFlowMlChange(), "详情只能把原充值流水当入账证据");
        assertEquals(0L, recharge.getCardBalanceMl(), "当前卡水量应反映退款后的终值");
        verify(refundEvidenceVerifier).requireIfRefunded(order);
    }

    @Test
    void completedPurchaseWithoutCardIsRejected() {
        WsOrder order = purchaseOrder(TradeEnum.OrderStatus.FINISHED.getValue(), null);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);

        JbkException error = assertThrows(JbkException.class, this::detail);

        assertTrue(error.getMessage().contains("缺少发行卡关联"));
    }

    @Test
    void completedPurchaseWithMismatchedIssueAnchorIsRejected() {
        WsOrder order = purchaseOrder(TradeEnum.OrderStatus.FINISHED.getValue(), CARD_ID);
        WsPayment payment = successfulPayment(order);
        stubCompleted(order, payment, processedSuccessEvent(order, payment),
                completedFlow(order), issuedCard(9999L));

        JbkException error = assertThrows(JbkException.class, this::detail);

        assertTrue(error.getMessage().contains("发行锚点与订单不一致"));
    }

    @Test
    void completedPurchaseWithWrongCardTypeIsRejected() {
        WsOrder order = purchaseOrder(TradeEnum.OrderStatus.FINISHED.getValue(), CARD_ID);
        WsPayment payment = successfulPayment(order);
        WsCard card = issuedCard(ORDER_ID).setCardType(2);
        stubCompleted(order, payment, processedSuccessEvent(order, payment), completedFlow(order), card);

        JbkException error = assertThrows(JbkException.class, this::detail);

        assertTrue(error.getMessage().contains("卡类型"));
    }

    @Test
    void completedPurchaseWithWrongCardScopeIsRejected() {
        WsOrder order = purchaseOrder(TradeEnum.OrderStatus.FINISHED.getValue(), CARD_ID);
        WsPayment payment = successfulPayment(order);
        WsCard card = issuedCard(ORDER_ID)
                .setScopeJson("{\"scopeType\":\"specified\",\"stationIds\":[2]}");
        stubCompleted(order, payment, processedSuccessEvent(order, payment), completedFlow(order), card);

        JbkException error = assertThrows(JbkException.class, this::detail);

        assertTrue(error.getMessage().contains("范围"));
    }

    @Test
    void completedPurchaseRequiresBothAfterValuesToStartFromZero() {
        WsOrder order = purchaseOrder(TradeEnum.OrderStatus.FINISHED.getValue(), CARD_ID);
        WsPayment payment = successfulPayment(order);
        WsWalletFlow flow = completedFlow(order).setAmountAfter(1L);
        stubCompleted(order, payment, processedSuccessEvent(order, payment), flow, issuedCard(ORDER_ID));

        JbkException error = assertThrows(JbkException.class, this::detail);

        assertTrue(error.getMessage().contains("AFTER"));
    }

    @Test
    void existingCardRechargeWithoutCardIsStillRejected() {
        WsOrder order = existingCardRechargeOrder(null);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);

        JbkException error = assertThrows(JbkException.class, this::detail);

        assertTrue(error.getMessage().contains("已有卡充值订单缺少目标卡"));
    }

    @Test
    void unfinishedPurchaseWithPrematureCardIsRejected() {
        WsOrder order = purchaseOrder(TradeEnum.OrderStatus.UNPAID.getValue(), CARD_ID);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);

        JbkException error = assertThrows(JbkException.class, this::detail);

        assertTrue(error.getMessage().contains("不应提前关联水卡"));
    }

    private OrderDetailVo detail() {
        MiniOrderDetailBo bo = new MiniOrderDetailBo();
        bo.setOrderId(ORDER_ID);
        return service.getMyOrderDetail(bo, USER_ID);
    }

    private void stubOrderAndPayment(WsOrder order, WsPayment payment) {
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(identityMapper.selectPaymentsByOrderIdIncludingDeleted(ORDER_ID)).thenReturn(List.of(payment));
    }

    private void stubCompleted(WsOrder order, WsPayment payment, WsPaymentEvent event,
                               WsWalletFlow flow, WsCard card) {
        stubOrderAndPayment(order, payment);
        when(walletFlowMapper.selectCount(any())).thenReturn(1L);
        when(identityMapper.selectEventsByOrderNoIncludingDeleted(ORDER_NO)).thenReturn(List.of(event));
        when(identityMapper.selectFlowsByOrderIdIncludingDeleted(ORDER_ID)).thenReturn(List.of(flow));
        when(identityMapper.selectCardsByIdIncludingDeleted(CARD_ID)).thenReturn(List.of(card));
    }

    private WsOrder purchaseOrder(int status, Long cardId) {
        WsOrder order = baseOrder(status, cardId);
        order.setPackageSnap(com.jbk.serve.service.mini.recharge.LegacySnapshots.forgeExpireDays(
                RechargeSnapshot.buildForPurchase(
                        UUID, rechargePackage(), WaterCardScope.normalize(SCOPE, "套餐"), CREATE_TIME),
                365));
        return order;
    }

    private WsOrder existingCardRechargeOrder(Long cardId) {
        WsCard target = new WsCard().setId(CARD_ID).setUserId(USER_ID)
                .setCardStatus(1).setExpireTime("20270722100000");
        WsOrder order = baseOrder(TradeEnum.OrderStatus.UNPAID.getValue(), cardId);
        WaterCardScope scope = WaterCardScope.normalize(SCOPE, "目标卡");
        order.setPackageSnap(com.jbk.serve.service.mini.recharge.LegacySnapshots.forgeExpireDays(
                RechargeSnapshot.build(
                        UUID, rechargePackage(), target, scope, scope, CREATE_TIME),
                365));
        return order;
    }

    private WsOrder baseOrder(int status, Long cardId) {
        WsOrder order = new WsOrder()
                .setId(ORDER_ID)
                .setOrderNo(ORDER_NO)
                .setOrderType(TradeEnum.OrderType.CARD.getValue())
                .setUserId(USER_ID)
                .setCardId(cardId)
                .setPackageId(3L)
                .setOrderAmount(10000L)
                .setPayWay(TradeEnum.PayWay.WECHAT.getValue())
                .setOrderStatus(status);
        order.setDataStatus(0);
        order.setCreateTime(CREATE_TIME);
        order.setUpdateTime(FINISH_TIME);
        if (status == TradeEnum.OrderStatus.FINISHED.getValue()) {
            order.setFinishTime(FINISH_TIME);
        }
        return order;
    }

    private WsPackage rechargePackage() {
        WsPackage pkg = new WsPackage();
        pkg.setId(3L);
        pkg.setPackageName("100元500升卡");
        pkg.setPayAmount(10000L);
        pkg.setWaterMl(500000L);
        pkg.setBonusAmount(0L);
        pkg.setUnitPriceSnap("20.00");
        pkg.setExpireDays(null); // D-213：有限期只存在于历史快照，由调用方铸造
        return pkg;
    }

    private WsPayment pendingPayment(WsOrder order) {
        return paymentBase(order).setPayStatus(1);
    }

    private WsPayment successfulPayment(WsOrder order) {
        return paymentBase(order)
                .setPayStatus(2)
                .setTransactionId("SIMTX-DETAIL-1")
                .setPaySuccessTime(PAY_SUCCESS_TIME);
    }

    private WsPayment paymentBase(WsOrder order) {
        WsPayment payment = new WsPayment()
                .setId(PAYMENT_ID)
                .setOrderId(ORDER_ID)
                .setOrderNo(ORDER_NO)
                .setPayAmount(order.getOrderAmount())
                .setPaySource(2)
                .setCurrency("CNY")
                .setPayExpireTime("20260722103000");
        payment.setDataStatus(0);
        return payment;
    }

    private WsPaymentEvent processedSuccessEvent(WsOrder order, WsPayment payment) {
        WsPaymentEvent event = new WsPaymentEvent()
                .setId(704L)
                .setPaymentId(payment.getId())
                .setOrderId(order.getId())
                .setOrderNo(order.getOrderNo())
                .setPaySource(payment.getPaySource())
                .setTradeState("SUCCESS")
                .setTransactionId(payment.getTransactionId())
                .setPayAmount(payment.getPayAmount())
                .setCurrency(payment.getCurrency())
                .setPaySuccessTime(payment.getPaySuccessTime())
                .setProcessingStatus(3);
        event.setDataStatus(0);
        return event;
    }

    private WsWalletFlow completedFlow(WsOrder order) {
        RechargeCredit credit = RechargeCredit.of(RechargeSnapshot.parse(order.getPackageSnap()));
        WsWalletFlow flow = new WsWalletFlow()
                .setId(705L)
                .setCardId(CARD_ID)
                .setUserId(USER_ID)
                .setFlowType(1)
                .setAmountChange(credit.amountFen())
                .setMlChange(credit.ml())
                .setAmountAfter(credit.amountFen())
                .setMlAfter(credit.ml())
                .setOrderId(ORDER_ID)
                .setBizIdempotencyKey("RECHARGE:" + ORDER_NO);
        flow.setDataStatus(0);
        return flow;
    }

    private WsCard issuedCard(Long issueOrderId) {
        WsCard card = new WsCard()
                .setId(CARD_ID)
                .setCardNo(CARD_NO)
                .setCardType(1)
                .setUserId(USER_ID)
                .setBalanceAmount(0L)
                .setBalanceMl(500000L)
                .setScopeJson(WaterCardScope.normalize(SCOPE, "水卡").toCanonicalJson().toJSONString())
                .setExpireTime("20270722100500")
                .setCardStatus(1)
                .setIssueOrderId(issueOrderId);
        card.setDataStatus(0);
        return card;
    }
}

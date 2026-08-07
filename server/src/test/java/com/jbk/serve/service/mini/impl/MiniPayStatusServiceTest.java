package com.jbk.serve.service.mini.impl;

import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.service.mini.recharge.IRechargePaySourceAdapter;
import com.jbk.serve.service.mini.recharge.RechargePayExpire;
import com.jbk.serve.service.mini.recharge.RechargePayStatus;
import com.jbk.serve.service.mini.recharge.RechargeRefundEvidenceVerifier;
import com.jbk.serve.service.mini.card.WaterCardScope;
import com.jbk.serve.service.mini.recharge.RechargeSnapshot;
import com.jbk.tool.data.mini.bo.MiniPayStatusBo;
import com.jbk.tool.data.mini.vo.MiniPayStatusVo;
import com.jbk.tool.data.product.po.WsPackage;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsPaymentEvent;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * L2-ORDER 支付状态查询（契约 §9.1）。
 * 当前服务只读：任何用例都不得出现入账、改卡余额或写支付成功。
 *
 * <p>整类的立场是 fail-closed：关键对象缺失/重复/被逻辑删除/共键错位一律 MISMATCH 且不可轮询，
 * 绝不"尽力理解"一份自身不自洽的数据再拼出一个成功状态。</p>
 */
class MiniPayStatusServiceTest {

    private static final Long ME = 9L;
    private static final Long OTHER = 10L;
    private static final long ORDER_ID = 777L;
    private static final long PAYMENT_ID = 888L;
    private static final String ORDER_NO = "RC0000000000000000000000000001";
    private static final String UUID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String CREATE_TIME = "20260722100000";
    /** 永久卡：createTime + 30min。 */
    private static final String EXPIRE_TIME = "20260722103000";
    private static final String PAID_TIME = "20260722101000";
    private static final long AMOUNT = 9900L;

    private RechargeIdentityMapper identityMapper;
    private RechargeRefundEvidenceVerifier refundEvidenceVerifier;
    private MiniPayStatusServiceImpl service;

    private WsOrder order;
    private WsPayment payment;
    private List<WsPaymentEvent> events;
    private long liveFlows;
    private long deletedFlows;

    @BeforeEach
    void setup() {
        identityMapper = Mockito.mock(RechargeIdentityMapper.class);
        refundEvidenceVerifier = Mockito.mock(RechargeRefundEvidenceVerifier.class);
        service = new MiniPayStatusServiceImpl(identityMapper, refundEvidenceVerifier);
        order = order(CREATE_TIME, CREATE_TIME);
        payment = payment(EXPIRE_TIME);
        events = new ArrayList<>();
        liveFlows = 0L;
        deletedFlows = 0L;
    }

    // ================= 归属与存在性 =================

    // 1) 四种"查不到本人有效充值订单"的原因必须给出完全相同的一句话——
    //    否则文案差异本身就是订单号探测信道，攻击者可枚举出别人的订单号是否存在。
    @Test
    void ownershipFailuresShareOneIndistinguishableMessage() {
        String notFound = assertThrows(JbkException.class,
                () -> runWith(List.of(), List.of(payment))).getMessage();

        setup();
        order.setUserId(OTHER);
        String foreign = assertThrows(JbkException.class, this::run).getMessage();

        setup();
        order.setDataStatus(1);
        String deleted = assertThrows(JbkException.class, this::run).getMessage();

        setup();
        order.setOrderType(1);
        String wrongType = assertThrows(JbkException.class, this::run).getMessage();

        assertEquals("订单不存在或无权访问", notFound);
        assertEquals(notFound, foreign, "非本人订单不得与不存在区分");
        assertEquals(notFound, deleted, "被逻辑删除订单不得与不存在区分");
        assertEquals(notFound, wrongType, "非充值订单不得与不存在区分");
    }

    // 2) 同一订单号查出多条：唯一键已被破坏，不得挑一条继续解释
    @Test
    void duplicateOrderRowsRejected() {
        JbkException e = assertThrows(JbkException.class,
                () -> runWith(List.of(order, order(CREATE_TIME, CREATE_TIME)), List.of(payment)));
        assertEquals("订单不存在或无权访问", e.getMessage());
    }

    // ================= 支付单与共键 =================

    // 3) 支付单必须恰好一条：0 条说明创单未闭环，2 条说明重复收款风险，都不能自动挑一条
    @Test
    void paymentMustBeExactlyOne() {
        assertMismatch(runWith(List.of(order), List.of()), "支付单必须恰好一条");
        setup();
        assertMismatch(runWith(List.of(order), List.of(payment, payment(EXPIRE_TIME))), "支付单必须恰好一条");
    }

    // 4) 支付单被逻辑删除：账面已被抹除，若忽略 DATA_STATUS 就会拿一条"已删除"的支付单报状态
    @Test
    void deletedPaymentIsPollution() {
        payment.setDataStatus(1);
        assertMismatch(run(), "支付单被逻辑删除，视为污染");
    }

    // 5) 支付单与订单三项共键任一错位：错位即意味着这笔钱可能属于别的订单
    @Test
    void paymentKeyMisalignmentRejected() {
        payment.setOrderId(ORDER_ID + 1);
        assertMismatch(run(), "支付单与订单共键错位");

        setup();
        payment.setOrderNo("RC0000000000000000000000000002");
        assertMismatch(run(), "支付单与订单共键错位");

        setup();
        payment.setPayAmount(AMOUNT + 1);
        assertMismatch(run(), "支付单与订单共键错位");
    }

    // 6) 支付方式非微信：当前服务只承认 PAY_WAY=1，其余组合的金额语义未冻结，不得解释。
    @Test
    void nonWechatPayWayRejected() {
        order.setPayWay(2);
        assertMismatch(run(), "订单支付方式不符");
    }

    // 7) 付款截止时间为空：没有截止时间就无法判定"是否按时支付"，等于放行迟到付款
    @Test
    void blankPayExpireRejected() {
        payment.setPayExpireTime(null);
        assertMismatch(run(), "付款截止时间缺失");
    }

    // ================= 快照锚点 =================

    // 8) 快照解析失败：解析不了就无从核对截止时间，不能"尽力理解"后继续
    @Test
    void unparsableSnapshotRejected() {
        order.setPackageSnap("not-a-json");
        assertMismatch(run(), "订单快照错位");
    }

    // 9) 防自指锚点：capturedTime 必须等于订单 createTime。
    //    少了这条，下面的截止时间重算就变成"用快照验快照"，改一份快照即可自洽，
    //    篡改者可以把付款窗口任意延长。
    @Test
    void snapshotCapturedTimeMustAnchorToOrderCreateTime() {
        order = order("20260722090000", CREATE_TIME);
        // 让截止时间与被篡改的快照自洽（09:30），证明只有锚点检查能拦住它
        payment.setPayExpireTime("20260722093000");
        assertMismatch(run(), "快照采集时间与订单创建时间不一致");
    }

    // 10) 截止时间必须等于按冻结算法的重算值：不等即说明创单后被改写，付款资格已不可信
    @Test
    void tamperedPayExpireRejected() {
        payment.setPayExpireTime("20260722110000");
        assertMismatch(run(), "付款截止时间与资格快照不一致");
    }

    // ================= 支付事件 =================

    // 11) 事件被逻辑删除：支付事实被抹除仍是污染，不能当它不存在继续解释状态
    @Test
    void deletedEventIsPollution() {
        events.add(notpay());
        events.get(0).setDataStatus(1);
        assertMismatch(run(), "存在被逻辑删除的支付事件，视为污染");
    }

    // 12) 事件 ORDER_ID 为 NULL：NULL 表示事务 A 未完成可信关联，不是"无需比对"。
    //     若写成 orderId != null && ... 短路放行，一条未关联的支付事实就会被当成已核对通过。
    @Test
    void nullEventOrderIdIsMisalignment() {
        WsPaymentEvent e = notpay();
        e.setOrderId(null);
        events.add(e);
        assertMismatch(run(), "支付事件与订单/支付单共键错位或未完成可信关联");
    }

    // 13) 事件 PAYMENT_ID 为 NULL：同上，未指向具体支付单的事实不得视为已核对
    @Test
    void nullEventPaymentIdIsMisalignment() {
        WsPaymentEvent e = notpay();
        e.setPaymentId(null);
        events.add(e);
        assertMismatch(run(), "支付事件与订单/支付单共键错位或未完成可信关联");
    }

    // 14) 事件三项共键各自错位：任一错位都可能是别单的支付事实被拼进本单
    @Test
    void eventKeyMisalignmentRejected() {
        WsPaymentEvent byOrderNo = notpay();
        byOrderNo.setOrderNo("RC0000000000000000000000000002");
        events.add(byOrderNo);
        assertMismatch(run(), "支付事件与订单/支付单共键错位或未完成可信关联");

        setup();
        WsPaymentEvent byOrderId = notpay();
        byOrderId.setOrderId(ORDER_ID + 1);
        events.add(byOrderId);
        assertMismatch(run(), "支付事件与订单/支付单共键错位或未完成可信关联");

        setup();
        WsPaymentEvent byPaymentId = notpay();
        byPaymentId.setPaymentId(PAYMENT_ID + 1);
        events.add(byPaymentId);
        assertMismatch(run(), "支付事件与订单/支付单共键错位或未完成可信关联");
    }

    // 15) 事件来源与支付单不一致：Pay-Sim 的事实绝不能用来解释一张微信支付单
    @Test
    void eventPaySourceMustMatchPayment() {
        WsPaymentEvent e = notpay();
        e.setPaySource(IRechargePaySourceAdapter.PAY_SIM);
        events.add(e);
        assertMismatch(run(), "支付事件与支付单来源不一致");
    }

    // 16) SUCCESS 事实四要素缺一不可：缺任一项都无法证明"这笔钱真的按时到了本单"。
    //     场景刻意坐在"已支付待入账"上——去掉这道校验，一份残缺的成功事实就会直接被报成已支付。
    @Test
    void successFactMissingRequiredFieldsRejected() {
        paidScene();
        events.add(success().setTransactionId(null));
        assertMismatch(run(), "成功事实缺少交易号/金额/币种/成功时间");

        setup();
        paidScene();
        events.add(success().setPayAmount(null));
        assertMismatch(run(), "成功事实缺少交易号/金额/币种/成功时间");

        setup();
        paidScene();
        events.add(success().setCurrency(null));
        assertMismatch(run(), "成功事实缺少交易号/金额/币种/成功时间");

        setup();
        paidScene();
        events.add(success().setPaySuccessTime(null));
        assertMismatch(run(), "成功事实缺少交易号/金额/币种/成功时间");
    }

    // 17) SUCCESS 金额与支付单不符：少收的钱不得被认成付清
    @Test
    void successAmountMustEqualPayment() {
        paidScene();
        events.add(success().setPayAmount(1L));
        assertMismatch(run(), "成功事实金额与支付单不一致");
    }

    // 18) SUCCESS 币种非 CNY：9900 美分与 9900 分不是同一笔钱，币种漏检等于按面值收外币
    @Test
    void successCurrencyMustBeCny() {
        paidScene();
        events.add(success().setCurrency("USD"));
        assertMismatch(run(), "成功事实币种非 CNY");
    }

    // 19) payment 已回填权威交易号时，事件交易号必须一致：否则跨单交易号会被拼进本单充作凭证
    @Test
    void crossOrderTransactionIdRejected() {
        paidScene();
        payment.setTransactionId("TX_AUTHORITATIVE");
        events.add(success().setTransactionId("TX_FROM_ANOTHER_ORDER"));
        assertMismatch(run(), "成功事实交易号与支付单不一致");
    }

    // 20) payment 已回填成功时间时，事件成功时间必须一致：否则可用另一时点的事实绕过按时判定
    @Test
    void mismatchedPaySuccessTimeRejected() {
        paidScene();
        payment.setPaySuccessTime(PAID_TIME);
        events.add(success().setPaySuccessTime("20260722102900"));
        assertMismatch(run(), "成功事实成功时间与支付单不一致");
    }

    // 21) 非成功事实同样要对金额：支付方在 NOTPAY/CLOSED 里回报的金额对不上，说明报文根本不属于本单
    @Test
    void nonSuccessFactAmountMustMatchWhenPresent() {
        events.add(notpay().setPayAmount(1L));
        assertMismatch(run(), "支付事实金额与支付单不一致");
    }

    // ================= 流水（DATA_STATUS 分流） =================

    // 22) 存在被逻辑删除的充值流水：账本已被抹除，仍报状态就等于对一笔来历不明的入账背书
    @Test
    void deletedRechargeFlowIsPollution() {
        deletedFlows = 1L;
        assertMismatch(run(), "存在被逻辑删除的充值流水");
    }

    // 23) live=1 且 deleted=1：若不按 DATA_STATUS 分流，这条订单会以"恰好一条流水"蒙混成 COMPLETED，
    //     把账本已抹除的订单报成"已到账"；反向地 L2-T 接上后会误判成"没入过账"而重复入账。
    @Test
    void deletedFlowCheckPrecedesExactlyOneLiveFlow() {
        completedScene();
        liveFlows = 1L;
        deletedFlows = 1L;
        assertMismatch(run(), "存在被逻辑删除的充值流水");
    }

    // ================= 正常路径与出参 =================

    // 24) 待支付：允许客户端继续轮询，否则用户付完款也看不到状态翻转
    @Test
    void waitingPayment() {
        MiniPayStatusVo vo = run();
        assertEquals("WAITING_PAYMENT", vo.getPayStatusCode());
        assertTrue(vo.getRetryable());
        assertEquals("WAITING_PAYMENT", vo.getProcessingStatus());
        assertEquals(ORDER_NO, vo.getOrderNo());
        assertEquals(EXPIRE_TIME, vo.getPayExpireTime());
        assertEquals(IRechargePaySourceAdapter.WECHAT, vo.getPaySource());
        assertNull(vo.getFinishTime());
    }

    // 25) 已支付待入账：权益还没到账，必须继续轮询
    @Test
    void paidCreditPending() {
        paidPendingScene();
        MiniPayStatusVo vo = run();
        assertEquals("PAID_CREDIT_PENDING", vo.getPayStatusCode());
        assertTrue(vo.getRetryable());
    }

    // 26) 已完成：终态不得再可轮询，否则客户端会对已到账订单无限轮询
    @Test
    void completed() {
        completedScene();
        liveFlows = 1L;
        MiniPayStatusVo vo = run();
        assertEquals("COMPLETED", vo.getPayStatusCode());
        assertFalse(vo.getRetryable(), "终态不得继续轮询");
        assertEquals("20260722101500", vo.getFinishTime());
    }

    // 27) 订单关闭：终态，且必须透出 PAY_STATUS=4 的真值
    @Test
    void closed() {
        closedScene();
        MiniPayStatusVo vo = run();
        assertEquals("CLOSED", vo.getPayStatusCode());
        assertFalse(vo.getRetryable(), "终态不得继续轮询");
    }

    // 28) order=7/8 退款：原支付/入账与售后证据共同成立，返回不可轮询终态
    @Test
    void refundedStatesAreTerminalAfterEvidenceVerification() {
        payment.setPayStatus(RechargePayStatus.PAY_SUCCESS);
        order.setOrderStatus(RechargePayStatus.ORDER_REFUNDED);
        order.setFinishTime("20260722102000");
        events.add(success().setProcessingStatus(RechargePayStatus.P_PROCESSED));
        liveFlows = 1L;
        MiniPayStatusVo vo = run();
        assertEquals("REFUNDED", vo.getPayStatusCode());
        assertFalse(vo.getRetryable());

        setup();
        payment.setPayStatus(RechargePayStatus.PAY_SUCCESS);
        order.setOrderStatus(RechargePayStatus.ORDER_PART_REFUNDED);
        order.setFinishTime("20260722102000");
        events.add(success().setProcessingStatus(RechargePayStatus.P_PROCESSED));
        liveFlows = 1L;
        assertEquals("PART_REFUNDED", run().getPayStatusCode());
    }

    @Test
    void refundedStateFailsClosedWhenAfterSaleEvidenceIsInvalid() {
        payment.setPayStatus(RechargePayStatus.PAY_SUCCESS);
        order.setOrderStatus(RechargePayStatus.ORDER_REFUNDED);
        order.setFinishTime("20260722102000");
        events.add(success().setProcessingStatus(RechargePayStatus.P_PROCESSED));
        liveFlows = 1L;
        Mockito.doThrow(new JbkException("充值退款证据不一致：缺少售后动作"))
                .when(refundEvidenceVerifier).requireIfRefunded(order);

        assertMismatch(run(), "缺少售后动作");
    }

    // 29) PAY_STATUS 必须透传库里的真值。硬编码成 1 会让已支付成功的订单在前端永远显示"待支付"，
    //     用户看不到到账、只会重复付款。
    @Test
    void payStatusIsPassedThroughNotHardcoded() {
        paidPendingScene();
        assertEquals(RechargePayStatus.PAY_SUCCESS, run().getPayStatus(), "已支付订单必须透出 payStatus=2");

        setup();
        closedScene();
        assertEquals(RechargePayStatus.PAY_CLOSED, run().getPayStatus(), "已关闭订单必须透出 payStatus=4");
    }

    // 30) 多事件处理态聚合：PROCESSING 优先于 PENDING，展示给用户的是最"要紧"的那一条
    @Test
    void processingStatusAggregatesByPriority() {
        paidPendingScene();
        events.add(success().setProcessingStatus(RechargePayStatus.P_PROCESSING));
        assertEquals("PROCESSING", run().getProcessingStatus());
    }

    // ---------------------------------------------------------------------

    /** 只摆好"已支付待入账"的组合，不放事件——由用例自己放一条被做过手脚的成功事实。 */
    private void paidScene() {
        payment.setPayStatus(RechargePayStatus.PAY_SUCCESS);
        order.setOrderStatus(RechargePayStatus.ORDER_PAID);
    }

    private void paidPendingScene() {
        paidScene();
        events.add(success().setProcessingStatus(RechargePayStatus.P_PENDING));
    }

    private void completedScene() {
        payment.setPayStatus(RechargePayStatus.PAY_SUCCESS);
        order.setOrderStatus(RechargePayStatus.ORDER_FINISHED);
        order.setFinishTime("20260722101500");
        events.add(success().setProcessingStatus(RechargePayStatus.P_PROCESSED));
    }

    private void closedScene() {
        payment.setPayStatus(RechargePayStatus.PAY_CLOSED);
        order.setOrderStatus(RechargePayStatus.ORDER_CANCELLED);
        WsPaymentEvent e = event();
        e.setTradeState(RechargePayStatus.CLOSED);
        e.setProcessingStatus(RechargePayStatus.P_PROCESSED);
        events.add(e);
    }

    private void assertMismatch(MiniPayStatusVo vo, String reason) {
        assertEquals("MISMATCH", vo.getPayStatusCode());
        assertTrue(vo.getStatusMessage() != null && vo.getStatusMessage().contains(reason),
                "必须给出具体原因「" + reason + "」，实际：" + vo.getStatusMessage());
        assertFalse(vo.getRetryable(), "任何不一致都不得允许继续轮询");
    }

    private MiniPayStatusVo run() {
        return runWith(List.of(order), List.of(payment));
    }

    private MiniPayStatusVo runWith(List<WsOrder> orders, List<WsPayment> payments) {
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(anyString())).thenReturn(orders);
        when(identityMapper.selectPaymentsByOrderIdIncludingDeleted(anyLong())).thenReturn(payments);
        when(identityMapper.selectEventsByOrderNoIncludingDeleted(anyString())).thenReturn(events);
        when(identityMapper.countLiveRechargeFlows(anyLong())).thenReturn(liveFlows);
        when(identityMapper.countDeletedRechargeFlows(anyLong())).thenReturn(deletedFlows);
        MiniPayStatusBo bo = new MiniPayStatusBo();
        bo.setOrderNo(ORDER_NO);
        return service.query(bo, ME);
    }

    /** @param capturedTime 写进 PACKAGE_SNAP 的采集时刻（与 createTime 分开传，用于构造锚点错位场景） */
    private WsOrder order(String capturedTime, String createTime) {
        WsCard c = new WsCard();
        c.setId(100L);
        c.setUserId(ME);
        c.setCardStatus(1);
        c.setScopeJson("{\"scopeType\":\"specified\",\"stationIds\":[1]}");
        WsPackage p = new WsPackage();
        p.setId(3L);
        p.setPackageName("季卡 100L");
        p.setPayAmount(AMOUNT);
        p.setWaterMl(100000L);
        p.setBonusAmount(0L);
        p.setUnitPriceSnap("20.00");
        p.setPackageStatus(1);
        String snap = RechargeSnapshot.build(UUID, p, c,
                WaterCardScope.normalize(c.getScopeJson(), "水卡"), null, capturedTime);

        WsOrder o = new WsOrder();
        o.setId(ORDER_ID);
        o.setOrderNo(ORDER_NO);
        o.setOrderType(2);
        o.setUserId(ME);
        o.setCardId(100L);
        o.setPackageId(3L);
        o.setPackageSnap(snap);
        o.setOrderAmount(AMOUNT);
        o.setPayWay(1);
        o.setOrderStatus(RechargePayStatus.ORDER_PENDING);
        o.setDataStatus(0);
        o.setCreateTime(createTime);
        return o;
    }

    private WsPayment payment(String payExpireTime) {
        WsPayment p = new WsPayment();
        p.setId(PAYMENT_ID);
        p.setOrderId(ORDER_ID);
        p.setOrderNo(ORDER_NO);
        p.setPayAmount(AMOUNT);
        p.setPayStatus(RechargePayStatus.PAY_PENDING);
        p.setPaySource(IRechargePaySourceAdapter.WECHAT);
        p.setCurrency("CNY");
        p.setPayExpireTime(payExpireTime);
        p.setDataStatus(0);
        return p;
    }

    private WsPaymentEvent event() {
        WsPaymentEvent e = new WsPaymentEvent();
        e.setId(1L);
        e.setOrderNo(ORDER_NO);
        e.setOrderId(ORDER_ID);
        e.setPaymentId(PAYMENT_ID);
        e.setPaySource(IRechargePaySourceAdapter.WECHAT);
        e.setDataStatus(0);
        return e;
    }

    private WsPaymentEvent notpay() {
        WsPaymentEvent e = event();
        e.setTradeState(RechargePayStatus.NOTPAY);
        e.setProcessingStatus(RechargePayStatus.P_PROCESSED);
        return e;
    }

    private WsPaymentEvent success() {
        WsPaymentEvent e = event();
        e.setTradeState(RechargePayStatus.SUCCESS);
        e.setTransactionId("TX_AUTHORITATIVE");
        e.setPayAmount(AMOUNT);
        e.setCurrency("CNY");
        e.setPaySuccessTime(PAID_TIME);
        e.setProcessingStatus(RechargePayStatus.P_PENDING);
        return e;
    }

    /** 冻结算法自校验：EXPIRE_TIME 常量必须与生产算法一致，否则整个测试基线都是错的。 */
    @Test
    void fixtureExpireTimeMatchesFrozenAlgorithm() {
        assertEquals(EXPIRE_TIME, RechargePayExpire.compute(CREATE_TIME, null));
    }

    // ================= 变异存活补洞（对抗验证发现，均为原用例覆盖不足） =================

    /**
     * 金额校验必须是**等值**，不是"不许少收"。
     * 原用例只构造了少收方向，把严格相等退化成 {@code e.payAmount < payment.payAmount} 照样全绿——
     * 于是跨单报文（把 199 元的成功事实拼到 99 元单上）会被判成"金额没问题"并推进到已支付。
     */
    @Test
    void successAmountOvercollectedAlsoRejected() {
        paidScene();
        events.add(success().setPayAmount(AMOUNT + 1));
        MiniPayStatusVo vo = run();
        assertEquals("MISMATCH", vo.getPayStatusCode());
        assertTrue(vo.getStatusMessage().contains("金额"), vo.getStatusMessage());
        assertFalse(vo.getRetryable());
    }

    /**
     * RECONCILIATION_REQUIRED 是「已扣款、订单异常、待人工」的终态，**绝不可轮询**。
     * 原用例完全没走到 pay=2/order=6 这条分支，把它加进 RETRYABLE 集合不会红——
     * 后果是小程序对一批已出问题的付款订单无限轮询，故障时刻放大 QPS，
     * 用户看到永远"加载中"而不是"请联系客服"，人工对账窗口被无声吞掉。
     */
    @Test
    void reconciliationRequiredIsTerminalAndNotRetryable() {
        payment.setPayStatus(RechargePayStatus.PAY_SUCCESS);
        order.setOrderStatus(RechargePayStatus.ORDER_ABNORMAL);
        events.add(success().setProcessingStatus(RechargePayStatus.P_RECONCILIATION));
        MiniPayStatusVo vo = run();
        assertEquals("RECONCILIATION_REQUIRED", vo.getPayStatusCode());
        assertFalse(vo.getRetryable(), "待人工对账是终态，继续轮询会吞掉人工介入窗口");
        assertEquals("RECONCILIATION_REQUIRED", vo.getProcessingStatus());
    }

    /** 入口守卫零覆盖：删掉「参数不完整」整段不会红，空白 orderNo 会带着空串去查库，null 直接 NPE。 */
    @Test
    void missingParametersRejectedBeforeAnyQuery() {
        MiniPayStatusBo bo = new MiniPayStatusBo();
        bo.setOrderNo(ORDER_NO);
        assertThrows(JbkException.class, () -> service.query(bo, null), "userId 为空必须拒绝");

        MiniPayStatusBo blank = new MiniPayStatusBo();
        blank.setOrderNo("   ");
        assertThrows(JbkException.class, () -> service.query(blank, ME), "空白 orderNo 必须拒绝");

        MiniPayStatusBo nullNo = new MiniPayStatusBo();
        assertThrows(JbkException.class, () -> service.query(nullNo, ME), "orderNo 为 null 必须拒绝而不是 NPE");

        Mockito.verifyNoInteractions(identityMapper);
    }

    /** 订单号首尾空白必须被规范化：去掉 trim 后用户粘贴带换行的单号会查不到，且与"非本人"同措辞无法排障。 */
    @Test
    void orderNoIsTrimmedBeforeLookup() {
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(ORDER_NO)).thenReturn(List.of(order));
        when(identityMapper.selectPaymentsByOrderIdIncludingDeleted(anyLong())).thenReturn(List.of(payment));
        when(identityMapper.selectEventsByOrderNoIncludingDeleted(anyString())).thenReturn(events);
        when(identityMapper.countLiveRechargeFlows(anyLong())).thenReturn(0L);
        when(identityMapper.countDeletedRechargeFlows(anyLong())).thenReturn(0L);

        MiniPayStatusBo bo = new MiniPayStatusBo();
        bo.setOrderNo("  " + ORDER_NO + "\n");
        assertEquals("WAITING_PAYMENT", service.query(bo, ME).getPayStatusCode());
    }
}

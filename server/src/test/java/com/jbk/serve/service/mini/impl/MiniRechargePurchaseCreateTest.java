package com.jbk.serve.service.mini.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.product.WsPackageMapper;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.service.mini.recharge.IRechargeCreateTx;
import com.jbk.serve.service.mini.recharge.IRechargePaySourceAdapter;
import com.jbk.serve.service.mini.recharge.RechargeRefundEvidenceVerifier;
import com.jbk.serve.service.mini.recharge.RechargeOrderNo;
import com.jbk.serve.service.mini.recharge.RechargePayExpire;
import com.jbk.serve.service.mini.card.WaterCardScope;
import com.jbk.serve.service.mini.recharge.RechargeSnapshot;
import com.jbk.tool.data.mini.bo.MiniRechargeCreateBo;
import com.jbk.tool.data.mini.vo.MiniRechargeOrderVo;
import com.jbk.tool.data.product.po.WsPackage;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2-A 首次购卡创单（决策 A1/A2/A4/A6/A7）。
 *
 * <p>套餐读取沿用 {@link MiniRechargeCreateTest} 的「按 wrapper 条件求值的假库」手法：
 * 生产代码写进 WHERE 的在售条件必须真正参与判定，不得用 {@code any()} 旁路。
 * 购卡路径<b>不读卡</b>——cardService 若被触碰即测试失败，防止发卡前偷读不存在的卡。</p>
 */
class MiniRechargePurchaseCreateTest {

    private RechargeIdentityMapper identityMapper;
    private WsPackageMapper packageMapper;
    private com.jbk.serve.service.user.IWsCardService cardService;
    private IRechargeCreateTx createTx;
    private MiniRechargeServiceImpl service;

    /** 假库里那一行套餐；用例改它来摆布“库里有什么”。 */
    private WsPackage dbPackage;

    private static final Long ME = 9L;
    private static final long PKG_ID = 3L;
    private static final long CARD_ID = 100L;
    private static final String UUID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String PKG_SCOPE = "{\"scopeType\":\"specified\",\"stationIds\":[1]}";

    private static final Pattern EQ_COND =
            Pattern.compile("([A-Z_0-9]+)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)}");

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(a, WsCard.class);
        TableInfoHelper.initTableInfo(a, WsPackage.class);
    }

    @BeforeEach
    void setup() {
        identityMapper = Mockito.mock(RechargeIdentityMapper.class);
        packageMapper = Mockito.mock(WsPackageMapper.class);
        cardService = Mockito.mock(com.jbk.serve.service.user.IWsCardService.class);
        createTx = Mockito.mock(IRechargeCreateTx.class);
        IRechargePaySourceAdapter paySource = () -> IRechargePaySourceAdapter.WECHAT;
        service = new MiniRechargeServiceImpl(new com.jbk.serve.service.settlement.IInviteService() {
                    public String myInviteCode(Long userId) { return "IVTEST0000"; }
                    public void bindReferrer(Long userId, String inviteCode) { }
                    public Long referrerSnapshotOf(Long userId) { return null; }
                }, identityMapper, packageMapper, cardService, createTx, paySource,
                new MiniPayStatusServiceImpl(identityMapper,
                        Mockito.mock(RechargeRefundEvidenceVerifier.class)));
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(anyString())).thenReturn(List.of());
        when(identityMapper.selectEventsByOrderNoIncludingDeleted(anyString())).thenReturn(List.of());
        when(identityMapper.selectCountLiveCardsByUser(ME)).thenReturn(0L);
        dbPackage = pkg(null, PKG_SCOPE); // D-213：可售套餐一律永久
        when(packageMapper.selectOne(any(Wrapper.class))).thenAnswer(inv ->
                dbPackage != null && matches(inv.getArgument(0), packageRow(dbPackage)) ? dbPackage : null);
        when(createTx.create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> {
                    WsOrder o = inv.getArgument(0);
                    o.setId(777L);
                    return o;
                });
    }

    // 1) 正常购卡创单：CARD_ID=NULL、purchase 快照、PAY_EXPIRE=+30min、全程不读卡
    @Test
    void purchaseCreatesCardlessPendingOrderWithPurchaseSnapshot() {
        MiniRechargeOrderVo vo = service.create(purchaseBo(String.valueOf(PKG_ID)), ME);

        assertEquals(1, vo.getOrderStatus());
        assertEquals(1, vo.getPayStatus());
        assertEquals(9900L, vo.getOrderAmountFen());
        assertFalse(vo.getIdempotentHit());
        assertNull(vo.getCardId(), "决策 A2：待支付阶段不得预建卡");
        assertTrue(vo.getOrderNo().startsWith("RC") && vo.getOrderNo().length() == 32);

        ArgumentCaptor<WsOrder> orderCap = ArgumentCaptor.forClass(WsOrder.class);
        ArgumentCaptor<String> expireCap = ArgumentCaptor.forClass(String.class);
        verify(createTx).create(orderCap.capture(), expireCap.capture(),
                org.mockito.ArgumentMatchers.anyInt());
        WsOrder order = orderCap.getValue();
        assertNull(order.getCardId(), "订单 CARD_ID 必须为 NULL，由发卡事务回填");
        // 决策 A1：首次购卡 PAY_EXPIRE_TIME = createTime + 30min（无卡有效期可截短）
        assertEquals(RechargePayExpire.compute(order.getCreateTime(), null), expireCap.getValue());

        RechargeSnapshot.Parsed snap = RechargeSnapshot.parse(order.getPackageSnap());
        assertEquals(RechargeSnapshot.PURCHASE_MODE_FIRST_CARD, snap.purchaseMode());
        assertEquals(Integer.valueOf(RechargeSnapshot.PLANNED_CARD_TYPE_VIRTUAL), snap.plannedCardType());
        assertNull(snap.expireDays(), "D-213：可售套餐一律永久，快照有效期恒为 null");
        assertNull(snap.cardStatusAtCreate(), "purchase 快照没有目标卡资格状态字段");
        assertNull(snap.expireTimeAtCreate());
        // 决策 A6：新卡精确继承套餐范围
        assertTrue(snap.cardScope().sameAuthorityAs(WaterCardScope.normalize(PKG_SCOPE, "套餐")));
        assertTrue(snap.packageScope().sameAuthorityAs(snap.cardScope()));

        // 购卡路径全程不读卡：不存在“待支付水卡”，也不许拿别的卡凑数
        verify(cardService, never()).getOne(any(Wrapper.class));
    }

    // 2) 资格（决策 A4）：存在任意 DATA_STATUS=0 的卡（含冻结/过期/注销）即拒绝，且先于套餐读取
    @Test
    void anyLiveCardBlocksPurchase() {
        when(identityMapper.selectCountLiveCardsByUser(ME)).thenReturn(1L);
        JbkException ex = assertThrows(JbkException.class,
                () -> service.create(purchaseBo(String.valueOf(PKG_ID)), ME));
        assertTrue(ex.getMessage().contains("已有水卡，请直接充值"), "实际=" + ex.getMessage());
        verify(createTx, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(packageMapper, never()).selectOne(any(Wrapper.class));
    }

    // 3) 套餐范围（决策 A6）：SCOPE_JSON 空=未配置默认拒绝；非法 JSON 同样拒绝，绝不自动扩为 all
    @Test
    void packageWithoutUsableScopeRejected() {
        dbPackage = pkg(null, null);
        JbkException blank = assertThrows(JbkException.class,
                () -> service.create(purchaseBo(String.valueOf(PKG_ID)), ME));
        assertTrue(blank.getMessage().contains("套餐未配置可用范围"), "实际=" + blank.getMessage());

        dbPackage = pkg(null, "  ");
        assertThrows(JbkException.class, () -> service.create(purchaseBo(String.valueOf(PKG_ID)), ME));

        dbPackage = pkg(null, "{\"scopeType\":\"weird\"}");
        assertThrows(JbkException.class, () -> service.create(purchaseBo(String.valueOf(PKG_ID)), ME));
        verify(createTx, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // 4) 永久套餐（expireDays=NULL）同样可购卡创单
    @Test
    void permanentPackagePurchaseAllowed() {
        dbPackage = pkg(null, PKG_SCOPE);
        MiniRechargeOrderVo vo = service.create(purchaseBo(String.valueOf(PKG_ID)), ME);
        assertEquals(1, vo.getOrderStatus());
        ArgumentCaptor<WsOrder> cap = ArgumentCaptor.forClass(WsOrder.class);
        verify(createTx).create(cap.capture(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        assertNull(RechargeSnapshot.parse(cap.getValue().getPackageSnap()).expireDays());
    }

    // 5) 幂等模式切换（决策 A7）：purchase 请求命中 recharge 单 → 拒绝
    @Test
    void purchaseRequestHittingRechargeOrderRejected() {
        String orderNo = RechargeOrderNo.derive(ME, UUID);
        WsOrder recharge = rechargeOrder(orderNo, "20260722100000");
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(orderNo)).thenReturn(List.of(recharge));

        JbkException ex = assertThrows(JbkException.class,
                () -> service.create(purchaseBo(String.valueOf(PKG_ID)), ME));
        assertTrue(ex.getMessage().contains("不可切换购卡/充值模式"), "实际=" + ex.getMessage());
        verify(createTx, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // 6) 幂等模式切换（决策 A7）：cardId 传了值命中 purchase 单 → 拒绝，即使值恰好等于回填后的 CARD_ID
    @Test
    void rechargeRequestHittingPurchaseOrderRejected() {
        String orderNo = RechargeOrderNo.derive(ME, UUID);
        WsOrder purchase = purchaseOrder(orderNo, "20260722100000");
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(orderNo)).thenReturn(List.of(purchase));

        MiniRechargeCreateBo withCard = purchaseBo(String.valueOf(PKG_ID));
        withCard.setCardId(String.valueOf(CARD_ID));
        JbkException ex = assertThrows(JbkException.class, () -> service.create(withCard, ME));
        assertTrue(ex.getMessage().contains("不可切换购卡/充值模式"), "实际=" + ex.getMessage());

        // 已完成购卡单 CARD_ID 已被回填：即使传的 cardId 与之相等，仍是模式切换，必须拒绝
        purchase.setCardId(CARD_ID);
        purchase.setOrderStatus(4);
        purchase.setFinishTime("20260722100500");
        assertThrows(JbkException.class, () -> service.create(withCard, ME));
        verify(createTx, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // 7) purchase 幂等命中：返回原订单，不新建、不重读套餐
    @Test
    void purchaseIdempotentHitReturnsOriginal() {
        String orderNo = RechargeOrderNo.derive(ME, UUID);
        WsOrder purchase = purchaseOrder(orderNo, "20260722100000");
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(orderNo)).thenReturn(List.of(purchase));
        when(identityMapper.selectPaymentsByOrderIdIncludingDeleted(purchase.getId()))
                .thenReturn(List.of(payment(purchase, RechargePayExpire.compute("20260722100000", null))));

        MiniRechargeOrderVo vo = service.create(purchaseBo(String.valueOf(PKG_ID)), ME);

        assertTrue(vo.getIdempotentHit());
        assertEquals(orderNo, vo.getOrderNo());
        assertNull(vo.getCardId());
        verify(createTx, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(packageMapper, never()).selectOne(any(Wrapper.class));
        verify(cardService, never()).getOne(any(Wrapper.class));
    }

    // 8) 同 requestId 改 packageId：拒绝且零副作用
    @Test
    void purchaseIdempotentHitRejectsChangedPackage() {
        String orderNo = RechargeOrderNo.derive(ME, UUID);
        WsOrder purchase = purchaseOrder(orderNo, "20260722100000");
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(orderNo)).thenReturn(List.of(purchase));
        when(identityMapper.selectPaymentsByOrderIdIncludingDeleted(purchase.getId()))
                .thenReturn(List.of(payment(purchase, RechargePayExpire.compute("20260722100000", null))));

        assertThrows(JbkException.class, () -> service.create(purchaseBo("999"), ME));
        verify(createTx, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // 9) purchase 幂等命中同样校验 PAY_EXPIRE_TIME 未被改写（冻结算法 = createTime + 30min）
    @Test
    void purchaseIdempotentHitRejectsTamperedPayExpire() {
        String orderNo = RechargeOrderNo.derive(ME, UUID);
        WsOrder purchase = purchaseOrder(orderNo, "20260722100000");
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(orderNo)).thenReturn(List.of(purchase));
        // 应为 10:30，被改成 11:00
        when(identityMapper.selectPaymentsByOrderIdIncludingDeleted(purchase.getId()))
                .thenReturn(List.of(payment(purchase, "20260722110000")));

        assertThrows(JbkException.class, () -> service.create(purchaseBo(String.valueOf(PKG_ID)), ME));
    }

    // ---------------------------------------------------------------------

    private static boolean matches(Wrapper<?> wrapper, Map<String, Object> row) {
        String segment = wrapper.getSqlSegment();
        Map<String, Object> params = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
        Matcher m = EQ_COND.matcher(segment);
        boolean any = false;
        while (m.find()) {
            any = true;
            String column = m.group(1);
            if (!row.containsKey(column)) {
                throw new IllegalStateException("假库不认识列 " + column + "，请同步更新测试数据行：" + segment);
            }
            Object expected = params.get(m.group(2));
            if (!String.valueOf(row.get(column)).equals(String.valueOf(expected))) {
                return false;
            }
        }
        if (!any) {
            throw new IllegalStateException("查询未产生任何等值条件，判定已失效：" + segment);
        }
        return true;
    }

    private static Map<String, Object> packageRow(WsPackage p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", p.getId());
        row.put("PACKAGE_STATUS", p.getPackageStatus());
        return row;
    }

    private WsPackage pkg(Integer expireDays, String scopeJson) {
        WsPackage p = new WsPackage();
        p.setId(PKG_ID);
        p.setPackageName("季卡 100L");
        p.setPayAmount(9900L);
        p.setWaterMl(100000L);
        p.setBonusAmount(0L);
        p.setUnitPriceSnap("20.00");
        p.setPackageStatus(1);
        p.setExpireDays(expireDays);
        p.setScopeJson(scopeJson);
        return p;
    }

    private MiniRechargeCreateBo purchaseBo(String packageId) {
        MiniRechargeCreateBo b = new MiniRechargeCreateBo();
        // cardId 缺省 = 首次购卡
        b.setPackageId(packageId);
        b.setRequestId(UUID);
        return b;
    }

    /** 既有 purchase 单：CARD_ID=NULL + buildForPurchase 快照。 */
    private WsOrder purchaseOrder(String orderNo, String createTime) {
        String snap = RechargeSnapshot.buildForPurchase(UUID, pkg(null, PKG_SCOPE),
                WaterCardScope.normalize(PKG_SCOPE, "套餐"), createTime);
        WsOrder o = new WsOrder();
        o.setId(777L);
        o.setOrderNo(orderNo);
        o.setOrderType(2);
        o.setUserId(ME);
        o.setCardId(null);
        o.setPackageId(PKG_ID);
        o.setPackageSnap(snap);
        o.setOrderAmount(9900L);
        o.setPayWay(1);
        o.setOrderStatus(1);
        o.setDataStatus(0);
        o.setCreateTime(createTime);
        return o;
    }

    /** 既有 recharge 单：带目标卡与 L2-B 快照。 */
    private WsOrder rechargeOrder(String orderNo, String createTime) {
        WsCard c = new WsCard();
        c.setId(CARD_ID);
        c.setUserId(ME);
        c.setCardStatus(1);
        c.setScopeJson(PKG_SCOPE);
        c.setExpireTime(null);
        String snap = RechargeSnapshot.build(UUID, pkg(null, null), c,
                WaterCardScope.normalize(PKG_SCOPE, "水卡"), null, createTime);
        WsOrder o = new WsOrder();
        o.setId(778L);
        o.setOrderNo(orderNo);
        o.setOrderType(2);
        o.setUserId(ME);
        o.setCardId(CARD_ID);
        o.setPackageId(PKG_ID);
        o.setPackageSnap(snap);
        o.setOrderAmount(9900L);
        o.setPayWay(1);
        o.setOrderStatus(1);
        o.setDataStatus(0);
        o.setCreateTime(createTime);
        return o;
    }

    private WsPayment payment(WsOrder order, String payExpireTime) {
        WsPayment p = new WsPayment();
        p.setId(888L);
        p.setOrderId(order.getId());
        p.setOrderNo(order.getOrderNo());
        p.setPayAmount(order.getOrderAmount());
        p.setPayStatus(1);
        p.setPaySource(IRechargePaySourceAdapter.WECHAT);
        p.setCurrency("CNY");
        p.setPayExpireTime(payExpireTime);
        p.setDataStatus(0);
        p.setCreateTime(order.getCreateTime());
        p.setUpdateTime(order.getCreateTime());
        return p;
    }
}

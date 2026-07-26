package com.jbk.serve.service.mini.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.product.WsPackageMapper;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.service.mini.recharge.IRechargeCreateTx;
import com.jbk.serve.service.mini.recharge.IRechargePaySourceAdapter;
import com.jbk.serve.service.mini.recharge.RechargeOrderNo;
import com.jbk.serve.service.mini.recharge.RechargeSnapshot;
import com.jbk.tool.data.mini.bo.MiniRechargeCreateBo;
import com.jbk.tool.data.mini.vo.MiniRechargeOrderVo;
import com.jbk.tool.data.product.po.WsPackage;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.trade.po.WsPaymentEvent;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2-ORDER 创建与幂等（契约 §4.2 / §14.2）。
 * 创单服务只落待支付订单：任何用例都不得出现入账、改卡余额或支付成功。
 *
 * <p>卡与套餐的读取不用 {@code thenReturn} 直接喂结果，而是走 {@link #matches} 假库：
 * 生产代码写进 WHERE 的每个等值条件都会真正参与判定。若只用 {@code getOne(any())} 打桩，
 * 归属条件（USER_ID）与在售条件（PACKAGE_STATUS）从生产代码里删掉测试也照样绿——
 * 那样越权充值和买下架套餐会一路裸奔到线上而无人拦截。</p>
 */
class MiniRechargeCreateTest {

    private RechargeIdentityMapper identityMapper;
    private WsPackageMapper packageMapper;
    private com.jbk.serve.service.user.IWsCardService cardService;
    private IRechargeCreateTx createTx;
    private MiniRechargeServiceImpl service;

    /** 假库里那一行卡；用例改它来摆布"库里有什么"，而不是绕过 WHERE 直接摆布"查出了什么"。 */
    private WsCard dbCard;
    /** 假库里那一行套餐，同上。 */
    private WsPackage dbPackage;

    private static final Long ME = 9L;
    private static final Long OTHER = 10L;
    /** 卡的真实归属人，与会话用户 ME 不同——用于验证越权拒绝。 */
    private static final Long STRANGER = 7L;
    private static final long CARD_ID = 100L;
    private static final long PKG_ID = 3L;
    private static final String UUID = "550e8400-e29b-41d4-a716-446655440000";

    /** MP 3.5.7 的 sqlSegment 形如 {@code (ID = #{ew.paramNameValuePairs.MPGENVAL1} AND USER_ID = #{...})}。 */
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
        service = new MiniRechargeServiceImpl(identityMapper, packageMapper, cardService, createTx, paySource,
                new MiniPayStatusServiceImpl(identityMapper));
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(anyString())).thenReturn(List.of());
        when(identityMapper.selectEventsByOrderNoIncludingDeleted(anyString())).thenReturn(List.of());
        dbCard = card(null);
        dbPackage = pkg(null);
        // 假库：wrapper 里的条件与库里那一行逐列比对，全中才返回，否则返回 null（= 数据库查不到）
        when(cardService.getOne(any(Wrapper.class))).thenAnswer(inv ->
                dbCard != null && matches(inv.getArgument(0), cardRow(dbCard)) ? dbCard : null);
        when(packageMapper.selectOne(any(Wrapper.class))).thenAnswer(inv ->
                dbPackage != null && matches(inv.getArgument(0), packageRow(dbPackage)) ? dbPackage : null);
        when(createTx.create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> {
                    WsOrder o = inv.getArgument(0);
                    o.setId(777L);
                    return o;
                });
    }

    /**
     * 把 wrapper 生成的等值条件解析成「列 → 期望值」，再与假库那一行逐列比对。
     * 出现假库不认识的列时直接炸掉：生产代码新增了 WHERE 条件却没人告诉测试，
     * 比悄悄放行或悄悄拒绝都好，能立刻暴露判定口径漂移。
     */
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
            // 一个等值条件都没有 = 生产查询退化成全表捞，等于没有任何过滤
            throw new IllegalStateException("查询未产生任何等值条件，判定已失效：" + segment);
        }
        return true;
    }

    /** 假库中卡这一行参与判定的列：主键 + 归属人。 */
    private static Map<String, Object> cardRow(WsCard c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", c.getId());
        row.put("USER_ID", c.getUserId());
        return row;
    }

    /** 假库中套餐这一行参与判定的列：主键 + 上下架状态。 */
    private static Map<String, Object> packageRow(WsPackage p) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", p.getId());
        row.put("PACKAGE_STATUS", p.getPackageStatus());
        return row;
    }

    private WsCard card(String expireTime) {
        WsCard c = new WsCard();
        c.setId(CARD_ID);
        c.setUserId(ME);
        c.setCardStatus(1);
        c.setScopeJson("{\"scopeType\":\"specified\",\"stationIds\":[1]}");
        c.setExpireTime(expireTime);
        return c;
    }

    private WsPackage pkg(Integer expireDays) {
        WsPackage p = new WsPackage();
        p.setId(PKG_ID);
        p.setPackageName("季卡 100L");
        p.setPayAmount(9900L);
        p.setWaterMl(100000L);
        p.setBonusAmount(0L);
        p.setUnitPriceSnap("20.00");
        p.setPackageStatus(1);
        p.setExpireDays(expireDays);
        return p;
    }

    private MiniRechargeCreateBo bo(String cardId, String packageId, String requestId) {
        MiniRechargeCreateBo b = new MiniRechargeCreateBo();
        b.setCardId(cardId);
        b.setPackageId(packageId);
        b.setRequestId(requestId);
        return b;
    }

    private MiniRechargeCreateBo okBo() {
        return bo(String.valueOf(CARD_ID), String.valueOf(PKG_ID), UUID);
    }

    // 1) 正常创建：订单停在待支付，金额取套餐售价，不动余额
    @Test
    void createsPendingOrderOnly() {
        MiniRechargeOrderVo vo = service.create(okBo(), ME);
        assertEquals(1, vo.getOrderStatus(), "订单必须停在待支付");
        assertEquals(1, vo.getPayStatus(), "支付单必须停在待支付");
        assertEquals(9900L, vo.getOrderAmountFen());
        assertFalse(vo.getIdempotentHit());
        assertTrue(vo.getOrderNo().startsWith("RC") && vo.getOrderNo().length() == 32);
    }

    // 2) 前端不得决定金额/来源：BO 里根本没有这些字段（编译期保证），此处校验金额来自套餐
    @Test
    void amountComesFromPackageNotClient() {
        dbPackage = pkg(null).setPayAmount(12345L);
        assertEquals(12345L, service.create(okBo(), ME).getOrderAmountFen());
    }

    // 3) 输入规范化：非十进制 ID、非规范 UUID 一律拒绝且不落库
    @Test
    void malformedInputRejectedWithoutWrite() {
        assertThrows(JbkException.class, () -> service.create(bo("0", "3", UUID), ME));
        assertThrows(JbkException.class, () -> service.create(bo("-1", "3", UUID), ME));
        assertThrows(JbkException.class, () -> service.create(bo("1.5", "3", UUID), ME));
        assertThrows(JbkException.class, () -> service.create(bo("100", "3", UUID.toUpperCase()), ME));
        assertThrows(JbkException.class, () -> service.create(bo("100", "3", "not-uuid"), ME));
        verify(createTx, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // 4) 幂等命中：返回原订单、不新建
    @Test
    void idempotentHitReturnsOriginalWithoutCreating() {
        String orderNo = RechargeOrderNo.derive(ME, UUID);
        WsOrder existing = existingOrder(orderNo, "20260722100000", null);
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(orderNo)).thenReturn(List.of(existing));
        when(identityMapper.selectPaymentsByOrderIdIncludingDeleted(existing.getId()))
                .thenReturn(List.of(payment(existing, "20260722103000")));

        MiniRechargeOrderVo vo = service.create(okBo(), ME);

        assertTrue(vo.getIdempotentHit());
        assertEquals(orderNo, vo.getOrderNo());
        verify(createTx, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        // 幂等命中时不得再读套餐/卡（不因套餐改价而重建）
        verify(packageMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    void idempotentHitRejectsImpossibleOrderPaymentMatrixAndPaymentEvidence() {
        String orderNo = RechargeOrderNo.derive(ME, UUID);
        WsOrder existing = existingOrder(orderNo, "20260722100000", null);
        WsPayment payment = payment(existing, "20260722103000");
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(orderNo)).thenReturn(List.of(existing));
        when(identityMapper.selectPaymentsByOrderIdIncludingDeleted(existing.getId())).thenReturn(List.of(payment));

        existing.setOrderStatus(4);
        existing.setFinishTime("20260722100500");
        assertThrows(JbkException.class, () -> service.create(okBo(), ME),
                "order=4/payment=1 不得作为幂等原单返回");

        existing.setOrderStatus(1);
        existing.setFinishTime(null);
        payment.setCurrency("USD");
        assertThrows(JbkException.class, () -> service.create(okBo(), ME),
                "非 CNY 支付单不得作为幂等原单返回");

        payment.setCurrency("CNY");
        payment.setTransactionId("unexpected-success-evidence");
        assertThrows(JbkException.class, () -> service.create(okBo(), ME),
                "待支付单携带成功交易号必须 fail-closed");
        verify(createTx, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void completedIdempotentHitPassesOnlyThroughSharedExactMatrix() {
        String orderNo = RechargeOrderNo.derive(ME, UUID);
        String paidTime = "20260722100500";
        WsOrder existing = existingOrder(orderNo, "20260722100000", null);
        existing.setOrderStatus(4);
        existing.setFinishTime(paidTime);
        WsPayment payment = payment(existing, "20260722103000");
        payment.setPayStatus(2);
        payment.setTransactionId("WX-TX-1");
        payment.setPaySuccessTime(paidTime);
        payment.setCallbackTime(paidTime);
        WsPaymentEvent event = successEvent(existing, payment, paidTime);
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(orderNo)).thenReturn(List.of(existing));
        when(identityMapper.selectPaymentsByOrderIdIncludingDeleted(existing.getId())).thenReturn(List.of(payment));
        when(identityMapper.selectEventsByOrderNoIncludingDeleted(orderNo)).thenReturn(List.of(event));
        when(identityMapper.countLiveRechargeFlows(existing.getId())).thenReturn(1L);

        MiniRechargeOrderVo vo = service.create(okBo(), ME);

        assertTrue(vo.getIdempotentHit());
        assertEquals(4, vo.getOrderStatus());
        assertEquals(2, vo.getPayStatus());
        verify(createTx, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // 5) 哈希碰撞到他人订单：必须拒绝，绝不当幂等成功
    @Test
    void collisionWithOtherUserRejected() {
        String orderNo = RechargeOrderNo.derive(ME, UUID);
        WsOrder foreign = existingOrder(orderNo, "20260722100000", null);
        foreign.setUserId(OTHER);
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(orderNo)).thenReturn(List.of(foreign));

        assertThrows(JbkException.class, () -> service.create(okBo(), ME));
    }

    // 6) 同 requestId 改 cardId/packageId：拒绝且零副作用
    @Test
    void sameRequestIdDifferentTargetRejected() {
        String orderNo = RechargeOrderNo.derive(ME, UUID);
        WsOrder existing = existingOrder(orderNo, "20260722100000", null);
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(orderNo)).thenReturn(List.of(existing));
        when(identityMapper.selectPaymentsByOrderIdIncludingDeleted(anyLong()))
                .thenReturn(List.of(payment(existing, "20260722103000")));

        assertThrows(JbkException.class, () -> service.create(bo("999", "3", UUID), ME));
        assertThrows(JbkException.class, () -> service.create(bo("100", "999", UUID), ME));
        verify(createTx, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // 7) 既有订单缺 payment / 多条 payment：不自动修复，拒绝
    @Test
    void missingOrDuplicatePaymentRejected() {
        String orderNo = RechargeOrderNo.derive(ME, UUID);
        WsOrder existing = existingOrder(orderNo, "20260722100000", null);
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(orderNo)).thenReturn(List.of(existing));

        when(identityMapper.selectPaymentsByOrderIdIncludingDeleted(anyLong())).thenReturn(List.of());
        assertThrows(JbkException.class, () -> service.create(okBo(), ME));

        when(identityMapper.selectPaymentsByOrderIdIncludingDeleted(anyLong()))
                .thenReturn(List.of(payment(existing, "20260722103000"), payment(existing, "20260722103000")));
        assertThrows(JbkException.class, () -> service.create(okBo(), ME));
        verify(createTx, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // 8) 幂等命中时 PAY_EXPIRE_TIME 必须与资格快照重算结果一致（证明未被改写）
    @Test
    void tamperedPayExpireRejected() {
        String orderNo = RechargeOrderNo.derive(ME, UUID);
        WsOrder existing = existingOrder(orderNo, "20260722100000", null);
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(orderNo)).thenReturn(List.of(existing));
        // 快照 capturedTime=10:00 永久卡 → 应为 10:30；此处被改成 11:00
        when(identityMapper.selectPaymentsByOrderIdIncludingDeleted(anyLong()))
                .thenReturn(List.of(payment(existing, "20260722110000")));

        assertThrows(JbkException.class, () -> service.create(okBo(), ME));
    }

    // 9) 并发命中唯一键：重读走同一核验，不把 DuplicateKey 当成功
    @Test
    void duplicateKeyReReadsAndVerifies() {
        String orderNo = RechargeOrderNo.derive(ME, UUID);
        WsOrder concurrent = existingOrder(orderNo, "20260722100000", null);
        // 用 doThrow 而非 when(...)：when 会真的调用 mock，触发 setup 中已注册的 answer（参数为 null）
        Mockito.doThrow(new DuplicateKeyException("uk_order_no"))
                .when(createTx).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(orderNo))
                .thenReturn(List.of())          // 首次查：无
                .thenReturn(List.of(concurrent)); // 冲突后重读：有
        when(identityMapper.selectPaymentsByOrderIdIncludingDeleted(anyLong()))
                .thenReturn(List.of(payment(concurrent, "20260722103000")));

        MiniRechargeOrderVo vo = service.create(okBo(), ME);
        assertTrue(vo.getIdempotentHit(), "并发冲突后必须以幂等命中返回，而不是报成功创建");
    }

    // 10) 并发冲突后仍查不到订单：拒绝，不静默成功
    @Test
    void duplicateKeyWithoutRowRejected() {
        // 用 doThrow 而非 when(...)：when 会真的调用 mock，触发 setup 中已注册的 answer（参数为 null）
        Mockito.doThrow(new DuplicateKeyException("uk_order_no"))
                .when(createTx).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        assertThrows(JbkException.class, () -> service.create(okBo(), ME));
    }

    // 11) 卡的 fail-closed：他人卡/不存在、冻结、注销
    @Test
    void unusableCardRejected() {
        dbCard = null;
        assertThrows(JbkException.class, () -> service.create(okBo(), ME));

        WsCard frozen = card(null);
        frozen.setCardStatus(2);
        dbCard = frozen;
        assertThrows(JbkException.class, () -> service.create(okBo(), ME));

        WsCard closed = card(null);
        closed.setCardStatus(4);
        dbCard = closed;
        assertThrows(JbkException.class, () -> service.create(okBo(), ME));
        verify(createTx, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // 12) 卡范围为空/非法 → 拒绝（空=未配置=默认拒绝，不得因充值变可用）
    @Test
    void blankOrInvalidCardScopeRejected() {
        WsCard noScope = card(null);
        noScope.setScopeJson(null);
        dbCard = noScope;
        assertThrows(JbkException.class, () -> service.create(okBo(), ME));

        WsCard badScope = card(null);
        badScope.setScopeJson("{\"scopeType\":\"weird\"}");
        dbCard = badScope;
        assertThrows(JbkException.class, () -> service.create(okBo(), ME));
    }

    // 13) 套餐范围为空→沿用卡范围；非空→必须语义精确相等，错位拒绝
    @Test
    void packageScopeMustMatchExactly() {
        WsPackage sameScope = pkg(null);
        sameScope.setScopeJson("{\"scopeType\":\"specified\",\"stationIds\":[1]}");
        dbPackage = sameScope;
        assertEquals(1, service.create(okBo(), ME).getOrderStatus());

        WsPackage widerScope = pkg(null);
        widerScope.setScopeJson("{\"scopeType\":\"specified\",\"stationIds\":[1,2]}");
        dbPackage = widerScope;
        assertThrows(JbkException.class, () -> service.create(okBo(), ME),
                "套餐范围不得悄悄扩大卡范围");

        WsPackage allScope = pkg(null);
        allScope.setScopeJson("{\"scopeType\":\"all\"}");
        dbPackage = allScope;
        assertThrows(JbkException.class, () -> service.create(okBo(), ME));
    }

    // 14) 有限/永久交叉一律拒绝
    @Test
    void expiryKindCrossRejected() {
        // 永久卡买有限套餐
        dbPackage = pkg(365);
        assertThrows(JbkException.class, () -> service.create(okBo(), ME));
        // 有限卡买永久套餐
        dbCard = card("20270101000000");
        dbPackage = pkg(null);
        assertThrows(JbkException.class, () -> service.create(okBo(), ME));
    }

    // 15) 有限卡剩余不足 1 分钟：拒绝创建，不收无法完成的款
    @Test
    void finiteCardAboutToExpireRejected() {
        dbCard = card("19990101000000");
        dbPackage = pkg(365);
        assertThrows(JbkException.class, () -> service.create(okBo(), ME));
        verify(createTx, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void blankOrMalformedExpiryRejectedBeforeOrderAndPaymentCreation() {
        dbCard = card("");
        dbPackage = pkg(null);
        assertThrows(JbkException.class, () -> service.create(okBo(), ME),
                "空串不得被当作永久卡收款");

        dbCard = card("20260230000000");
        dbPackage = pkg(365);
        assertThrows(JbkException.class, () -> service.create(okBo(), ME),
                "非真实日期即使长度为14也必须拒绝");

        dbCard = card("20270101000000");
        assertEquals(1, service.create(okBo(), ME).getOrderStatus(),
                "合法14位有限期仍应正常创单");
    }

    // 16) 下架/不存在套餐拒绝；非法金额拒绝
    @Test
    void invalidPackageRejected() {
        dbPackage = null;
        assertThrows(JbkException.class, () -> service.create(okBo(), ME));

        dbPackage = pkg(null).setPayAmount(0L);
        assertThrows(JbkException.class, () -> service.create(okBo(), ME));

        dbPackage = pkg(null).setPayAmount(1_000_001L);
        assertThrows(JbkException.class, () -> service.create(okBo(), ME));
    }

    // 17) 逻辑删除的既有订单不复活
    @Test
    void deletedExistingOrderNotResurrected() {
        String orderNo = RechargeOrderNo.derive(ME, UUID);
        WsOrder deleted = existingOrder(orderNo, "20260722100000", null);
        deleted.setDataStatus(1);
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(orderNo)).thenReturn(List.of(deleted));

        assertThrows(JbkException.class, () -> service.create(okBo(), ME));
        verify(createTx, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // 18) 越权充值：卡真实归属他人时，归属条件必须在 WHERE 里就把它挡在库外。
    //     这条守卫没了，任何人只要猜到别人的 cardId 就能给别人的卡下单，甚至用对方的卡状态/范围做探测。
    @Test
    void foreignCardRejectedByWhereClause() {
        WsCard foreign = card(null);
        foreign.setUserId(STRANGER);
        dbCard = foreign;

        JbkException ex = assertThrows(JbkException.class, () -> service.create(okBo(), ME));
        assertTrue(ex.getMessage().contains("不存在或无权访问"), "实际=" + ex.getMessage());
        verify(createTx, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // 19) "卡不存在"与"他人的卡"必须同一句话。措辞一旦分叉，接口就变成枚举他人 cardId 是否存在的探测信道。
    @Test
    void foreignAndMissingCardShareOneMessage() {
        dbCard = null;
        String missing = assertThrows(JbkException.class, () -> service.create(okBo(), ME)).getMessage();

        WsCard foreign = card(null);
        foreign.setUserId(STRANGER);
        dbCard = foreign;
        String forbidden = assertThrows(JbkException.class, () -> service.create(okBo(), ME)).getMessage();

        assertEquals(missing, forbidden, "两种情形必须不可区分");
    }

    // 20) 下架套餐：在售条件必须在 WHERE 里。守卫没了，运营刚下架的套餐仍能被下单收款，
    //     且订单快照会固化一个已停售的价格与水量，后续入账口径直接错。
    @Test
    void offSalePackageRejectedByWhereClause() {
        WsPackage offSale = pkg(null);
        offSale.setPackageStatus(0);
        dbPackage = offSale;

        JbkException ex = assertThrows(JbkException.class, () -> service.create(okBo(), ME));
        assertTrue(ex.getMessage().contains("套餐不存在或已下架"), "实际=" + ex.getMessage());
        verify(createTx, never()).create(any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // 21) 本人卡但状态非 1：归属已确认，可以给出真实原因，且这句话必须与归属拒绝那句不同——
    //     否则用户会被"无权访问"误导去申诉，而真正该做的是解冻卡。
    @Test
    void ownCardWithBadStatusGetsDistinctMessage() {
        WsCard frozen = card(null);
        frozen.setCardStatus(2);
        dbCard = frozen;
        String statusMsg = assertThrows(JbkException.class, () -> service.create(okBo(), ME)).getMessage();
        assertTrue(statusMsg.contains("水卡当前状态不可充值"), "实际=" + statusMsg);

        dbCard = null;
        String ownershipMsg = assertThrows(JbkException.class, () -> service.create(okBo(), ME)).getMessage();
        assertNotEquals(ownershipMsg, statusMsg, "状态原因不得与归属拒绝混为一谈");
    }

    // ---------------------------------------------------------------------

    private WsOrder existingOrder(String orderNo, String createTime, String expireAtCreate) {
        WsCard c = card(expireAtCreate);
        WsPackage p = pkg(expireAtCreate == null ? null : 365);
        String snap = RechargeSnapshot.build(UUID, p, c,
                com.jbk.serve.service.mini.card.WaterCardScope.normalize(c.getScopeJson(), "水卡"),
                null, createTime);
        WsOrder o = new WsOrder();
        o.setId(777L);
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

    private WsPaymentEvent successEvent(WsOrder order, WsPayment payment, String paidTime) {
        WsPaymentEvent event = new WsPaymentEvent();
        event.setId(999L);
        event.setOrderId(order.getId());
        event.setOrderNo(order.getOrderNo());
        event.setPaymentId(payment.getId());
        event.setPaySource(payment.getPaySource());
        event.setTradeState("SUCCESS");
        event.setTransactionId(payment.getTransactionId());
        event.setPayAmount(payment.getPayAmount());
        event.setCurrency("CNY");
        event.setPaySuccessTime(paidTime);
        event.setProcessingStatus(3);
        event.setDataStatus(0);
        return event;
    }
}

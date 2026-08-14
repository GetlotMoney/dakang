package com.jbk.serve.service.mini.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.product.WsPackageMapper;
import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.service.mini.recharge.IRechargeCreateTx;
import com.jbk.serve.service.mini.recharge.IRechargePaySourceAdapter;
import com.jbk.serve.service.mini.recharge.RechargeRefundEvidenceVerifier;
import com.jbk.serve.service.mini.recharge.RechargeOrderNo;
import com.jbk.tool.data.mini.bo.MiniRechargeCreateBo;
import com.jbk.tool.data.mini.vo.MiniRechargeOrderVo;
import com.jbk.tool.data.product.po.WsPackage;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2-ORDER 创建的<b>并发</b>正确性（契约 §4.2）。
 *
 * <p>用一张 {@link ConcurrentHashMap} 假表模拟 ws_order 上的 uk_order_no：
 * 抢到键的一方原子地"提交"（分配自增 ID + 同事务写 payment），后到者拿到
 * {@link DuplicateKeyException}，由编排层重读后走同一套幂等核验。
 * 这条链路一旦断掉，同一次充值会被建成两单，用户可能被收两次钱。</p>
 *
 * <p><b>本用例证明的边界</b>：只证明编排层在并发下的行为正确，
 * <b>不</b>证明真实 MySQL 的唯一键真的挡得住并发插入——那需要真库集成测试。</p>
 */
class MiniRechargeConcurrencyTest {

    private static final Long ME = 9L;
    private static final long CARD_ID = 100L;
    private static final long PKG_ID = 3L;
    private static final String UUID_FIXED = "550e8400-e29b-41d4-a716-446655440000";
    /** 并发线程数：足够大到能真的撞上唯一键。 */
    private static final int THREADS = 20;

    private RechargeIdentityMapper identityMapper;
    private WsPackageMapper packageMapper;
    private com.jbk.serve.service.user.IWsCardService cardService;
    private IRechargeCreateTx createTx;
    private MiniRechargeServiceImpl service;

    /** 假 ws_order 表：key = ORDER_NO，等价于唯一键 uk_order_no。 */
    private final Map<String, WsOrder> orderTable = new ConcurrentHashMap<>();
    /** 假 ws_payment 表：key = ORDER_ID。 */
    private final Map<Long, WsPayment> paymentTable = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong();

    private ExecutorService pool;

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
        service = new MiniRechargeServiceImpl(phoneGateAllowing(), new com.jbk.serve.service.settlement.IInviteService() {
                    public String myInviteCode(Long userId) { return "IVTEST0000"; }
                    public void bindReferrer(Long userId, String inviteCode) { }
                    public Long referrerSnapshotOf(Long userId) { return null; }
                }, identityMapper, packageMapper, cardService, createTx, paySource,
                new MiniPayStatusServiceImpl(identityMapper,
                        Mockito.mock(RechargeRefundEvidenceVerifier.class)));
        when(identityMapper.selectEventsByOrderNoIncludingDeleted(anyString())).thenReturn(List.of());

        when(cardService.getOne(any(Wrapper.class))).thenReturn(card());
        when(packageMapper.selectOne(any(Wrapper.class))).thenReturn(pkg());

        // 假表读：跨全部 DATA_STATUS 读，等价于生产手写 SQL 的语义
        when(identityMapper.selectOrdersByOrderNoIncludingDeleted(anyString())).thenAnswer(inv -> {
            WsOrder row = orderTable.get(inv.<String>getArgument(0));
            return row == null ? List.of() : List.of(row);
        });
        when(identityMapper.selectPaymentsByOrderIdIncludingDeleted(anyLong())).thenAnswer(inv -> {
            WsPayment row = paymentTable.get(inv.<Long>getArgument(0));
            return row == null ? List.of() : List.of(row);
        });

        // 假事务：compute 对同一 ORDER_NO 是原子的，模拟"插入成功即整单提交可见"。
        // 用 compute 而不是先 putIfAbsent 再补 ID，是因为后者会让别的线程读到一条还没有 ID
        // 和 payment 的半成品——真实数据库在事务提交前根本不会让这种中间态可见。
        when(createTx.create(any(), anyString(), anyInt())).thenAnswer(inv -> {
            WsOrder order = inv.getArgument(0);
            String payExpireTime = inv.getArgument(1);
            int source = inv.getArgument(2);
            WsOrder[] loser = new WsOrder[1];
            orderTable.compute(order.getOrderNo(), (k, prev) -> {
                if (prev != null) {
                    loser[0] = prev;
                    return prev;
                }
                order.setId(idSeq.incrementAndGet());
                paymentTable.put(order.getId(), payment(order, payExpireTime, source));
                return order;
            });
            if (loser[0] != null) {
                throw new DuplicateKeyException("uk_order_no");
            }
            return order;
        });

        pool = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    // 1) 同一 userId + 同一 requestId 的 20 路并发：只能落 1 单 1 支付单，19 路走幂等命中。
    //    这条守卫没了，同一次充值会被并发建成多单，用户被重复收款、对账也对不上。
    @Test
    @Timeout(30)
    void sameRequestIdUnderConcurrencyCreatesExactlyOneOrder() throws Exception {
        List<MiniRechargeOrderVo> results = runConcurrently(i -> bo(UUID_FIXED));

        assertEquals(THREADS, results.size(), "20 路调用必须全部正常返回，不允许异常逃逸");

        Set<String> orderNos = results.stream().map(MiniRechargeOrderVo::getOrderNo).collect(Collectors.toSet());
        assertEquals(1, orderNos.size(), "同 requestId 派生的订单号必须完全相同");
        assertEquals(RechargeOrderNo.derive(ME, UUID_FIXED), orderNos.iterator().next());

        assertEquals(1, orderTable.size(), "ws_order 假表必须恰好 1 条：多出来的就是重复建单");
        assertEquals(1, paymentTable.size(), "ws_payment 假表必须恰好 1 条：多出来的就是重复收款");

        long created = results.stream().filter(v -> Boolean.FALSE.equals(v.getIdempotentHit())).count();
        long hits = results.stream().filter(v -> Boolean.TRUE.equals(v.getIdempotentHit())).count();
        assertEquals(1, created, "只允许一路报告为新建");
        assertEquals(THREADS - 1L, hits, "其余各路必须诚实地报告为幂等命中，不得谎称新建");

        // 幂等命中方回传的必须是同一张订单的真实字段，不是各自本地拼的
        WsOrder stored = orderTable.values().iterator().next();
        for (MiniRechargeOrderVo vo : results) {
            assertEquals(stored.getOrderAmount(), vo.getOrderAmountFen());
            assertEquals(stored.getCreateTime(), vo.getCreateTime());
        }
    }

    // 2) 同一 userId + 20 个不同 requestId：必须落 20 单。
    //    这条守卫没了，说明幂等键粗到把用户的多次独立充值合并掉——用户付了钱却只得到一单。
    @Test
    @Timeout(30)
    void differentRequestIdsAreNotMergedTogether() throws Exception {
        List<String> requestIds = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            requestIds.add(UUID.randomUUID().toString());
        }

        List<MiniRechargeOrderVo> results = runConcurrently(i -> bo(requestIds.get(i)));

        assertEquals(THREADS, results.size());
        assertEquals(THREADS, results.stream().map(MiniRechargeOrderVo::getOrderNo).distinct().count(),
                "不同 requestId 必须派生出互不相同的订单号");
        assertEquals(THREADS, orderTable.size(), "20 次独立充值必须落 20 单");
        assertEquals(THREADS, paymentTable.size());
        assertTrue(results.stream().noneMatch(MiniRechargeOrderVo::getIdempotentHit),
                "彼此独立的请求不得被误判为幂等命中");
    }

    // 3) 撞唯一键但重读也查不到（诡异的半可见状态）：必须显式失败。
    //    这条守卫没了，DuplicateKeyException 会被当成"创建成功"，返回一个数据库里根本不存在的订单号，
    //    用户照着它去付款，钱进来却没有单可以入账。
    @Test
    void duplicateKeyWithoutVisibleRowNeverReportsSuccess() {
        // 用 doThrow 重新打桩：when(mock.m(...)) 会真的调用 mock，触发上面注册的 Answer
        Mockito.doThrow(new DuplicateKeyException("uk_order_no"))
                .when(createTx).create(any(), anyString(), anyInt());

        JbkException ex = assertThrows(JbkException.class, () -> service.create(bo(UUID_FIXED), ME));
        assertEquals("充值订单创建冲突，请重试", ex.getMessage());
        assertTrue(orderTable.isEmpty(), "失败路径不得留下任何订单");
        assertTrue(paymentTable.isEmpty(), "失败路径不得留下任何支付单");
    }

    // 4) 并发创单不得触碰资金：只落待支付单，任何余额、水量或流水写入都属于越权入账。
    //    这条守卫没了，用户还没付钱就被充上了值。
    @Test
    @Timeout(30)
    void concurrentCreateNeverTouchesMoney() throws Exception {
        runConcurrently(i -> bo(UUID_FIXED));

        // 卡与套餐只允许被读，绝不允许被写（余额、水量都在卡上）
        verify(cardService, never()).updateById(any(WsCard.class));
        verify(cardService, never()).update(any(WsCard.class), any(Wrapper.class));
        verify(cardService, never()).saveOrUpdate(any(WsCard.class));
        verify(cardService, never()).save(any(WsCard.class));
        // BaseMapper 的 updateById 有 (T) 与 (Collection<T>) 两个重载，any() 会歧义，必须写死类型
        verify(packageMapper, never()).updateById(any(WsPackage.class));

        // 并发败方允许只读流水数量来复用精确状态矩阵，但创单路径没有任何资金写接口。
        verify(identityMapper, never()).insertPayment(any());

        // 落库结果本身必须停在待支付：没有成功时间、没有交易号
        WsOrder stored = orderTable.values().iterator().next();
        assertEquals(1, stored.getOrderStatus(), "订单必须停在待支付");
        assertNull(stored.getFinishTime(), "待支付创单不得写完成时间");
        WsPayment storedPayment = paymentTable.values().iterator().next();
        assertEquals(1, storedPayment.getPayStatus(), "支付单必须停在待支付");
        assertNull(storedPayment.getPaySuccessTime());
        assertNull(storedPayment.getTransactionId());
    }

    // ---------------------------------------------------------------------

    /** 让 THREADS 个线程在栅栏处真正同时起跑，而不是顺序 for 循环冒充并发。 */
    private List<MiniRechargeOrderVo> runConcurrently(java.util.function.IntFunction<MiniRechargeCreateBo> boFactory)
            throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(THREADS);
        CountDownLatch done = new CountDownLatch(THREADS);
        List<MiniRechargeOrderVo> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> escaped = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < THREADS; i++) {
            final int idx = i;
            pool.execute(() -> {
                try {
                    startLine.await(10, TimeUnit.SECONDS);
                    results.add(service.create(boFactory.apply(idx), ME));
                } catch (Throwable t) {
                    escaped.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(20, TimeUnit.SECONDS), "并发调用超时未完成");
        assertTrue(escaped.isEmpty(), () -> "并发下不允许任何异常逃逸，实际：" + escaped);
        return results;
    }

    private MiniRechargeCreateBo bo(String requestId) {
        MiniRechargeCreateBo b = new MiniRechargeCreateBo();
        b.setCardId(String.valueOf(CARD_ID));
        b.setPackageId(String.valueOf(PKG_ID));
        b.setRequestId(requestId);
        return b;
    }

    private WsCard card() {
        WsCard c = new WsCard();
        c.setId(CARD_ID);
        c.setUserId(ME);
        c.setCardStatus(1);
        c.setScopeJson("{\"scopeType\":\"specified\",\"stationIds\":[1]}");
        c.setExpireTime(null);
        return c;
    }

    private WsPackage pkg() {
        WsPackage p = new WsPackage();
        p.setId(PKG_ID);
        p.setPackageName("季卡 100L");
        p.setPayAmount(9900L);
        p.setWaterMl(100000L);
        p.setBonusAmount(0L);
        p.setUnitPriceSnap("20.00");
        p.setPackageStatus(1);
        p.setExpireDays(null);
        return p;
    }

    /** 与 RechargeCreateTxImpl 同事务写出的支付单保持一致：金额取订单、来源取适配器、状态恒为待支付。 */
    private WsPayment payment(WsOrder order, String payExpireTime, int paySource) {
        WsPayment p = new WsPayment();
        p.setId(order.getId());
        p.setOrderId(order.getId());
        p.setOrderNo(order.getOrderNo());
        p.setPayAmount(order.getOrderAmount());
        p.setPayStatus(1);
        p.setPaySource(paySource);
        p.setCurrency("CNY");
        p.setPayExpireTime(payExpireTime);
        p.setDataStatus(0);
        p.setCreateTime(order.getCreateTime());
        p.setUpdateTime(order.getCreateTime());
        assertNotNull(payExpireTime);
        return p;
    }

    /**
     * 放行版绑号闸：本类用例测的是充值链本身，账号一律视为已绑号。
     * 闸的判据由 MiniPhoneGateTest 覆盖，锚点是否挂齐由 PhoneGateAnchorContractTest 覆盖。
     */
    private static com.jbk.serve.service.mini.auth.MiniPhoneGate phoneGateAllowing() {
        com.jbk.serve.mapper.user.WsUserIdentityMapper m =
                Mockito.mock(com.jbk.serve.mapper.user.WsUserIdentityMapper.class);
        Mockito.when(m.selectPhoneByIdIncludingDeleted(Mockito.anyLong()))
                .thenReturn("13900000000");
        return new com.jbk.serve.service.mini.auth.MiniPhoneGate(m);
    }

}

package com.jbk.serve.service.mini.recharge.impl;

import com.jbk.serve.mapper.trade.RechargeIdentityMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsPayment;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2-ORDER 创建事务（契约 §4.2 / §5.2）：order + payment 同事务写入。
 *
 * <p>本类只盯"写"这一步：写入顺序、payment 每个字段的决定权归属、失败路径不留半成品。
 * 幂等判定属于编排层（见 {@code MiniRechargeCreateTest}），这里不重复。</p>
 */
class RechargeCreateTxImplTest {

    private static final Long USER_ID = 9L;
    private static final Long ORDER_ID = 777L;
    private static final String ORDER_NO = "RC260722000000000000000000000001";
    private static final String CREATE_TIME = "20260722100000";
    private static final String PAY_EXPIRE = "20260722103000";
    /** 故意取一个非 1 的来源值：若有人把 paySource 写死成 1（微信），本值会立刻暴露。 */
    private static final int PAY_SOURCE_SIM = 9;

    private WsOrderMapper wsOrderMapper;
    private RechargeIdentityMapper identityMapper;
    private RechargeCreateTxImpl tx;

    @BeforeEach
    void setup() {
        wsOrderMapper = Mockito.mock(WsOrderMapper.class);
        identityMapper = Mockito.mock(RechargeIdentityMapper.class);
        tx = new RechargeCreateTxImpl(wsOrderMapper, identityMapper);
        // 模拟自增主键回填：真实 DB 由 useGeneratedKeys 在 insert 之后才写入 ID
        when(wsOrderMapper.insert(any(WsOrder.class))).thenAnswer(inv -> {
            inv.<WsOrder>getArgument(0).setId(ORDER_ID);
            return 1;
        });
        when(identityMapper.insertPayment(any(WsPayment.class))).thenReturn(1);
    }

    private WsOrder order() {
        WsOrder o = new WsOrder();
        o.setOrderNo(ORDER_NO);
        o.setOrderType(2);
        o.setUserId(USER_ID);
        o.setCardId(100L);
        o.setPackageId(3L);
        o.setOrderAmount(9900L);
        o.setPayWay(1);
        o.setOrderStatus(1);
        o.setDataStatus(0);
        o.setCreateBy(USER_ID);
        o.setCreateTime(CREATE_TIME);
        o.setUpdateBy(USER_ID);
        o.setUpdateTime(CREATE_TIME);
        return o;
    }

    private WsPayment captureInsertedPayment(WsOrder o) {
        tx.create(o, PAY_EXPIRE, PAY_SOURCE_SIM);
        ArgumentCaptor<WsPayment> captor = ArgumentCaptor.forClass(WsPayment.class);
        verify(identityMapper).insertPayment(captor.capture());
        return captor.getValue();
    }

    // 1) 顺序：先落订单拿到自增 ID，再用该 ID 建支付单。
    //    这条守卫没了，payment.ORDER_ID 会是 null——支付回调按 ORDER_ID 找不到订单，钱收了却无法入账。
    @Test
    void insertsOrderBeforePayment() {
        WsOrder o = order();
        assertSame(o, tx.create(o, PAY_EXPIRE, PAY_SOURCE_SIM), "必须返回已落库的同一订单对象（含回填的 ID）");

        InOrder inOrder = Mockito.inOrder(wsOrderMapper, identityMapper);
        inOrder.verify(wsOrderMapper).insert(any(WsOrder.class));
        inOrder.verify(identityMapper).insertPayment(any(WsPayment.class));
        inOrder.verifyNoMoreInteractions();
    }

    // 2) payment 的身份字段必须来自已落库的订单本体。
    //    这条守卫没了，支付单会挂到错的订单号/订单 ID 上，对账时两张表永远对不上。
    @Test
    void paymentIdentityCopiedFromPersistedOrder() {
        WsOrder o = order();
        WsPayment p = captureInsertedPayment(o);
        assertEquals(ORDER_ID, p.getOrderId(), "必须用 insert 之后回填的自增 ID");
        assertEquals(o.getId(), p.getOrderId());
        assertEquals(ORDER_NO, p.getOrderNo());
        assertEquals(o.getOrderNo(), p.getOrderNo());
    }

    // 3) 金额只认订单金额（订单金额由服务端按套餐售价算出）。
    //    这条守卫没了就是"前端说多少钱就收多少钱"，一分钱能买走一张年卡。
    @Test
    void payAmountAlwaysEqualsOrderAmount() {
        WsOrder o = order();
        o.setOrderAmount(12345L);
        assertEquals(12345L, captureInsertedPayment(o).getPayAmount(),
                "支付金额必须严格等于订单金额，绝不取任何外部输入");
    }

    // 4) 支付状态与币种由服务端钉死。
    //    payStatus 若不是 1（待支付），系统会产生缺少支付事实和资金流水的“已支付”订单。
    @Test
    void payStatusPendingAndCurrencyFixed() {
        WsPayment p = captureInsertedPayment(order());
        assertEquals(1, p.getPayStatus(), "创建出来的支付单必须停在待支付");
        assertEquals("CNY", p.getCurrency());
    }

    // 5) paySource 必须透传调用方（受信任的服务端适配器）给的值。
    //    这条守卫没了（比如硬编码成 1），Pay-Sim 造的单会被记成真实微信支付，演示与生产的账混成一本。
    @Test
    void paySourcePassedThroughNotHardcoded() {
        assertEquals(PAY_SOURCE_SIM, captureInsertedPayment(order()).getPaySource(),
                "paySource 必须原样透传，不得写死成 1（微信）");
    }

    // 6) 付款截止时间取调用方冻结的值。
    //    这条守卫没了，截止时间会被创建之外的逻辑重算，过期单可以被"续命"继续付款。
    @Test
    void payExpireTimeTakenFromArgument() {
        assertEquals(PAY_EXPIRE, captureInsertedPayment(order()).getPayExpireTime());
    }

    // 7) 审计字段与逻辑删除位跟随订单。
    //    dataStatus 若不是 0，支付单一出生就是"已删除"，pay-status 会把它判成数据污染，用户永远付不了款。
    @Test
    void auditFieldsFollowOrder() {
        WsOrder o = order();
        WsPayment p = captureInsertedPayment(o);
        assertEquals(0, p.getDataStatus(), "新建支付单必须是未删除状态");
        assertEquals(USER_ID, p.getCreateBy());
        assertEquals(USER_ID, p.getUpdateBy());
        assertEquals(o.getUserId(), p.getCreateBy());
        assertEquals(CREATE_TIME, p.getCreateTime(), "创建时间必须与订单同一时刻，避免两表时间线错位");
        assertEquals(CREATE_TIME, p.getUpdateTime());
        assertEquals(o.getCreateTime(), p.getUpdateTime());
    }

    // 8) 订单插入影响行数不为 1：直接失败，绝不继续建支付单。
    //    这条守卫没了会产生"没有订单的支付单"（ORDER_ID 为 null 的孤儿），回调时无从入账。
    @Test
    void orderInsertNotAffectedOneAborts() {
        // 影响行数为 0，但 ID 仍被回填——只有真正校验行数才拦得住
        Mockito.doAnswer(inv -> {
            inv.<WsOrder>getArgument(0).setId(ORDER_ID);
            return 0;
        }).when(wsOrderMapper).insert(any(WsOrder.class));
        assertThrows(JbkException.class, () -> tx.create(order(), PAY_EXPIRE, PAY_SOURCE_SIM));
        verify(identityMapper, never()).insertPayment(any(WsPayment.class));
    }

    // 9) 插入报成功但自增 ID 没回填：同样必须失败。
    //    这条守卫没了，payment.ORDER_ID 会写成 null，订单与支付单从此失联。
    @Test
    void missingGeneratedIdAborts() {
        // 只返回 1、不回填 ID，模拟 useGeneratedKeys 未生效
        Mockito.doReturn(1).when(wsOrderMapper).insert(any(WsOrder.class));
        assertThrows(JbkException.class, () -> tx.create(order(), PAY_EXPIRE, PAY_SOURCE_SIM));
        verify(identityMapper, never()).insertPayment(any(WsPayment.class));
    }

    // 10) 支付单插入影响行数不为 1：必须抛异常让事务整体回滚。
    //     这条守卫没了会留下"有订单没支付单"的半成品，用户点付款时无单可付，订单永久卡死。
    @Test
    void paymentInsertNotAffectedOneAborts() {
        Mockito.doReturn(0).when(identityMapper).insertPayment(any(WsPayment.class));
        assertThrows(JbkException.class, () -> tx.create(order(), PAY_EXPIRE, PAY_SOURCE_SIM));
    }

    // 11) 唯一键冲突必须原样向上抛。
    //     编排层要靠 DuplicateKeyException 触发"按 ORDER_NO 重读 + 重新核验"的幂等路径；
    //     这里若被吞掉或转成成功返回，并发下同一 requestId 就会重复建单/重复收款。
    @Test
    void duplicateKeyPropagatesUnchanged() {
        DuplicateKeyException boom = new DuplicateKeyException("uk_payment_order_no");
        Mockito.doThrow(boom).when(identityMapper).insertPayment(any(WsPayment.class));
        DuplicateKeyException thrown = assertThrows(DuplicateKeyException.class,
                () -> tx.create(order(), PAY_EXPIRE, PAY_SOURCE_SIM));
        assertSame(boom, thrown, "唯一键冲突必须原样传播，不得被包装成 JbkException 或吞成成功");
    }

    // 12) 订单插入阶段的唯一键冲突同样原样向上抛（且不建支付单）。
    @Test
    void duplicateKeyOnOrderInsertPropagates() {
        DuplicateKeyException boom = new DuplicateKeyException("uk_order_no");
        Mockito.doThrow(boom).when(wsOrderMapper).insert(any(WsOrder.class));
        assertSame(boom, assertThrows(DuplicateKeyException.class,
                () -> tx.create(order(), PAY_EXPIRE, PAY_SOURCE_SIM)));
        verify(identityMapper, never()).insertPayment(any(WsPayment.class));
    }

    // 13) 注解级断言：钉死 @Transactional(rollbackFor = Exception.class) 不被误删或收窄。
    //     注意：这是"注解存在性"断言，纯单测无法验证真实回滚行为；
    //     它的价值是——rollbackFor 一旦丢失，JbkException（RuntimeException 之外的受检异常场景）
    //     不再触发回滚，就会留下"有订单没支付单"的脏数据。真实回滚需集成测试覆盖。
    @Test
    void createIsTransactionalWithRollbackForException() throws NoSuchMethodException {
        Method create = RechargeCreateTxImpl.class.getMethod("create", WsOrder.class, String.class, int.class);
        Transactional annotation = create.getAnnotation(Transactional.class);
        assertNotNull(annotation, "create 必须带 @Transactional，否则 order 与 payment 不在同一事务");
        assertArrayEquals(new Class<?>[]{Exception.class}, annotation.rollbackFor(),
                "rollbackFor 必须含 Exception.class");
        assertTrue(Arrays.asList(annotation.rollbackFor()).contains(Exception.class));
    }
}

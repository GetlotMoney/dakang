package com.jbk.serve.service.delivery;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jbk.serve.mapper.trade.WsWalletFlowMapper;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.data.trade.po.WsWalletFlow;
import com.jbk.tool.exception.JbkException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配送扣款流水定位单测（E2E-04 包A，返还基准的单一出处）。
 *
 * <p>流水不用 {@code thenReturn} 直接喂结果，而是走 {@link #matches} 假库：生产代码写进
 * WHERE 的每个等值条件都会真正参与判定，且假库那一行的幂等键是<b>字面量</b>
 * {@code "DELIVERY:DO-9001"} 而非调用 {@code bizKey()} 拼出来的。
 * 这样一来，任何人改动前缀都会让回读查不到而变红——若两侧都调 {@code bizKey()}，
 * 前缀漂移会同步发生、测试照样绿，写入与回读之间的约定就失去保护。</p>
 */
class DeliveryConsumeFlowTest {

    /** MP 3.5.7 的 sqlSegment 形如 {@code (BIZ_IDEMPOTENCY_KEY = #{ew.paramNameValuePairs.MPGENVAL1})}。 */
    private static final Pattern EQ_COND =
            Pattern.compile("([A-Z_0-9]+)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)}");

    private static final long ORDER_ID = 9001L;
    private static final long CARD_ID = 3301L;
    private static final String ORDER_NO = "DO-9001";
    /** 写入侧约定的幂等键字面量；回读若拼不出同一个串就必须查不到。 */
    private static final String EXPECTED_BIZ_KEY = "DELIVERY:DO-9001";

    private WsWalletFlowMapper mapper;
    /** 假库里那一行流水；用例改它来摆布"库里有什么"，而不是摆布"查出了什么"。 */
    private WsWalletFlow dbFlow;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(a, WsWalletFlow.class);
    }

    @BeforeEach
    void setup() {
        mapper = Mockito.mock(WsWalletFlowMapper.class);
        dbFlow = flow(ORDER_ID, CARD_ID);
        Mockito.when(mapper.selectOne(Mockito.any(Wrapper.class))).thenAnswer(inv ->
                dbFlow != null && matches(inv.getArgument(0), flowRow()) ? dbFlow : null);
    }

    // ==================== bizKey ====================

    @Test
    void bizKeyIsPrefixedOrderNo() {
        assertEquals(EXPECTED_BIZ_KEY, DeliveryConsumeFlow.bizKey(ORDER_NO));
        assertEquals("DELIVERY:DO-1", DeliveryConsumeFlow.bizKey("DO-1"));
    }

    @Test
    void bizKeyRejectsBlankOrderNo() {
        for (String bad : new String[]{null, "", "   ", "\t"}) {
            assertThrows(JbkException.class, () -> DeliveryConsumeFlow.bizKey(bad),
                    "订单号 [" + bad + "] 必须拒绝");
        }
    }

    // ==================== require ====================

    @Test
    void requireReadsFlowByBizKey() {
        assertSame(dbFlow, DeliveryConsumeFlow.require(mapper, order(ORDER_NO)));
    }

    /** 键对不上就是查不到：流水缺失时必须 fail-closed，绝不退化成"取第一条"。 */
    @Test
    void missingFlowIsRejected() {
        // ① 库里根本没有这一行
        dbFlow = null;
        JbkException empty = assertThrows(JbkException.class,
                () -> DeliveryConsumeFlow.require(mapper, order(ORDER_NO)));
        assertTrue(empty.getMessage().contains("缺失"), empty.getMessage());

        // ② 库里有流水，但订单号不同 → 幂等键不同 → 查不到
        dbFlow = flow(ORDER_ID, CARD_ID);
        assertThrows(JbkException.class, () -> DeliveryConsumeFlow.require(mapper, order("DO-9002")));
    }

    /**
     * 键对上而归属对不上：说明流水被改写或订单号被复用，按任一侧退款都可能把钱退到别人卡上。
     */
    @Test
    void misattributedFlowIsRejected() {
        dbFlow = flow(8888L, CARD_ID);
        JbkException wrongOrder = assertThrows(JbkException.class,
                () -> DeliveryConsumeFlow.require(mapper, order(ORDER_NO)));
        assertTrue(wrongOrder.getMessage().contains("归属错位"), wrongOrder.getMessage());

        dbFlow = flow(ORDER_ID, 7777L);
        JbkException wrongCard = assertThrows(JbkException.class,
                () -> DeliveryConsumeFlow.require(mapper, order(ORDER_NO)));
        assertTrue(wrongCard.getMessage().contains("归属错位"), wrongCard.getMessage());

        // 归属列为空同样不算对上：null 不得被当成"没写就算匹配"
        dbFlow = flow(null, CARD_ID);
        assertThrows(JbkException.class, () -> DeliveryConsumeFlow.require(mapper, order(ORDER_NO)));
        dbFlow = flow(ORDER_ID, null);
        assertThrows(JbkException.class, () -> DeliveryConsumeFlow.require(mapper, order(ORDER_NO)));
    }

    @Test
    void requireRejectsMissingArguments() {
        assertThrows(JbkException.class, () -> DeliveryConsumeFlow.require(null, order(ORDER_NO)));
        assertThrows(JbkException.class, () -> DeliveryConsumeFlow.require(mapper, null));
        // 订单存在但没有订单号：拼不出幂等键，同样拒绝
        assertThrows(JbkException.class, () -> DeliveryConsumeFlow.require(mapper, order(null)));
    }

    // ==================== requireChange ====================

    @Test
    void requireChangeAcceptsOnlyNonPositiveValues() {
        assertEquals(-7500L, DeliveryConsumeFlow.requireChange(-7500L, "原扣款流水金额"));
        assertEquals(0L, DeliveryConsumeFlow.requireChange(0L, "原扣款流水水量"),
                "该维度没扣过是合法的，0 必须通过");
        assertEquals(Long.MIN_VALUE, DeliveryConsumeFlow.requireChange(Long.MIN_VALUE, "原扣款流水金额"));
    }

    /** 正值意味着取到的是一条入账流水；拿它当返还基准会把上限算成负数或反向放大。 */
    @Test
    void positiveChangeIsRejected() {
        JbkException e = assertThrows(JbkException.class,
                () -> DeliveryConsumeFlow.requireChange(1L, "原扣款流水金额"));
        assertTrue(e.getMessage().contains("正数"), e.getMessage());
        assertTrue(e.getMessage().contains("原扣款流水金额"), "拒因须点名是哪一维：" + e.getMessage());
        assertThrows(JbkException.class,
                () -> DeliveryConsumeFlow.requireChange(7500L, "原扣款流水水量"));
    }

    @Test
    void nullChangeIsRejected() {
        JbkException e = assertThrows(JbkException.class,
                () -> DeliveryConsumeFlow.requireChange(null, "原扣款流水水量"));
        assertTrue(e.getMessage().contains("缺失"), e.getMessage());
    }

    // ==================== helpers ====================

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

    /** 假库中流水这一行参与判定的列：只有业务幂等键（归属列由生产代码在回读后另行核验）。 */
    private static Map<String, Object> flowRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("BIZ_IDEMPOTENCY_KEY", EXPECTED_BIZ_KEY);
        return row;
    }

    private static WsWalletFlow flow(Long orderId, Long cardId) {
        WsWalletFlow f = new WsWalletFlow();
        f.setId(1L);
        f.setOrderId(orderId);
        f.setCardId(cardId);
        f.setAmountChange(-7500L);
        f.setMlChange(0L);
        f.setBizIdempotencyKey(EXPECTED_BIZ_KEY);
        return f;
    }

    private static WsOrder order(String orderNo) {
        WsOrder o = new WsOrder();
        o.setId(ORDER_ID);
        o.setOrderNo(orderNo);
        o.setCardId(CARD_ID);
        return o;
    }
}

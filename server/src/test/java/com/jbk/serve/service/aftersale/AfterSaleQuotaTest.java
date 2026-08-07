package com.jbk.serve.service.aftersale;

import com.jbk.serve.service.aftersale.AfterSaleQuota.Caps;
import com.jbk.serve.service.aftersale.AfterSaleQuota.Used;
import com.jbk.serve.service.aftersale.AfterSaleQuota.Verdict;
import com.jbk.serve.service.aftersale.AfterSaleStrategy.Refund;
import com.jbk.serve.service.delivery.DeliveryRefundSnapshot;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四元额度与累计封顶纯函数单测（E2E-04 包A R0-1/R0-3/R0-7）。
 *
 * <p>本类最要紧的一组用例是 {@link #eachDimensionIsCappedIndependently()} 与
 * {@link #repeatedServiceFeeRefundsCannotBorrowProductQuota()}：它们保护的是
 * 「payWay=2 下水费与配送费都从余额扣，若按混合总额判封顶，连续多次 SERVICE_FEE_ONLY
 * 申诉就能把水费额度挪去退配送费」这个 P0。若有人把三维封顶合并成一个总额判定，
 * 这两条会立刻变红；只断言"总额没超"的测试则会一路放行。</p>
 */
class AfterSaleQuotaTest {

    /** 20L桶 × 5 余额支付：水费 6500 + 配送费 1000，扣款流水金额 −7500、水量 0。 */
    private static final DeliveryRefundSnapshot.Parsed BALANCE_SNAP =
            new DeliveryRefundSnapshot.Parsed(2, 6500L, 1000L, 100_000L, 5, 1300L, 200L, 6500L);

    /** 同规格水量抵扣：余额只扣配送费 1000，水量扣 100000ml。 */
    private static final DeliveryRefundSnapshot.Parsed ML_SNAP =
            new DeliveryRefundSnapshot.Parsed(3, 0L, 1000L, 100_000L, 5, 1300L, 200L, 6500L);

    // ==================== 额度锚点 ====================

    /** payWay=2 → (水费, 配送费, 0)：水量维度必须恒 0，否则会凭空多出一份可退水量。 */
    @Test
    void balancePayCapsAnchorToWaterAmountAndDeliveryFee() {
        Caps caps = AfterSaleQuota.caps(BALANCE_SNAP, -7500L, 0L);
        assertEquals(6500L, caps.capProductFen());
        assertEquals(1000L, caps.capServiceFen());
        assertEquals(0L, caps.capProductMl(), "余额支付单没有可退水量");
    }

    /** payWay=3 → (0, 配送费, 水量)：余额侧不含任何水费，水品额度只以毫升计。 */
    @Test
    void mlPayCapsAnchorToWaterMlAndDeliveryFee() {
        Caps caps = AfterSaleQuota.caps(ML_SNAP, -1000L, -100_000L);
        assertEquals(0L, caps.capProductFen(), "水量单的余额侧水费额度必须为 0");
        assertEquals(1000L, caps.capServiceFen(), "配送费在 payWay=3 下同样从余额扣");
        assertEquals(100_000L, caps.capProductMl());
    }

    /**
     * R0-3 总额兜底：锚点必须与原扣款流水<b>逐维精确相等</b>，不等即 fail-closed。
     * 刻意不做 min() 收敛——少退用户的钱与多退一样是事故。
     */
    @Test
    void capsRejectWhenSnapshotDisagreesWithDeductionFlow() {
        // 流水比快照少：若取 min 会静默少退 100 分
        JbkException less = assertThrows(JbkException.class,
                () -> AfterSaleQuota.caps(BALANCE_SNAP, -7400L, 0L));
        assertTrue(less.getMessage().contains("账实不符"), less.getMessage());
        // 流水比快照多：若取 min 会静默把差额吞掉
        assertThrows(JbkException.class, () -> AfterSaleQuota.caps(BALANCE_SNAP, -7600L, 0L));
        // 水量维度独立比对：金额对得上不代表水量对得上
        assertThrows(JbkException.class, () -> AfterSaleQuota.caps(BALANCE_SNAP, -7500L, -1L));
        assertThrows(JbkException.class, () -> AfterSaleQuota.caps(ML_SNAP, -1000L, -99_999L));
        assertThrows(JbkException.class, () -> AfterSaleQuota.caps(ML_SNAP, -1000L, 0L));
        // payWay=3 的金额锚点只含配送费；把水费也算进去就会对不上
        assertThrows(JbkException.class, () -> AfterSaleQuota.caps(ML_SNAP, -7500L, -100_000L));
    }

    /** 扣款流水必须为负或零；正值说明取到的是一条入账流水，拿它当基准会反向放大额度。 */
    @Test
    void positiveFlowChangeIsNotADeductionAndIsRejected() {
        JbkException amount = assertThrows(JbkException.class,
                () -> AfterSaleQuota.caps(BALANCE_SNAP, 7500L, 0L));
        assertTrue(amount.getMessage().contains("正数"), amount.getMessage());
        JbkException ml = assertThrows(JbkException.class,
                () -> AfterSaleQuota.caps(ML_SNAP, -1000L, 100_000L));
        assertTrue(ml.getMessage().contains("正数"), ml.getMessage());
        // 边界：0 是合法的"该维度没扣过"，不能被一并拒绝
        assertEquals(0L, AfterSaleQuota.caps(BALANCE_SNAP, -7500L, 0L).capProductMl());
    }

    @Test
    void capsRejectMissingSnapshotAndOverflowingFlow() {
        assertThrows(JbkException.class, () -> AfterSaleQuota.caps(null, -7500L, 0L));
        // Long.MIN_VALUE 取负会越界，必须拒绝而不是回绕成负上限
        assertThrows(JbkException.class, () -> AfterSaleQuota.caps(BALANCE_SNAP, Long.MIN_VALUE, 0L));
    }

    // ==================== 三维独立封顶（P0） ====================

    /**
     * 三维独立封顶：任一维度不足都不允许拿另一维度的余量顶上，且拒因必须点名<b>是哪一维</b>。
     *
     * <p>三条断言分别构造「水品超限但配送费未超」「配送费超限但水品未超」「水量超限」，
     * 每条的另外两维都还有富余——合并成总额判定的实现会全部放行。</p>
     */
    @Test
    void eachDimensionIsCappedIndependently() {
        Caps balanceCaps = new Caps(6500L, 1000L, 0L);

        // ① 水品已用满，配送费一分没用：总额余量 1000，但水品维度必须拒绝
        JbkException product = assertThrows(JbkException.class, () -> AfterSaleQuota.requireWithinCap(
                balanceCaps, new Used(6500L, 0L, 0L), new Refund(1L, 0L, 0L)));
        assertTrue(product.getMessage().contains("水品金额"),
                "拒因必须指明是水品金额维度：" + product.getMessage());

        // ② 配送费已用满，水品一分没用：总额余量 6500，但配送费维度必须拒绝 —— 这正是 P0
        JbkException service = assertThrows(JbkException.class, () -> AfterSaleQuota.requireWithinCap(
                balanceCaps, new Used(0L, 1000L, 0L), new Refund(0L, 1L, 0L)));
        assertTrue(service.getMessage().contains("配送费"),
                "拒因必须指明是配送费维度：" + service.getMessage());
        assertFalse(service.getMessage().contains("水品金额"), "不得把配送费超限报成水品超限");

        // ③ 水量维度：金额两维完全空闲，水量仍必须独立封顶
        Caps mlCaps = new Caps(0L, 1000L, 100_000L);
        JbkException ml = assertThrows(JbkException.class, () -> AfterSaleQuota.requireWithinCap(
                mlCaps, new Used(0L, 0L, 100_000L), new Refund(0L, 0L, 1L)));
        assertTrue(ml.getMessage().contains("水品水量"),
                "拒因必须指明是水品水量维度：" + ml.getMessage());
    }

    /**
     * P0 攻击序列复现：payWay=2 单，水费 6500 + 配送费 1000，连续三次 SERVICE_FEE_ONLY 各退 1000。
     * 按混合总额判定时三次都能过（总额 7500 够用），实际却只扣过 1000 分配送费。
     */
    @Test
    void repeatedServiceFeeRefundsCannotBorrowProductQuota() {
        Caps caps = new Caps(6500L, 1000L, 0L);
        Refund oneServiceRefund = new Refund(0L, 1000L, 0L);
        // 第一次：恰好用满配送费额度
        AfterSaleQuota.requireWithinCap(caps, Used.NONE, oneServiceRefund);
        // 第二、三次：总额还剩 6500，但配送费维度已经见底
        assertThrows(JbkException.class, () -> AfterSaleQuota.requireWithinCap(
                caps, new Used(0L, 1000L, 0L), oneServiceRefund));
        assertThrows(JbkException.class, () -> AfterSaleQuota.requireWithinCap(
                caps, new Used(0L, 2000L, 0L), oneServiceRefund));
    }

    /** 恰好用满与恰好超一分：封顶边界必须是闭区间（used + requested ≤ cap）。 */
    @Test
    void capBoundaryIsInclusive() {
        Caps caps = new Caps(6500L, 1000L, 0L);
        AfterSaleQuota.requireWithinCap(caps, new Used(6000L, 900L, 0L), new Refund(500L, 100L, 0L));
        assertThrows(JbkException.class, () -> AfterSaleQuota.requireWithinCap(
                caps, new Used(6000L, 900L, 0L), new Refund(501L, 100L, 0L)));
        assertThrows(JbkException.class, () -> AfterSaleQuota.requireWithinCap(
                caps, new Used(6000L, 900L, 0L), new Refund(500L, 101L, 0L)));
        // 零返还（RESEND 场景）在任何已用额度下都不消耗额度
        AfterSaleQuota.requireWithinCap(caps, new Used(6500L, 1000L, 0L), Refund.NONE);
    }

    // ==================== check 的单维语义 ====================

    @Test
    void checkPassesWithinCapAndRejectsOverflowAndNegatives() {
        assertTrue(AfterSaleQuota.check(0L, 100L, 100L, "配送费").allowed());
        assertNull(AfterSaleQuota.check(0L, 100L, 100L, "配送费").reason(), "通过时不带原因");

        Verdict over = AfterSaleQuota.check(50L, 60L, 100L, "配送费");
        assertFalse(over.allowed());
        assertTrue(over.reason().contains("剩余 50"), over.reason());

        // 负额度没有任何合法来源；容忍它等于允许「负的已用额度」凭空放大余量
        assertFalse(AfterSaleQuota.check(-1L, 10L, 100L, "水品金额").allowed());
        assertFalse(AfterSaleQuota.check(0L, -1L, 100L, "水品金额").allowed());
        assertFalse(AfterSaleQuota.check(0L, 10L, -1L, "水品金额").allowed());

        // 已用超过上限本身就是账本断裂，必须转人工而不是只拒绝本次
        Verdict broken = AfterSaleQuota.check(101L, 0L, 100L, "配送费");
        assertFalse(broken.allowed());
        assertTrue(broken.reason().contains("账本断裂"), broken.reason());

        // 加法溢出：拒绝而不是回绕成一个"看起来还在额度内"的负数
        Verdict overflow = AfterSaleQuota.check(1L, Long.MAX_VALUE, Long.MAX_VALUE, "水品水量");
        assertFalse(overflow.allowed());
        assertTrue(overflow.reason().contains("溢出"), overflow.reason());
    }

    @Test
    void requireWithinCapRejectsMissingArguments() {
        Caps caps = new Caps(1L, 1L, 0L);
        assertThrows(JbkException.class,
                () -> AfterSaleQuota.requireWithinCap(null, Used.NONE, Refund.NONE));
        assertThrows(JbkException.class,
                () -> AfterSaleQuota.requireWithinCap(caps, null, Refund.NONE));
        assertThrows(JbkException.class,
                () -> AfterSaleQuota.requireWithinCap(caps, Used.NONE, null));
    }

    /** 取消整单：fullRefund 出来的额度必须恰好卡在上限上，且第二次取消（已用非零）必须被拒。 */
    @Test
    void fullRefundFitsCapExactlyButNotTwice() {
        Caps caps = AfterSaleQuota.caps(BALANCE_SNAP, -7500L, 0L);
        Refund full = AfterSaleStrategy.fullRefund(caps);
        AfterSaleQuota.requireWithinCap(caps, Used.NONE, full);
        assertThrows(JbkException.class, () -> AfterSaleQuota.requireWithinCap(
                caps, new Used(6500L, 1000L, 0L), full), "满额返还不得重复发放");
    }
}

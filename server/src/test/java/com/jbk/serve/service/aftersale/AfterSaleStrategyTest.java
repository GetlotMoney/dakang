package com.jbk.serve.service.aftersale;

import com.jbk.serve.service.delivery.DeliveryRefundSnapshot;
import com.jbk.tool.consts.aftersale.AfterSaleEnum.StrategyCode;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 售后补偿策略纯函数单测（E2E-04 包A R0-1/R0-2）。
 *
 * <p>本类逐格钉死「策略码 × payWay → 三元返还」矩阵：断言的是 productFen/serviceFen/productMl
 * 的<b>精确值</b>而不是"总额对得上"。若有人把 payWay=3 的水量折成现金（或反之）退给用户，
 * 总额可能仍然"看着合理"，只有三列分开断言才会立刻变红——这正是 R0-1 要防的维度串用。</p>
 *
 * <p>快照直接用 {@link DeliveryRefundSnapshot.Parsed} 构造而不经 JSON：本类只测策略算术，
 * 快照自洽性由 {@code DeliveryRefundSnapshotTest} 独立覆盖，两者不互相掩盖。</p>
 */
class AfterSaleStrategyTest {

    /** 20L桶 × 5，余额支付：水费 1300/桶、配送费 200/桶、水量 20000ml/桶。 */
    private static final DeliveryRefundSnapshot.Parsed BALANCE_SNAP =
            new DeliveryRefundSnapshot.Parsed(2, 6500L, 1000L, 100_000L, 5, 1300L, 200L, 6500L);

    /** 同一张单若走水量抵扣：余额侧水费恒 0，水品维度改由 waterMl 承接。 */
    private static final DeliveryRefundSnapshot.Parsed ML_SNAP =
            new DeliveryRefundSnapshot.Parsed(3, 0L, 1000L, 100_000L, 5, 1300L, 200L, 6500L);

    // ==================== 策略码 × payWay 返还维度矩阵 ====================

    /** payWay=2：水品退现金（productFen），水量维度必须恒 0。 */
    @Test
    void balancePayMatrixIsExactPerDimension() {
        assertRefund(StrategyCode.PRODUCT_ONLY, 2, BALANCE_SNAP, 2600L, 0L, 0L);
        assertRefund(StrategyCode.RESEND, 2, BALANCE_SNAP, 0L, 0L, 0L);
    }

    /**
     * D-414（2026-08-06 甲方确认）：履约后的售后裁决不退配送费——两个含配送费的策略
     * 在新裁决入口一律拒绝。历史已登记动作的执行链不经 compute，不受影响
     * （AfterSaleRefundTxDbTest 用旧策略码构造的存量动作仍可执行完毕即为证明）。
     */
    @Test
    void serviceFeeStrategiesRejectedOnNewDecisions() {
        for (StrategyCode banned : new StrategyCode[]{
                StrategyCode.SERVICE_FEE_ONLY, StrategyCode.PRODUCT_AND_SERVICE}) {
            for (DeliveryRefundSnapshot.Parsed snap : new DeliveryRefundSnapshot.Parsed[]{BALANCE_SNAP, ML_SNAP}) {
                JbkException e = assertThrows(JbkException.class,
                        () -> AfterSaleStrategy.compute(banned, 2, snap), banned + " 必须被拒绝");
                assertTrue(e.getMsg().contains("配送费不予退还"), "实际=" + e.getMsg());
            }
        }
    }

    /**
     * payWay=3：水品退水量（productMl），余额侧水费恒 0；而配送费在 payWay=3 下<b>同样</b>
     * 是从余额扣的，必须仍以分返还——把它折成水量是 R0-1 明令禁止的跨维折算。
     */
    @Test
    void mlPayMatrixKeepsProductAndServiceInSeparateUnits() {
        assertRefund(StrategyCode.PRODUCT_ONLY, 2, ML_SNAP, 0L, 0L, 40_000L);
        assertRefund(StrategyCode.RESEND, 2, ML_SNAP, 0L, 0L, 0L);
    }

    /** 单位量级逐格再钉一次：q=1 时水品列恰等于快照单价/单桶水量（D-414 后仅水品维可退）。 */
    @Test
    void singleUnitRefundEqualsSnapshotUnitPrices() {
        assertRefund(StrategyCode.PRODUCT_ONLY, 1, BALANCE_SNAP, 1300L, 0L, 0L);
        assertRefund(StrategyCode.PRODUCT_ONLY, 1, ML_SNAP, 0L, 0L, 20_000L);
        // 满额 q=5：与快照水品总额恰好相等，单价×数量=总额不变式在返还侧成立
        assertRefund(StrategyCode.PRODUCT_ONLY, 5, BALANCE_SNAP, 6500L, 0L, 0L);
        assertRefund(StrategyCode.PRODUCT_ONLY, 5, ML_SNAP, 0L, 0L, 100_000L);
    }

    @Test
    void totalFenIsSumOfCashDimensionsOnly() {
        // 双现金列的构造经 fullRefund 仍可达（待接单取消含配送费），Refund 语义不因 D-414 而变
        AfterSaleStrategy.Refund refund = new AfterSaleStrategy.Refund(2600L, 400L, 0L);
        assertEquals(3000L, refund.totalFen(), "合计只加两列现金");
        assertFalse(refund.isZero());
        // payWay=3 的水量不得混进 totalFen，否则会按毫升数去扣余额
        assertEquals(400L, new AfterSaleStrategy.Refund(0L, 400L, 40_000L).totalFen(), "水量不进金额合计");
        assertTrue(AfterSaleStrategy.Refund.NONE.isZero());
    }

    // ==================== 终态派生与资金开关 ====================

    @Test
    void deriveOutcomeMapsEachStrategyToItsTerminalState() {
        assertEquals(DeliveryEnum.AppealStatus.REJECTED,
                AfterSaleStrategy.deriveOutcome(StrategyCode.REJECT));
        assertEquals(DeliveryEnum.AppealStatus.RESEND_PENDING,
                AfterSaleStrategy.deriveOutcome(StrategyCode.RESEND));
        for (StrategyCode funding : new StrategyCode[]{StrategyCode.PRODUCT_ONLY,
                StrategyCode.SERVICE_FEE_ONLY, StrategyCode.PRODUCT_AND_SERVICE}) {
            assertEquals(DeliveryEnum.AppealStatus.COMPENSATE_PENDING,
                    AfterSaleStrategy.deriveOutcome(funding), funding + " 必须落成立待补偿");
        }
        assertThrows(JbkException.class, () -> AfterSaleStrategy.deriveOutcome(null));
    }

    /** 终态数值同时钉死：枚举值一旦被改动，落库的 APPEAL_STATUS 会静默漂移。 */
    @Test
    void deriveOutcomeValuesMatchDictionary() {
        assertEquals(3, AfterSaleStrategy.deriveOutcome(StrategyCode.REJECT).getValue());
        assertEquals(5, AfterSaleStrategy.deriveOutcome(StrategyCode.RESEND).getValue());
        assertEquals(2, AfterSaleStrategy.deriveOutcome(StrategyCode.PRODUCT_ONLY).getValue());
    }

    @Test
    void onlyThreeFundingStrategiesRefundAssets() {
        assertTrue(AfterSaleStrategy.refundsAssets(StrategyCode.PRODUCT_ONLY));
        assertTrue(AfterSaleStrategy.refundsAssets(StrategyCode.SERVICE_FEE_ONLY));
        assertTrue(AfterSaleStrategy.refundsAssets(StrategyCode.PRODUCT_AND_SERVICE));
        assertFalse(AfterSaleStrategy.refundsAssets(StrategyCode.RESEND), "补送走履约，不动钱");
        assertFalse(AfterSaleStrategy.refundsAssets(StrategyCode.REJECT), "驳回零写入");
        assertThrows(JbkException.class, () -> AfterSaleStrategy.refundsAssets(null));
    }

    @Test
    void requireStrategyIsTheOnlyWhitelistGate() {
        assertEquals(StrategyCode.PRODUCT_AND_SERVICE,
                AfterSaleStrategy.requireStrategy("PRODUCT_AND_SERVICE"));
        assertEquals(StrategyCode.REJECT, AfterSaleStrategy.requireStrategy("REJECT"));
        assertThrows(JbkException.class, () -> AfterSaleStrategy.requireStrategy(null));
        assertThrows(JbkException.class, () -> AfterSaleStrategy.requireStrategy(""));
        assertThrows(JbkException.class, () -> AfterSaleStrategy.requireStrategy("  "));
        assertThrows(JbkException.class, () -> AfterSaleStrategy.requireStrategy("product_only"),
                "大小写不符不得当作合法码");
        assertThrows(JbkException.class, () -> AfterSaleStrategy.requireStrategy("FULL_REFUND"));
    }

    // ==================== 数量边界 ====================

    /** QUANTITY：少送几桶就只能赔几桶，上界 = 计划 − 实收，与实际签收数无关。 */
    @Test
    void quantityMaxIsPlannedMinusReceived() {
        assertEquals(3, AfterSaleStrategy.maxApprovedCount("QUANTITY", 2, 5, 2));
        assertEquals(5, AfterSaleStrategy.maxApprovedCount("QUANTITY", 0, 5, 0));
        assertEquals(0, AfterSaleStrategy.maxApprovedCount("QUANTITY", 5, 5, 5));
        // 声明实收多于计划 = 数据自相矛盾，转人工而不是取 min 掩盖
        assertThrows(JbkException.class, () -> AfterSaleStrategy.maxApprovedCount("QUANTITY", 6, 5, 5));
        // 缺证据不裁决：null 一律拒绝，绝不按 0 兜底（那会把部分赔付放大成全单赔付）
        assertThrows(JbkException.class, () -> AfterSaleStrategy.maxApprovedCount("QUANTITY", null, 5, 5));
        assertThrows(JbkException.class, () -> AfterSaleStrategy.maxApprovedCount("QUANTITY", 2, null, 5));
        assertThrows(JbkException.class, () -> AfterSaleStrategy.maxApprovedCount("QUANTITY", -1, 5, 5));
    }

    /** QUALITY/DAMAGE：上界 = min(实收, 实际签收)，没签收的桶谈不上水质或破损。 */
    @Test
    void qualityAndDamageMaxIsMinOfReceivedAndActual() {
        for (String reason : new String[]{"QUALITY", "DAMAGE"}) {
            assertEquals(3, AfterSaleStrategy.maxApprovedCount(reason, 3, 5, 5), reason);
            assertEquals(2, AfterSaleStrategy.maxApprovedCount(reason, 2, 5, 4), reason);
            assertEquals(4, AfterSaleStrategy.maxApprovedCount(reason, 4, 5, 4), reason);
            // 声明实收 > 实际签收：两个独立数据源打架，拒绝裁决
            assertThrows(JbkException.class,
                    () -> AfterSaleStrategy.maxApprovedCount(reason, 5, 5, 4), reason);
            assertThrows(JbkException.class,
                    () -> AfterSaleStrategy.maxApprovedCount(reason, null, 5, 4), reason);
            assertThrows(JbkException.class,
                    () -> AfterSaleStrategy.maxApprovedCount(reason, 3, 5, null), reason);
        }
    }

    /** PLACEMENT/OTHER 只走补送，上界取实际签收数量。 */
    @Test
    void placementAndOtherMaxIsActualDeliveryCount() {
        assertEquals(4, AfterSaleStrategy.maxApprovedCount("PLACEMENT", 4, 5, 4));
        assertEquals(4, AfterSaleStrategy.maxApprovedCount("OTHER", 0, 5, 4));
        assertThrows(JbkException.class, () -> AfterSaleStrategy.maxApprovedCount("PLACEMENT", 4, 5, null));
    }

    @Test
    void unknownReasonCodeIsRejected() {
        assertThrows(JbkException.class, () -> AfterSaleStrategy.maxApprovedCount("REFUND", 1, 5, 5));
        assertThrows(JbkException.class, () -> AfterSaleStrategy.maxApprovedCount(null, 1, 5, 5));
        assertThrows(JbkException.class, () -> AfterSaleStrategy.maxApprovedCount("", 1, 5, 5));
        assertThrows(JbkException.class, () -> AfterSaleStrategy.maxApprovedCount("quantity", 1, 5, 5),
                "小写码不在白名单");
    }

    /**
     * PLACEMENT/OTHER × 资金策略 = 一律拒绝。
     * 摆放问题货与配送都已履约，赔钱没有对应的扣款事实可退；OTHER 语义未定义。
     */
    @Test
    void placementAndOtherRejectEveryFundingStrategy() {
        for (String reason : new String[]{"PLACEMENT", "OTHER"}) {
            for (StrategyCode funding : new StrategyCode[]{StrategyCode.PRODUCT_ONLY,
                    StrategyCode.SERVICE_FEE_ONLY, StrategyCode.PRODUCT_AND_SERVICE}) {
                assertThrows(JbkException.class, () -> AfterSaleStrategy.requireApprovedCount(
                        funding, reason, 1, 4, 5, 4), reason + " × " + funding + " 必须拒绝");
            }
            // 不动钱的两条出口保持畅通
            assertEquals(1, AfterSaleStrategy.requireApprovedCount(
                    StrategyCode.RESEND, reason, 1, 4, 5, 4));
            assertEquals(0, AfterSaleStrategy.requireApprovedCount(
                    StrategyCode.REJECT, reason, null, null, null, null));
        }
    }

    @Test
    void rejectNeedsNoCountsAndAlwaysYieldsZero() {
        // 驳回不产生执行动作，不该为了驳回还得凑齐计数字段
        assertEquals(0, AfterSaleStrategy.requireApprovedCount(
                StrategyCode.REJECT, "QUANTITY", null, null, null, null));
        assertEquals(0, AfterSaleStrategy.requireApprovedCount(
                StrategyCode.REJECT, "QUALITY", 99, 1, 1, 1), "越界数量在驳回下也只归零");
    }

    @Test
    void approvedCountOutOfRangeIsRejectedNotClamped() {
        // 上界 3（计划 5 − 实收 2）
        assertEquals(3, AfterSaleStrategy.requireApprovedCount(
                StrategyCode.PRODUCT_ONLY, "QUANTITY", 3, 2, 5, 2), "恰好取到上界应通过");
        assertEquals(1, AfterSaleStrategy.requireApprovedCount(
                StrategyCode.PRODUCT_ONLY, "QUANTITY", 1, 2, 5, 2));
        for (Integer bad : new Integer[]{null, 0, -1, 4, 99}) {
            assertThrows(JbkException.class, () -> AfterSaleStrategy.requireApprovedCount(
                    StrategyCode.PRODUCT_ONLY, "QUANTITY", bad, 2, 5, 2),
                    "批准数量 " + bad + " 必须拒绝而不是截断到上界");
        }
    }

    @Test
    void zeroPayableCountBlocksBothCompensationAndResend() {
        // 计划 5 全部实收：QUANTITY 上界为 0，此时补偿与补送都无标的
        assertThrows(JbkException.class, () -> AfterSaleStrategy.requireApprovedCount(
                StrategyCode.PRODUCT_ONLY, "QUANTITY", 1, 5, 5, 5));
        assertThrows(JbkException.class, () -> AfterSaleStrategy.requireApprovedCount(
                StrategyCode.RESEND, "QUANTITY", 1, 5, 5, 5));
        // 一桶都没签收：QUALITY 上界为 0
        assertThrows(JbkException.class, () -> AfterSaleStrategy.requireApprovedCount(
                StrategyCode.PRODUCT_ONLY, "QUALITY", 1, 0, 5, 0));
    }

    @Test
    void requireApprovedCountRejectsMissingStrategy() {
        assertThrows(JbkException.class, () -> AfterSaleStrategy.requireApprovedCount(
                null, "QUANTITY", 1, 2, 5, 2));
    }

    // ==================== compute 的独立防线与溢出 ====================

    /** 驳回走到 compute 说明调用方漏了白名单：宁可炸，也不能返回一个"零返还"的合法对象。 */
    @Test
    void rejectMustNotProduceAnExecutionRow() {
        JbkException e = assertThrows(JbkException.class,
                () -> AfterSaleStrategy.compute(StrategyCode.REJECT, 1, BALANCE_SNAP));
        assertTrue(e.getMessage().contains("驳回"), "拒因须点名驳回：" + e.getMessage());
        // 数量为 0 也一样炸，不能因为"反正是零"就放行
        assertThrows(JbkException.class,
                () -> AfterSaleStrategy.compute(StrategyCode.REJECT, 0, BALANCE_SNAP));
    }

    @Test
    void computeRejectsMissingSnapshotAndStrategy() {
        assertThrows(JbkException.class,
                () -> AfterSaleStrategy.compute(StrategyCode.PRODUCT_ONLY, 1, null));
        assertThrows(JbkException.class, () -> AfterSaleStrategy.compute(null, 1, BALANCE_SNAP));
    }

    @Test
    void computeRejectsNonPositiveCountForFundingStrategies() {
        assertThrows(JbkException.class,
                () -> AfterSaleStrategy.compute(StrategyCode.PRODUCT_ONLY, 0, BALANCE_SNAP));
        assertThrows(JbkException.class,
                () -> AfterSaleStrategy.compute(StrategyCode.SERVICE_FEE_ONLY, -1, BALANCE_SNAP));
    }

    /** 与快照的独立交叉核对：批准数量的上界来自任务表，这里再对一次订单快照。 */
    @Test
    void computeCrossChecksCountAgainstSnapshotDeliveryCount() {
        assertRefund(StrategyCode.PRODUCT_ONLY, 5, BALANCE_SNAP, 6500L, 0L, 0L);
        JbkException e = assertThrows(JbkException.class,
                () -> AfterSaleStrategy.compute(StrategyCode.PRODUCT_ONLY, 6, BALANCE_SNAP));
        assertTrue(e.getMessage().contains("快照"), "拒因须指出与快照不符：" + e.getMessage());
    }

    /**
     * 溢出即拒绝：回绕出来的小额返还比拒绝危险得多（乘法一旦回绕，
     * 超额返还会伪装成一笔"金额很小、封顶也过得去"的正常补偿）。
     */
    @Test
    void arithmeticOverflowIsRejectedNotWrappedAround() {
        DeliveryRefundSnapshot.Parsed huge = new DeliveryRefundSnapshot.Parsed(
                2, Long.MAX_VALUE, Long.MAX_VALUE, 1_000_000L, 1_000_000,
                Long.MAX_VALUE / 2, Long.MAX_VALUE / 2, Long.MAX_VALUE);
        JbkException product = assertThrows(JbkException.class,
                () -> AfterSaleStrategy.compute(StrategyCode.PRODUCT_ONLY, 1_000_000, huge));
        assertTrue(product.getMessage().contains("溢出"), product.getMessage());
        // 配送费维的溢出分支随 D-414 策略拒绝一并不可达（防线代码保留在 compute 内，
        // 若将来恢复配送费策略会重新生效）；水量维单桶量=总量÷份数为派生值，
        // 乘回份数在数学上不可能超过总量，天然无溢出面，故此处不再构造。
        // 合计同样是 checked：两列各自不溢出、相加溢出时必须炸而不是回绕成负数
        assertThrows(ArithmeticException.class,
                () -> new AfterSaleStrategy.Refund(Long.MAX_VALUE, 1L, 0L).totalFen());
    }

    // ==================== fullRefund ====================

    /** 取消 = 整单原样退回：三列必须逐列等于额度上限，且不得跨维搬运。 */
    @Test
    void fullRefundEqualsCapsColumnByColumn() {
        AfterSaleQuota.Caps balanceCaps = new AfterSaleQuota.Caps(6500L, 1000L, 0L);
        AfterSaleStrategy.Refund balance = AfterSaleStrategy.fullRefund(balanceCaps);
        assertEquals(6500L, balance.productFen());
        assertEquals(1000L, balance.serviceFen());
        assertEquals(0L, balance.productMl());

        AfterSaleQuota.Caps mlCaps = new AfterSaleQuota.Caps(0L, 1000L, 100_000L);
        AfterSaleStrategy.Refund ml = AfterSaleStrategy.fullRefund(mlCaps);
        assertEquals(0L, ml.productFen(), "水量单不得凭空退现金水费");
        assertEquals(1000L, ml.serviceFen());
        assertEquals(100_000L, ml.productMl());
    }

    @Test
    void fullRefundRejectsMissingCaps() {
        assertThrows(JbkException.class, () -> AfterSaleStrategy.fullRefund(null));
    }

    // ==================== helpers ====================

    private static void assertRefund(StrategyCode strategy, int approvedCount,
                                     DeliveryRefundSnapshot.Parsed snap,
                                     long expectProductFen, long expectServiceFen, long expectProductMl) {
        AfterSaleStrategy.Refund refund = AfterSaleStrategy.compute(strategy, approvedCount, snap);
        String tag = strategy + "@payWay=" + snap.payWay() + "×" + approvedCount;
        assertEquals(expectProductFen, refund.productFen(), tag + " 水品金额");
        assertEquals(expectServiceFen, refund.serviceFen(), tag + " 配送费");
        assertEquals(expectProductMl, refund.productMl(), tag + " 水品水量");
    }
}

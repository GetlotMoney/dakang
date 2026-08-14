package com.jbk.serve.service.aftersale.batch;

import com.jbk.tool.data.aftersale.po.WsCardEntitlementBatch;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权益退款折算与批次选取次序单测（E2E-04 包D，REQ-061）。
 *
 * <p>这两块是包D 的算术核心：折算决定「退多少钱」，选取次序决定「先扣谁的水」。
 * 两者都是纯函数，故用穷举而非抽样——金额差一分、次序错一位，都是真实资金后果。</p>
 */
class EntitlementRefundMathTest {

    // ==================== 水量套餐折算 ====================

    /** 一分没用：全额可退。 */
    @Test
    void unusedWaterPackageRefundsFullPaidAmount() {
        assertEquals(0L, EntitlementRefundMath.usedPrincipalOfWaterPackage(10000L, 0L, 500000L));
        assertEquals(10000L, EntitlementRefundMath.refundableOfWaterPackage(10000L, 0L, 500000L, 0L));
    }

    /** 用光：一分不退。 */
    @Test
    void fullyUsedWaterPackageRefundsNothing() {
        assertEquals(10000L, EntitlementRefundMath.usedPrincipalOfWaterPackage(10000L, 500000L, 500000L));
        assertEquals(0L, EntitlementRefundMath.refundableOfWaterPackage(10000L, 500000L, 500000L, 0L));
    }

    /** 整除场景：用掉两成水，退八成钱。 */
    @Test
    void proportionalUsageRefundsProportionally() {
        assertEquals(2000L, EntitlementRefundMath.usedPrincipalOfWaterPackage(10000L, 100000L, 500000L));
        assertEquals(8000L, EntitlementRefundMath.refundableOfWaterPackage(10000L, 100000L, 500000L, 0L));
    }

    /**
     * 除不尽必须<b>向上</b>取整已用本金，即退款额向下走。
     *
     * <p>100 元 / 500升 的卡用掉 1 毫升：精确已用本金是 0.02 分。
     * ceil 后记 1 分，可退 9999 分。若写成 floor 会记 0 分、可退 10000 分——
     * 用户用了水却拿回全款，且每一笔都「只差一分」，在大量小额退款上会持续多退。</p>
     */
    @Test
    void indivisibleUsageRoundsPrincipalUpSoRefundRoundsDown() {
        assertEquals(1L, EntitlementRefundMath.usedPrincipalOfWaterPackage(10000L, 1L, 500000L));
        assertEquals(9999L, EntitlementRefundMath.refundableOfWaterPackage(10000L, 1L, 500000L, 0L));
        // 差额上界恒为 1 分：ceil 与精确值之差永远落在 (0,1)
        assertEquals(3334L, EntitlementRefundMath.usedPrincipalOfWaterPackage(10000L, 166666L, 500000L));
    }

    /** 已消费的赠送从可退里扣：用户没为赠送付过钱，用掉后不能按实付全额退。 */
    @Test
    void consumedBonusReducesRefundable() {
        assertEquals(7000L, EntitlementRefundMath.refundableOfWaterPackage(10000L, 100000L, 500000L, 1000L));
    }

    /** 扣到负数时夹到 0：负的可退额会让「退款」变成「向用户收钱」。 */
    @Test
    void refundableIsClampedAtZeroNeverNegative() {
        assertEquals(0L, EntitlementRefundMath.refundableOfWaterPackage(10000L, 400000L, 500000L, 5000L));
        assertEquals(0L, EntitlementRefundMath.refundableOfCashPackage(5000L, 4000L, 3000L));
    }

    /** 已用超过发放：批次账本自相矛盾，必须拒绝而不是算出一个看起来合理的数。 */
    @Test
    void usedExceedingGrantedIsRejected() {
        JbkException ex = assertThrows(JbkException.class,
                () -> EntitlementRefundMath.usedPrincipalOfWaterPackage(10000L, 500001L, 500000L));
        assertTrue(ex.getMessage().contains("批次账本异常"), "实际=" + ex.getMessage());
    }

    @Test
    void nonPositiveGrantedWaterIsRejected() {
        for (long granted : new long[] { 0L, -1L }) {
            assertTrue(assertThrows(JbkException.class,
                    () -> EntitlementRefundMath.usedPrincipalOfWaterPackage(10000L, 0L, granted))
                    .getMessage().contains("发放水量必须为正"));
        }
    }

    @Test
    void negativeInputsAreRejected() {
        assertTrue(assertThrows(JbkException.class,
                () -> EntitlementRefundMath.usedPrincipalOfWaterPackage(-1L, 0L, 500000L))
                .getMessage().contains("不能为负"));
        assertTrue(assertThrows(JbkException.class,
                () -> EntitlementRefundMath.refundableOfCashPackage(10000L, -1L, 0L))
                .getMessage().contains("不能为负"));
        assertTrue(assertThrows(JbkException.class,
                () -> EntitlementRefundMath.refundableOfCashPackage(10000L, 0L, -1L))
                .getMessage().contains("不能为负"));
    }

    /** 溢出必须在写库前炸掉，绝不静默回绕成负数。 */
    @Test
    void overflowIsRejectedNotWrapped() {
        assertThrows(ArithmeticException.class,
                () -> EntitlementRefundMath.usedPrincipalOfWaterPackage(Long.MAX_VALUE, 2L, 3L));
    }

    // ==================== 纯金额套餐折算 ====================

    @Test
    void cashPackageRefundsPaidMinusConsumed() {
        assertEquals(5000L, EntitlementRefundMath.refundableOfCashPackage(5000L, 0L, 0L));
        assertEquals(2000L, EntitlementRefundMath.refundableOfCashPackage(5000L, 3000L, 0L));
        assertEquals(1000L, EntitlementRefundMath.refundableOfCashPackage(5000L, 3000L, 1000L));
    }

    // ==================== 批次选取次序 ====================

    private WsCardEntitlementBatch batch(long id, String expire, String createTime) {
        WsCardEntitlementBatch b = new WsCardEntitlementBatch();
        b.setId(id);
        b.setExpireTime(expire);
        b.setBatchStatus(EntitlementBatchOrder.BatchStatus.AVAILABLE);
        b.setCreateTime(createTime);
        return b;
    }

    private List<Long> sortedIds(WsCardEntitlementBatch... batches) {
        List<WsCardEntitlementBatch> list = new ArrayList<>(List.of(batches));
        list.sort(EntitlementBatchOrder.consumeOrder());
        return list.stream().map(WsCardEntitlementBatch::getId).toList();
    }

    /** 最早到期优先。 */
    @Test
    void earliestExpiryIsConsumedFirst() {
        assertEquals(List.of(2L, 1L, 3L), sortedIds(
                batch(1L, "20270101000000", "20260101000000"),
                batch(2L, "20260601000000", "20260101000000"),
                batch(3L, "20280101000000", "20260101000000")));
    }

    /**
     * 永久批次（有效期为空）排最后。
     *
     * <p>先用会过期的、后用永久的，是对用户最有利的顺序；反过来会出现
     * 「永久的用光了、有限期的过期作废了」这种两头落空。</p>
     */
    @Test
    void permanentBatchIsConsumedLast() {
        assertEquals(List.of(2L, 3L, 1L), sortedIds(
                batch(1L, null, "20260101000000"),
                batch(2L, "20260601000000", "20260101000000"),
                batch(3L, "20270101000000", "20260101000000")));
        // 空串与 null 同义
        assertEquals(List.of(2L, 1L), sortedIds(
                batch(1L, "", "20260101000000"),
                batch(2L, "20260601000000", "20260101000000")));
    }

    /** 同到期时间按创建时间升序。 */
    @Test
    void sameExpiryFallsBackToCreateTime() {
        assertEquals(List.of(2L, 1L), sortedIds(
                batch(1L, "20260601000000", "20260201000000"),
                batch(2L, "20260601000000", "20260101000000")));
    }

    /**
     * 到期与创建时间都相同时按 ID 兜底。
     *
     * <p>CREATE_TIME 只精确到秒，同一秒建出的两个批次靠前两级分不出先后，
     * 次序会退化成不确定——而不确定正是这个比较器要消除的东西。</p>
     */
    @Test
    void identicalExpiryAndCreateTimeFallBackToId() {
        assertEquals(List.of(7L, 9L), sortedIds(
                batch(9L, "20260601000000", "20260101000000"),
                batch(7L, "20260601000000", "20260101000000")));
    }

    // ==================== 可消费性闸 ====================

    /**
     * 退款锁定的批次不可消费。
     *
     * <p>任务书明令「退款审核后必须锁定对应批次，防止退款处理中继续消费」。
     * 少了这道闸，用户在退款受理后还能把这批权益用掉，退款成功时冲正的
     * 就是一个已经被花掉的余额。</p>
     */
    @Test
    void refundLockedBatchIsNotConsumable() {
        WsCardEntitlementBatch locked = batch(1L, null, "20260101000000");
        locked.setBatchStatus(EntitlementBatchOrder.BatchStatus.REFUND_LOCKED);
        JbkException ex = assertThrows(JbkException.class,
                () -> EntitlementBatchOrder.requireConsumable(locked));
        assertTrue(ex.getMessage().contains("退款处理中"), "实际=" + ex.getMessage());
    }

    /**
     * 可消费集合 = {1可用, 6不可退}：6 表达「退不了款」不表达「花不了」，历史聚合批次恒为 6
     * 且承载存量卡真实余额，判成不可消费会让存量卡一分扣不动。退款收口在 lockForRefund 只认 1。
     */
    @Test
    void availableAndNonRefundableBatchesAreConsumable() {
        EntitlementBatchOrder.requireConsumable(batch(1L, null, "20260101000000"));
        WsCardEntitlementBatch legacy = batch(1L, null, "20260101000000");
        legacy.setBatchStatus(EntitlementBatchOrder.BatchStatus.NON_REFUNDABLE);
        EntitlementBatchOrder.requireConsumable(legacy);

        for (int status : new int[] {
                EntitlementBatchOrder.BatchStatus.REFUNDED,
                EntitlementBatchOrder.BatchStatus.EXHAUSTED,
                EntitlementBatchOrder.BatchStatus.EXPIRED }) {
            WsCardEntitlementBatch b = batch(1L, null, "20260101000000");
            b.setBatchStatus(status);
            assertTrue(assertThrows(JbkException.class, () -> EntitlementBatchOrder.requireConsumable(b))
                    .getMessage().contains("不可消费"), "status=" + status);
        }
    }

    @Test
    void missingBatchOrStatusIsRejected() {
        assertTrue(assertThrows(JbkException.class, () -> EntitlementBatchOrder.requireConsumable(null))
                .getMessage().contains("批次不存在"));
        WsCardEntitlementBatch noStatus = batch(1L, null, "20260101000000");
        noStatus.setBatchStatus(null);
        assertTrue(assertThrows(JbkException.class, () -> EntitlementBatchOrder.requireConsumable(noStatus))
                .getMessage().contains("状态缺失"));
    }
}

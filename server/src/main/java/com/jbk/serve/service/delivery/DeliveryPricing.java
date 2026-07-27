package com.jbk.serve.service.delivery;

import com.jbk.tool.exception.JbkException;

import java.util.Map;

/**
 * 配送计价唯一入口（E2E-03 规则1/2：水费+配送费分开快照，总额=两者之和）。
 *
 * <p>价目与水量换算对齐小程序行为契约 miniapp/src/api/delivery.ts 的
 * CONTAINER_WATER_PRICE_FEN / CONTAINER_WATER_ML / DELIVERY_FEE_PER_CONTAINER_FEN：
 * 3L袋 300、5L桶 500、10L桶 1200、20L桶 1300（分/桶），配送费固定 200 分/桶。
 * 正式价目待商业一期确认后只改本类常量，禁止各路径散落复制。</p>
 */
public final class DeliveryPricing {

    /** 容器规格 → 单桶水费(分)。白名单外的规格一律拒绝（fail-closed）。 */
    private static final Map<String, Long> CONTAINER_WATER_PRICE_FEN = Map.of(
            "3L袋", 300L,
            "5L桶", 500L,
            "10L桶", 1200L,
            "20L桶", 1300L);

    /**
     * 容器规格 → 单桶水量(毫升)，D-214 混合结算（payWay=3）按此表折算 BALANCE_ML 抵扣量。
     * 与水价表同键同白名单：规格是容器的物理容量而非价目，两表键集合必须一致，
     * 缺一侧即视为规格未登记（quote 会在取价一步 fail-closed）。
     */
    private static final Map<String, Long> CONTAINER_WATER_ML = Map.of(
            "3L袋", 3_000L,
            "5L桶", 5_000L,
            "10L桶", 10_000L,
            "20L桶", 20_000L);

    /** 配送费(分/桶)，与 Mock 契约同值。 */
    public static final long DELIVERY_FEE_PER_CONTAINER_FEN = 200L;

    /** 单笔数量上限：防溢出与异常大单（Bo 层同界；这里是不可绕过的最后防线）。 */
    public static final int MAX_COUNT = 99;

    private DeliveryPricing() {
    }

    /**
     * 价格快照：waterAmountFen / deliveryFeeFen / totalFen / waterMl（全程 checked arithmetic）。
     * waterMl 是本单容器总水量（毫升），payWay=3 时即 BALANCE_ML 抵扣量；payWay=2 只作快照参考。
     */
    public record Quote(long waterAmountFen, long deliveryFeeFen, long totalFen, long waterMl) {
    }

    public static long requireUnitWaterPrice(String containerSpec) {
        Long price = containerSpec == null ? null : CONTAINER_WATER_PRICE_FEN.get(containerSpec);
        if (price == null) {
            throw new JbkException("容器规格不合法");
        }
        return price;
    }

    /** 容器规格 → 单桶水量(毫升)；白名单外 fail-closed，与取价同口径。 */
    public static long requireUnitWaterMl(String containerSpec) {
        Long ml = containerSpec == null ? null : CONTAINER_WATER_ML.get(containerSpec);
        if (ml == null) {
            throw new JbkException("容器规格不合法");
        }
        return ml;
    }

    public static int requireDeliveryCount(Integer count) {
        if (count == null || count <= 0 || count > MAX_COUNT) {
            throw new JbkException("配送数量不合法");
        }
        return count;
    }

    public static int requireReturnCount(Integer count) {
        if (count == null || count < 0 || count > MAX_COUNT) {
            throw new JbkException("回收数量不合法");
        }
        return count;
    }

    /**
     * 计算下单快照。数量先过界校验；乘加全部 checked，溢出即拒绝下单，
     * 绝不允许回绕成小额扣款或小量抵扣。
     */
    public static Quote quote(String containerSpec, Integer deliveryCount) {
        long unit = requireUnitWaterPrice(containerSpec);
        long unitMl = requireUnitWaterMl(containerSpec);
        int count = requireDeliveryCount(deliveryCount);
        try {
            long water = Math.multiplyExact(unit, count);
            long fee = Math.multiplyExact(DELIVERY_FEE_PER_CONTAINER_FEN, count);
            long total = Math.addExact(water, fee);
            long waterMl = Math.multiplyExact(unitMl, count);
            return new Quote(water, fee, total, waterMl);
        } catch (ArithmeticException e) {
            throw new JbkException("配送计价溢出，拒绝下单");
        }
    }
}

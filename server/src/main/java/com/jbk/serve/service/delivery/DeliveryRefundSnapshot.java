package com.jbk.serve.service.delivery;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 配送订单 {@code ws_order.PACKAGE_SNAP} 的唯一解析器（铁律⑤：快照解析只允许一份实现）。
 *
 * <p>返还基准是下单时冻结的快照而非 {@link DeliveryPricing} 的当前价目——用现价重算历史单
 * 会多退或少退，故 {@code service/aftersale/**} 明令禁止 import {@code DeliveryPricing}；
 * 也刻意不暴露 containerSpec，防止调用方拿它去价目表重算。</p>
 *
 * <p>单价恒由快照总额整除数量派生（自动补货快照 {@code generateOne} 不写单价字段也可解析）；
 * 快照带单价字段时须与派生值精确相等，顺带钉死「单价×数量=总额」不变式。快照与订单总额、
 * 扣款流水的三方恒等属 {@code DeliveryLedgerVerifier}，此处不重复判定。</p>
 */
public final class DeliveryRefundSnapshot {

    // 字段名常量一律 private：外传 key 会长出第二份解析逻辑；需要新字段就在本类加访问器
    private static final String F_PAY_WAY = "payWay";
    private static final String F_WATER_AMOUNT_FEN = "waterAmountFen";
    private static final String F_DELIVERY_FEE_FEN = "deliveryFeeFen";
    private static final String F_WATER_ML = "waterMl";
    private static final String F_DELIVERY_COUNT = "deliveryCount";
    private static final String F_UNIT_WATER_PRICE_FEN = "unitWaterPriceFen";
    private static final String F_DELIVERY_FEE_PER_CONTAINER_FEN = "deliveryFeePerContainerFen";
    private static final String F_PRICE_WATER_AMOUNT_FEN = "priceWaterAmountFen";

    /**
     * 溢出闸，刻意不复用 {@code DeliveryPricing.MAX_COUNT}：下单上限会随业务调整，
     * 共用会让改下单上限时静默改变历史单的可解析范围。
     */
    private static final int MAX_DELIVERY_COUNT = 100_000;

    private DeliveryRefundSnapshot() {
    }

    /**
     * 配送创单冻结快照的返还相关切片。
     *
     * @param payWay                     原支付方式：2 卡余额 / 3 卡水量（1 微信不走内部返还）
     * @param waterAmountFen             本单实际从<b>余额</b>扣的水费（payWay=3 恒 0）
     * @param deliveryFeeFen             本单配送费总额（两种支付方式下<b>都</b>从余额扣）
     * @param waterMl                    本单容器总水量；payWay=3 时即从卡水量扣的毫升数
     * @param deliveryCount              计划配送桶数
     * @param unitWaterPriceFen          单桶水费（分），冻结价
     * @param deliveryFeePerContainerFen 单桶配送费（分），冻结价
     * @param priceWaterAmountFen        原价目水费总额（payWay=3 时 &gt; 0，是水量的价目参考而非扣款）
     */
    public record Parsed(int payWay, long waterAmountFen, long deliveryFeeFen, long waterMl,
                         int deliveryCount, long unitWaterPriceFen, long deliveryFeePerContainerFen,
                         long priceWaterAmountFen) {

        /** 原支付方式是否为卡水量抵扣（payWay=3）。 */
        public boolean payByMl() {
            return payWay == TradeEnum.PayWay.CARD_ML.getValue();
        }

        /**
         * 单桶水量（毫升）。整除性已在解析期校验：除不尽的快照在解析期即拒绝，
         * 绝不允许四舍五入进资金链路。
         */
        public long unitWaterMl() {
            return waterMl / deliveryCount;
        }
    }


    /**
     * 写事务用（售后返还、配送扣款）：任一必需字段缺失、类型非法或与总额不自洽一律 fail-closed。
     * 金额与配额不许猜——猜错的代价是多退或少退真金白银。
     */
    public static Parsed require(WsOrder order) {
        if (order == null || StrUtil.isBlank(order.getPackageSnap())) {
            throw new JbkException("配送订单快照缺失，无法确定历史价格");
        }
        JSONObject snap;
        try {
            snap = JSONUtil.parseObj(order.getPackageSnap());
        } catch (RuntimeException e) {
            throw new JbkException("配送订单快照不是合法 JSON");
        }
        return readFields(snap);
    }

    /**
     * 快照字段的唯一判定体；不设「坏值返回 null」的宽松入口，只读试算应在其上包一层捕获。
     */
    private static Parsed readFields(JSONObject snap) {
        int payWay = requireInt(snap, F_PAY_WAY, "配送快照支付方式");
        if (payWay != TradeEnum.PayWay.CARD_BALANCE.getValue()
                && payWay != TradeEnum.PayWay.CARD_ML.getValue()) {
            // 微信直付（1）与未知值都不在内部权益返还的射程内：白名单而非黑名单
            throw new JbkException("配送快照支付方式不支持内部返还：" + payWay);
        }
        int deliveryCount = requireInt(snap, F_DELIVERY_COUNT, "配送快照配送数量");
        if (deliveryCount < 1 || deliveryCount > MAX_DELIVERY_COUNT) {
            throw new JbkException("配送快照配送数量非法：" + deliveryCount);
        }
        long waterAmountFen = requireNonNegativeLong(snap, F_WATER_AMOUNT_FEN, "配送快照水费");
        long deliveryFeeFen = requireNonNegativeLong(snap, F_DELIVERY_FEE_FEN, "配送快照配送费");
        long priceWaterAmountFen = requireNonNegativeLong(snap, F_PRICE_WATER_AMOUNT_FEN, "配送快照价目水费");
        long waterMl = requireNonNegativeLong(snap, F_WATER_ML, "配送快照水量");
        if (waterMl <= 0) {
            // 水量为 0 的配送单不存在；容忍它会让 payWay=3 的单桶水量退化成 0，返还静默变成 0
            throw new JbkException("配送快照水量必须为正");
        }
        if (waterMl % deliveryCount != 0) {
            throw new JbkException("配送快照水量与配送数量不自洽：" + waterMl + " 不能被 " + deliveryCount + " 整除");
        }
        // D-214 金额口径：payWay=2 的水费从余额扣（等于价目水费）；payWay=3 的水费从水量扣（余额侧恒 0）。
        // 这条恒等式若不成立，说明快照被改写或来自未授权创建路径，按哪一侧退都可能错。
        if (payWay == TradeEnum.PayWay.CARD_ML.getValue()) {
            if (waterAmountFen != 0L) {
                throw new JbkException("水量抵扣配送快照的余额侧水费必须为 0，实际 " + waterAmountFen);
            }
        } else if (waterAmountFen != priceWaterAmountFen) {
            throw new JbkException("余额支付配送快照水费(" + waterAmountFen
                    + ")与价目水费(" + priceWaterAmountFen + ")不一致");
        }
        long unitWaterPriceFen = unitFrom(snap, F_UNIT_WATER_PRICE_FEN,
                priceWaterAmountFen, deliveryCount, "配送快照单桶水费");
        long unitDeliveryFeeFen = unitFrom(snap, F_DELIVERY_FEE_PER_CONTAINER_FEN,
                deliveryFeeFen, deliveryCount, "配送快照单桶配送费");
        return new Parsed(payWay, waterAmountFen, deliveryFeeFen, waterMl, deliveryCount,
                unitWaterPriceFen, unitDeliveryFeeFen, priceWaterAmountFen);
    }

    /**
     * 单价 = 同一份快照的总额 ÷ 数量（必须整除）；快照若显式带了单价字段，必须与派生值精确相等。
     */
    private static long unitFrom(JSONObject snap, String field, long total, int count, String label) {
        if (total % count != 0) {
            throw new JbkException(label + "不自洽：总额 " + total + " 不能被数量 " + count + " 整除");
        }
        long derived = total / count;
        if (snap.containsKey(field) && snap.get(field) != null) {
            long declared = requireNonNegativeLong(snap, field, label);
            if (declared != derived) {
                throw new JbkException(label + "(" + declared + ")×数量(" + count
                        + ")与总额(" + total + ")不等，拒绝按不自洽快照返还");
            }
        }
        return derived;
    }

    /**
     * 严格整数读取：只认 JSON 整数。刻意不走 hutool 的 {@code getLong}——它会把
     * {@code "500"}、{@code 5.7} 宽容转数，而金额快照里出现字符串或小数即是被改写的信号。
     */
    private static long requireLong(JSONObject snap, String field, String label) {
        Object raw = snap.get(field);
        if (raw == null) {
            throw new JbkException(label + "缺失");
        }
        if (raw instanceof Integer || raw instanceof Long
                || raw instanceof Short || raw instanceof Byte) {
            return ((Number) raw).longValue();
        }
        try {
            if (raw instanceof BigInteger big) {
                return big.longValueExact();
            }
            if (raw instanceof BigDecimal dec) {
                return dec.longValueExact();
            }
        } catch (ArithmeticException overflow) {
            throw new JbkException(label + "越界或非整数");
        }
        throw new JbkException(label + "必须是 JSON 整数");
    }

    private static long requireNonNegativeLong(JSONObject snap, String field, String label) {
        long value = requireLong(snap, field, label);
        if (value < 0) {
            throw new JbkException(label + "不能为负：" + value);
        }
        return value;
    }

    private static int requireInt(JSONObject snap, String field, String label) {
        long value = requireLong(snap, field, label);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new JbkException(label + "越界");
        }
        return (int) value;
    }
}

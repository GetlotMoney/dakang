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
 * <p><b>为什么必须是快照而不是 {@link DeliveryPricing}</b>：{@code DeliveryPricing} 的价目表是
 * <b>当前</b>价目（其类注释已写明「正式价目待商业一期确认后只改本类常量」）。价目一旦调整，
 * 用它重算历史单会算出与原扣款不同的金额；而售后封顶的基准恰是原扣款流水——重算值高于原扣款
 * 会把正常单judge成超额（大批转人工），低于原扣款则少退用户的钱。下单事务内冻结的这份快照
 * 是历史价格的唯一真相。故 {@code service/aftersale/**} 明令禁止 import {@code DeliveryPricing}，
 * 只能经由本类取历史单价。</p>
 *
 * <p><b>刻意不暴露 containerSpec</b>：容器规格是当前价目表的查询键，一旦出现在返还入参里，
 * 下一个读者就会顺手用它去 {@code DeliveryPricing.requireUnitWaterPrice} 重算——上一段禁止的
 * 正是这件事。返还只需要「单价」本身，不需要「怎么查到单价」。</p>
 *
 * <p><b>单价的两条来源与它们为何等价</b>：{@code buildSnap}（用户下单）写了
 * {@code unitWaterPriceFen}/{@code deliveryFeePerContainerFen}，但 {@code generateOne}
 * （自动补货周期单）没写这两列——若在此处硬性要求字段存在，全部周期单将永远无法退款。
 * 本类的做法是：单价<b>恒定</b>由同一份快照的总额整除数量派生，字段存在时再与派生值逐一比对，
 * 不等即 fail-closed。既覆盖了两种快照形状，又顺带钉死了「单价×数量=总额」这条自洽式
 * （将来引入折扣/阶梯价而总额不再等于单价×数量时，这里会立刻拒绝而不是按错误单价退款）。
 * 全程只读快照自身，一次都没有碰价目表。</p>
 *
 * <p>本类只负责「快照自洽」。快照与订单总额、与 {@code ws_wallet_flow} 扣款流水的三方恒等
 * 属账本核验，单一出处在 {@code DeliveryLedgerVerifier}，此处不重复判定。</p>
 */
public final class DeliveryRefundSnapshot {

    // 字段名常量一律 private：本类存在的唯一理由就是「快照 JSON 只在这里解析一次」，
    // 把 key 名暴露出去等于给调用方递上自己 JSONUtil.parseObj 的钥匙，第二份解析逻辑
    // 会立刻长出来并与这里的校验口径漂移。需要新字段就在本类加访问器，不要外传 key。
    private static final String F_PAY_WAY = "payWay";
    private static final String F_WATER_AMOUNT_FEN = "waterAmountFen";
    private static final String F_DELIVERY_FEE_FEN = "deliveryFeeFen";
    private static final String F_WATER_ML = "waterMl";
    private static final String F_DELIVERY_COUNT = "deliveryCount";
    private static final String F_UNIT_WATER_PRICE_FEN = "unitWaterPriceFen";
    private static final String F_DELIVERY_FEE_PER_CONTAINER_FEN = "deliveryFeePerContainerFen";
    private static final String F_PRICE_WATER_AMOUNT_FEN = "priceWaterAmountFen";

    /**
     * 数量上界只作溢出闸，不复刻 {@code DeliveryPricing.MAX_COUNT}：
     * 那是「下单允许的最大数量」，会随业务调整；这里要的是「历史单再怎么离谱也不能让乘法回绕」。
     * 两者语义不同，用同一个常量会让改下单上限时静默改变历史单的可解析范围。
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
         * 单桶水量（毫升）。整除性已在解析期校验，此处不会有舍入：
         * 除不尽意味着「一桶水」在毫升维度没有确定值，按 q 桶返还必然与原扣款差出零头，
         * 这种争议只能在解析期堵死，绝不允许四舍五入后进资金链路。
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
     * 快照字段的唯一判定体。
     *
     * <p>曾另有一个「坏值返回 null」的容错版供只读预览用，因全仓无调用方已删除：
     * 那种宽松入口最终一定会被顺手用在写路径上，而金额与配额不许猜。
     * 将来若确需只读试算，在本方法之上另包一层捕获即可，判定体仍只此一份。</p>
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
     * 见类注释：这样既支持缺单价字段的自动补货快照，又把「单价×数量=总额」钉成硬不变式。
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
     * 严格整数读取：只认 JSON 整数（Integer/Long/BigInteger，或 scale≤0 的 BigDecimal）。
     * 刻意不走 hutool 的 {@code getLong}——它会把 {@code "500"}、{@code 5.7} 这类值宽容地转成数字，
     * 而一份本该由服务端生成的金额快照里出现字符串或小数，本身就是被改写过的信号。
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

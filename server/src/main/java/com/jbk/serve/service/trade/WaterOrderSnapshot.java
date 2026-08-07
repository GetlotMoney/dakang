package com.jbk.serve.service.trade;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jbk.tool.consts.trade.TradeEnum;
import com.jbk.tool.exception.JbkException;

import java.math.BigDecimal;

/**
 * 取水订单冻结快照（{@code ws_order.PACKAGE_SNAP}）的<b>唯一</b>构造与解析实现。
 *
 * <p>快照冻结的是「下单当时用户看到并认可的交易身份」：请求号、单价、计划水量、支付方式、水种。
 * 之所以必须集中在一处，是因为这几个字段在三个不同时点被读：下单扣款事务、出水指令准备、
 * 出水结算。三处各写一份 JSON 解析，就会各自漂移出宽严不一的口径——最先松掉的那一处
 * 就是绕过口。</p>
 *
 * <h3>两种读法，语义不同，不可互相替代</h3>
 * <ul>
 *   <li>{@link #parseStrict}：下单与指令准备用。任何字段缺失或非法一律 fail-closed，
 *       因为这两处正是要用快照去核对「现在的档案是不是还是当时那个交易」。</li>
 *   <li>{@link #readUnitPriceLenient}：结算用。上线前的历史单可能没有单价字段，
 *       缺失按既定回退口径（单价 0）并留痕；但字段<b>存在却非法</b>属篡改信号，必须抛出，
 *       绝不能用全额退款把它掩盖过去。</li>
 * </ul>
 *
 * @author dakang
 * @since 2026-08-03
 */
public final class WaterOrderSnapshot {

    private static final String KEY_REQUEST_ID = "requestId";
    private static final String KEY_UNIT_PRICE = "unitPriceFenPerLiter";
    private static final String KEY_PLAN_ML = "planMl";
    private static final String KEY_PAY_WAY = "payWay";
    private static final String KEY_WATER_TYPE_ID = "waterTypeId";

    private WaterOrderSnapshot() {
    }

    /**
     * 下单时点冻结的交易身份。
     *
     * <p>{@code waterTypeId} 用包装类型：{@link #parseStrict} 保证非空，
     * {@link #parseForIdempotency} 允许修复前未写该字段的历史快照为 null，由调用方按兼容口径回查。</p>
     */
    public record Frozen(String requestId, int unitPriceFenPerLiter, long planMl, int payWay, Long waterTypeId) {
    }

    /**
     * 结算侧的宽松单价读取结果。
     *
     * @param unitPriceFenPerLiter 采用的单价（分/升）
     * @param fallback             是否走了回退（true 时调用方必须留痕）
     * @param fallbackReason       回退原因（非回退时为 null）
     */
    public record UnitPriceRead(int unitPriceFenPerLiter, boolean fallback, String fallbackReason) {
    }

    /** 装配快照 JSON；字段名即本类常量，构造与解析共用同一套键名。 */
    public static String build(String requestId, int unitPriceFenPerLiter, long planMl,
                               int payWay, Long waterTypeId) {
        return JSONUtil.createObj()
                .set(KEY_REQUEST_ID, requestId)
                .set(KEY_UNIT_PRICE, unitPriceFenPerLiter)
                .set(KEY_PLAN_ML, planMl)
                .set(KEY_PAY_WAY, payWay)
                .set(KEY_WATER_TYPE_ID, waterTypeId)
                .toString();
    }

    /** 严格解析：任何缺失、结构损坏、非规范整数、越界一律抛出（fail-closed）。 */
    public static Frozen parseStrict(String snapshot) {
        Frozen frozen = parse(snapshot, true);
        if (ObjectUtil.isNull(frozen.waterTypeId())) {
            throw new JbkException("快照缺少 " + KEY_WATER_TYPE_ID);
        }
        return frozen;
    }

    /**
     * 幂等核验解析：规则与 {@link #parseStrict} 完全相同，只在一处放宽——
     * 允许水种冻结上线<b>之前</b>产生的历史快照没有 {@code waterTypeId}（返回 null 分量），
     * 由调用方按既定兼容口径回查订单已持久关联的出水口，绝不接受前端自报水种。
     */
    public static Frozen parseForIdempotency(String snapshot) {
        return parse(snapshot, false);
    }

    private static Frozen parse(String snapshot, boolean requireWaterType) {
        JSONObject json = requireJson(snapshot);
        String requestId = json.getStr(KEY_REQUEST_ID);
        if (StrUtil.isBlank(requestId)) {
            throw new JbkException("快照缺少 " + KEY_REQUEST_ID);
        }
        int unitPrice = WaterBillingMath.requireUnitPrice(requireIntValue(json, KEY_UNIT_PRICE));
        long planMl = WaterBillingMath.requirePlanMl(requireIntegral(json, KEY_PLAN_ML));
        int payWay = requireIntValue(json, KEY_PAY_WAY);
        if (payWay != TradeEnum.PayWay.CARD_BALANCE.getValue()
                && payWay != TradeEnum.PayWay.CARD_ML.getValue()) {
            throw new JbkException("快照 " + KEY_PAY_WAY + " 不是受支持的取水支付方式");
        }
        Long waterTypeId = null;
        if (requireWaterType || json.containsKey(KEY_WATER_TYPE_ID)) {
            waterTypeId = requireIntegral(json, KEY_WATER_TYPE_ID);
            if (waterTypeId <= 0) {
                throw new JbkException("快照 " + KEY_WATER_TYPE_ID + " 非法");
            }
        }
        return new Frozen(requestId, unitPrice, planMl, payWay, waterTypeId);
    }

    /**
     * 按冻结快照推导应扣金额：余额支付 ceil(水量×单价÷1000)，水量支付记 0。
     * 下单事务与幂等核验都用这一份，避免两处各算一遍后对不上。
     */
    public static long expectedOrderAmount(Frozen frozen) {
        return frozen.payWay() == TradeEnum.PayWay.CARD_BALANCE.getValue()
                ? WaterBillingMath.ceilAmount(frozen.planMl(), frozen.unitPriceFenPerLiter())
                : 0L;
    }

    /**
     * 结算侧单价读取：缺失走回退，存在但非法抛出。
     *
     * @throws JbkException 单价字段存在但为负数、小数、非规范整数或越界
     */
    public static UnitPriceRead readUnitPriceLenient(String snapshot) {
        if (StrUtil.isBlank(snapshot)) {
            return fallback("结算单价快照缺失");
        }
        JSONObject json;
        try {
            json = JSONUtil.parseObj(snapshot);
        }
        catch (Exception malformed) {
            return fallback("结算单价快照不是合法 JSON 对象");
        }
        Object raw;
        try {
            raw = json.get(KEY_UNIT_PRICE);
        }
        catch (Exception malformed) {
            return fallback("结算单价快照不是合法 JSON 对象");
        }
        if (raw == null) {
            return fallback("结算单价字段缺失");
        }
        try {
            long value = normalizeIntegral(raw, KEY_UNIT_PRICE, true);
            return new UnitPriceRead(WaterBillingMath.requireUnitPrice(toIntExact(value, KEY_UNIT_PRICE)),
                    false, null);
        }
        catch (ArithmeticException | JbkException e) {
            String reason = e instanceof JbkException ? ((JbkException) e).getMsg() : e.getMessage();
            throw new JbkException("结算单价快照数值非法：" + reason);
        }
    }

    private static UnitPriceRead fallback(String reason) {
        return new UnitPriceRead(0, true, reason);
    }

    private static JSONObject requireJson(String snapshot) {
        if (StrUtil.isBlank(snapshot)) {
            throw new JbkException("快照缺失");
        }
        try {
            return JSONUtil.parseObj(snapshot);
        }
        catch (Exception malformed) {
            throw new JbkException("快照不是合法 JSON 对象");
        }
    }

    /**
     * 取 int 字段。越界必须转成 JbkException——裸 ArithmeticException 会穿过调用方仅有的
     * {@code catch (JbkException)}，把一次「篡改被拒」变成没有审计留痕的 500。
     */
    private static int requireIntValue(JSONObject json, String key) {
        return toIntExact(requireIntegral(json, key), key);
    }

    private static int toIntExact(long value, String key) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new JbkException("快照 " + key + " 超出取值范围");
        }
        return (int) value;
    }

    private static long requireIntegral(JSONObject json, String key) {
        Object raw;
        try {
            raw = json.get(key);
        }
        catch (Exception malformed) {
            throw new JbkException("快照不是合法 JSON 对象");
        }
        if (raw == null) {
            throw new JbkException("快照缺少 " + key);
        }
        try {
            return normalizeIntegral(raw, key, false);
        }
        catch (ArithmeticException e) {
            throw new JbkException("快照 " + key + " 非法：" + e.getMessage());
        }
    }

    /**
     * 规范整数判定：不得带小数位。字符串形态的「01」「1.0」「1e3」「 1 」一律拒绝——
     * 宽松解析等于给篡改留编码空间。JSON 数字形态的科学计数（{@code 5e3}）解析后等值于 5000
     * 且无小数位，按整数接受：它表达的仍是同一个整数值，拒绝它只会误伤合法报文。
     *
     * @param allowNumericString 是否接受纯十进制字符串。严格路径（下单/指令/幂等）传 false，
     *                           只认 JSON 数字类型；结算侧的历史行读取传 true，保留既有容忍度，
     *                           收紧它会让存量订单直接结算不了——那是比容忍更坏的结果。
     */
    private static long normalizeIntegral(Object raw, String key, boolean allowNumericString) {
        BigDecimal decimal;
        if (raw instanceof Number) {
            decimal = new BigDecimal(raw.toString());
        }
        else if (allowNumericString && raw instanceof String && ((String) raw).matches("^(?:0|[1-9]\\d*)$")) {
            decimal = new BigDecimal((String) raw);
        }
        else {
            throw new ArithmeticException(key + " 不是规范整数");
        }
        if (decimal.stripTrailingZeros().scale() > 0) {
            throw new ArithmeticException(key + " 包含小数");
        }
        return decimal.longValueExact();
    }
}

package com.jbk.serve.service.mini.recharge;

import com.jbk.tool.data.product.po.WsPackage;
import com.jbk.tool.exception.JbkException;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * 创建充值订单的输入上限与溢出校验（L2 契约 §4.4）。
 *
 * <p>这些是 v2 合同值；调整必须重新评审契约与测试，不能只改前端。</p>
 */
public final class RechargeLimits {

    public static final int NAME_MIN = 1;
    public static final int NAME_MAX = 50;
    /** 售价：正整数分，最高 10,000 元。 */
    public static final long PAY_AMOUNT_MIN = 1L;
    public static final long PAY_AMOUNT_MAX = 1_000_000L;
    /** 水量：非负整数毫升，最高 50,000 升。 */
    public static final long WATER_ML_MAX = 50_000_000L;
    /** 赠送余额：非负整数分，最高 10,000 元。 */
    public static final long BONUS_MAX = 1_000_000L;
    /** 单价上限：100000 分/升。 */
    public static final BigDecimal UNIT_PRICE_MAX = new BigDecimal("100000");
    /** 有限套餐有效期上限。 */
    public static final int EXPIRE_DAYS_MAX = 3650;

    /** 最多两位小数的非负十进制（禁科学计数法）。 */
    private static final Pattern UNIT_PRICE = Pattern.compile("^\\d+(\\.\\d{1,2})?$");

    private RechargeLimits() {
    }

    /**
     * 校验套餐全部数值合规。任一越界/畸形一律拒绝（fail-closed），不做截断或纠正。
     */
    public static void validatePackage(WsPackage pkg) {
        if (pkg == null) {
            throw new JbkException("套餐不存在或已下架");
        }
        validateValues(pkg.getPackageName(), pkg.getPayAmount(), pkg.getWaterMl(),
                pkg.getBonusAmount() == null ? 0L : pkg.getBonusAmount(),
                pkg.getUnitPriceSnap(), pkg.getExpireDays());
        rejectPaidLimitedExpiry(pkg.getPayAmount(), pkg.getExpireDays());
    }

    /**
     * D-213：付费购买的水卡一律永久有效，EXPIRE_DAYS 机制仅用于活动赠卡（走独立发卡服务）。
     * 守卫刻意只加在创建/售卖路径、不加在 {@link #validateSnapshotValues}——
     * 后者用于历史订单快照回放与入账，历史有限期数据不应因新规则无法读取或结算。
     */
    private static void rejectPaidLimitedExpiry(Long payAmount, Integer expireDays) {
        if (payAmount != null && payAmount > 0L && expireDays != null) {
            // 文案不带决策编号：消息原样弹给运营，内部编号只会被当成系统故障
            throw new JbkException("付费套餐不得设置有效期，付费水卡一律永久有效");
        }
    }

    /**
     * 校验持久快照中的套餐字段。调用方必须先完成 JSON 严格类型检查，禁止把字符串数字转换后传入。
     */
    static void validateSnapshotValues(String packageName, long payAmount, long waterMl,
                                       long bonusAmount, String unitPriceSnap, Integer expireDays) {
        validateValues(packageName, payAmount, waterMl, bonusAmount, unitPriceSnap, expireDays);
    }

    private static void validateValues(String packageName, Long payAmount, Long waterMl,
                                       Long bonusAmount, String unitPriceSnap, Integer expireDays) {
        String name = packageName == null ? "" : packageName.trim();
        if (name.length() < NAME_MIN || name.length() > NAME_MAX) {
            throw new JbkException("套餐名称长度非法");
        }
        long pay = requireNonNull(payAmount, "套餐售价");
        if (pay < PAY_AMOUNT_MIN || pay > PAY_AMOUNT_MAX) {
            throw new JbkException("套餐售价超出允许范围");
        }
        long water = requireNonNull(waterMl, "套餐水量");
        if (water < 0 || water > WATER_ML_MAX) {
            throw new JbkException("套餐水量超出允许范围");
        }
        long bonus = requireNonNull(bonusAmount, "套餐赠送余额");
        if (bonus < 0 || bonus > BONUS_MAX) {
            throw new JbkException("套餐赠送余额超出允许范围");
        }
        validateUnitPrice(unitPriceSnap, water);
        Integer days = expireDays;
        if (days != null && (days <= 0 || days > EXPIRE_DAYS_MAX)) {
            throw new JbkException("套餐有效期天数非法");
        }
        // Long 溢出必须在写库前拒绝。
        try {
            Math.addExact(pay, bonus);
        } catch (ArithmeticException e) {
            throw new JbkException("套餐金额溢出");
        }
        // 水量套餐与纯金额套餐算出的两个 credit 不得同时为 0（§2.3）。
        long mlCredit = water > 0 ? water : 0L;
        long amountCredit = water > 0 ? bonus : Math.addExact(pay, bonus);
        if (mlCredit == 0L && amountCredit == 0L) {
            throw new JbkException("套餐权益为空，拒绝创建订单");
        }
    }

    /** 单价快照：无损解析、最多两位小数、不超上限；水量套餐必须 >0，纯金额套餐固定为 0。 */
    private static void validateUnitPrice(String snap, long waterMl) {
        if (snap == null || !UNIT_PRICE.matcher(snap.trim()).matches()) {
            throw new JbkException("套餐单价快照格式非法");
        }
        BigDecimal price = new BigDecimal(snap.trim());
        if (price.compareTo(UNIT_PRICE_MAX) > 0) {
            throw new JbkException("套餐单价超出允许范围");
        }
        if (waterMl > 0 && price.signum() <= 0) {
            throw new JbkException("水量套餐单价必须大于 0");
        }
        if (waterMl == 0 && price.signum() != 0) {
            throw new JbkException("纯金额套餐单价必须为 0");
        }
    }

    private static long requireNonNull(Long v, String label) {
        if (v == null) {
            throw new JbkException(label + "缺失");
        }
        return v;
    }
}

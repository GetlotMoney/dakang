package com.jbk.serve.service.mini.recharge;

import com.jbk.serve.service.mini.card.WaterCardScope;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.jbk.tool.data.product.po.WsPackage;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 版本化套餐快照 PACKAGE_SNAP v2（L2 契约 §4.3）。
 *
 * <p>所有值均来自创建时的服务端 {@code ws_package} 与同一次读取的目标卡，
 * **任何值都不得来自前端或支付报文**。幂等命中时逐字段复核，绝不用套餐当前价格重建历史快照。</p>
 */
public final class RechargeSnapshot {

    public static final String SCHEMA_VERSION = "L2_V2";
    /** 通过创单校验的卡状态固定为 1 正常。 */
    public static final int CARD_STATUS_AT_CREATE = 1;
    /** 首次购卡快照的 purchaseMode 固定值（决策 A7）。 */
    public static final String PURCHASE_MODE_FIRST_CARD = "FIRST_CARD";
    /** 首次购卡计划卡类型固定 1 虚拟卡（决策 A5：一期 L2-A 固定建虚拟卡）。 */
    public static final int PLANNED_CARD_TYPE_VIRTUAL = 1;

    private static final String F_SCHEMA = "schemaVersion";
    private static final String F_REQUEST_ID = "requestId";
    private static final String F_PACKAGE_ID = "packageId";
    private static final String F_PACKAGE_NAME = "packageName";
    private static final String F_PAY_AMOUNT = "payAmount";
    private static final String F_WATER_ML = "waterMl";
    private static final String F_BONUS = "bonusAmount";
    private static final String F_UNIT_PRICE = "unitPriceSnap";
    private static final String F_EXPIRE_DAYS = "expireDays";
    private static final String F_PKG_SCOPE = "packageScopeSnapshot";
    private static final String F_CARD_SCOPE = "targetCardScopeSnapshot";
    private static final String F_ELIGIBILITY = "targetCardEligibilitySnapshot";
    private static final String F_CARD_STATUS_AT_CREATE = "cardStatusAtCreate";
    private static final String F_EXPIRE_AT_CREATE = "expireTimeAtCreate";
    private static final String F_CAPTURED_TIME = "capturedTime";
    private static final String F_PURCHASE_MODE = "purchaseMode";
    private static final String F_PLANNED_CARD_TYPE = "plannedCardType";
    private static final String F_USER_HAD_NO_CARD = "userHadNoCard";

    private static final Set<String> SNAPSHOT_FIELDS = Set.of(
            F_SCHEMA, F_REQUEST_ID, F_PACKAGE_ID, F_PACKAGE_NAME, F_PAY_AMOUNT, F_WATER_ML,
            F_BONUS, F_UNIT_PRICE, F_EXPIRE_DAYS, F_PKG_SCOPE, F_CARD_SCOPE, F_ELIGIBILITY);
    private static final Set<String> ELIGIBILITY_FIELDS = Set.of(
            F_CARD_STATUS_AT_CREATE, F_EXPIRE_AT_CREATE, F_CAPTURED_TIME);
    /** purchase 快照 = L2-B 基础字段 + purchaseMode + plannedCardType（决策 A7），仍是严格封闭集合。 */
    private static final Set<String> PURCHASE_SNAPSHOT_FIELDS;
    /** purchase 资格快照只有「当时无卡」与采集时间：没有目标卡，就不存在 cardStatusAtCreate/expireTimeAtCreate。 */
    private static final Set<String> PURCHASE_ELIGIBILITY_FIELDS = Set.of(
            F_USER_HAD_NO_CARD, F_CAPTURED_TIME);

    static {
        java.util.HashSet<String> purchase = new java.util.HashSet<>(SNAPSHOT_FIELDS);
        purchase.add(F_PURCHASE_MODE);
        purchase.add(F_PLANNED_CARD_TYPE);
        PURCHASE_SNAPSHOT_FIELDS = Set.copyOf(purchase);
    }
    private static final Pattern POSITIVE_DECIMAL_ID = Pattern.compile("^[1-9]\\d*$");

    private RechargeSnapshot() {
    }

    /**
     * 构建快照。
     *
     * @param cardScope 目标卡范围的规范化结果（授权判定依据）
     * @param pkgScope  套餐范围的规范化结果；套餐范围为空时传 {@code null}（不增加新约束）
     */
    public static String build(String requestId, WsPackage pkg, WsCard card,
                               WaterCardScope cardScope, WaterCardScope pkgScope, String createTime) {
        RechargeOrderNo.requireCanonicalUuid(requestId);
        RechargeLimits.validatePackage(pkg);
        JSONObject eligibility = new JSONObject(true);
        eligibility.put(F_CARD_STATUS_AT_CREATE, CARD_STATUS_AT_CREATE);
        // 永久卡为 null；有限卡是创建时刻的卡有效期。
        eligibility.put(F_EXPIRE_AT_CREATE, blankToNull(card.getExpireTime()));
        // 必须等于订单 createTime（同一服务端时刻）。
        eligibility.put(F_CAPTURED_TIME, createTime);

        JSONObject snap = new JSONObject(true);
        snap.put(F_SCHEMA, SCHEMA_VERSION);
        snap.put(F_REQUEST_ID, requestId);
        snap.put(F_PACKAGE_ID, String.valueOf(pkg.getId()));
        snap.put(F_PACKAGE_NAME, pkg.getPackageName());
        snap.put(F_PAY_AMOUNT, pkg.getPayAmount());
        snap.put(F_WATER_ML, pkg.getWaterMl());
        snap.put(F_BONUS, pkg.getBonusAmount() == null ? 0L : pkg.getBonusAmount());
        snap.put(F_UNIT_PRICE, pkg.getUnitPriceSnap());
        snap.put(F_EXPIRE_DAYS, pkg.getExpireDays());
        snap.put(F_PKG_SCOPE, pkgScope == null ? null : pkgScope.toCanonicalJson());
        snap.put(F_CARD_SCOPE, cardScope.toCanonicalJson());
        snap.put(F_ELIGIBILITY, eligibility);
        // v2 的 nullable 字段也是完整合同的一部分；必须显式序列化 null，避免 build 产物被 strict parse
        // 判成缺字段，也避免「字段缺失」与「明确为永久/不附加范围」混成同一语义。
        return JSON.toJSONString(snap, SerializerFeature.WriteMapNullValue);
    }

    /**
     * 构建首次购卡（purchase）快照（决策 A2/A6/A7）。
     *
     * <p>与 {@link #build} 的差别：没有目标卡，因此 {@code targetCardScopeSnapshot} 直接写
     * 套餐范围——新卡精确继承创单时冻结的套餐范围（决策 A6），发卡事务只认这份快照，
     * 不回读套餐当前值；资格快照只冻结「当时无卡」事实与采集时间。</p>
     *
     * @param pkgScope 套餐范围的规范化结果；决策 A6 要求必须非空（空=未配置默认拒绝，由创单层先行拦截）
     */
    public static String buildForPurchase(String requestId, WsPackage pkg,
                                          WaterCardScope pkgScope, String createTime) {
        RechargeOrderNo.requireCanonicalUuid(requestId);
        RechargeLimits.validatePackage(pkg);
        if (pkgScope == null) {
            // 防御：创单层应已拒绝空范围套餐；这里再拦一道，绝不落一份「新卡无范围」的快照
            throw new JbkException("首次购卡套餐未配置可用范围，拒绝生成快照");
        }
        JSONObject eligibility = new JSONObject(true);
        // 发卡资格快照（决策 A4/A7）：创单时用户名下没有任何 DATA_STATUS=0 的卡
        eligibility.put(F_USER_HAD_NO_CARD, Boolean.TRUE);
        eligibility.put(F_CAPTURED_TIME, createTime);

        JSONObject snap = new JSONObject(true);
        snap.put(F_SCHEMA, SCHEMA_VERSION);
        snap.put(F_REQUEST_ID, requestId);
        snap.put(F_PACKAGE_ID, String.valueOf(pkg.getId()));
        snap.put(F_PACKAGE_NAME, pkg.getPackageName());
        snap.put(F_PAY_AMOUNT, pkg.getPayAmount());
        snap.put(F_WATER_ML, pkg.getWaterMl());
        snap.put(F_BONUS, pkg.getBonusAmount() == null ? 0L : pkg.getBonusAmount());
        snap.put(F_UNIT_PRICE, pkg.getUnitPriceSnap());
        snap.put(F_EXPIRE_DAYS, pkg.getExpireDays());
        snap.put(F_PURCHASE_MODE, PURCHASE_MODE_FIRST_CARD);
        snap.put(F_PLANNED_CARD_TYPE, PLANNED_CARD_TYPE_VIRTUAL);
        snap.put(F_PKG_SCOPE, pkgScope.toCanonicalJson());
        // 新卡精确继承套餐范围：cardScope 与 packageScope 在 purchase 快照里语义相同
        snap.put(F_CARD_SCOPE, pkgScope.toCanonicalJson());
        snap.put(F_ELIGIBILITY, eligibility);
        return JSON.toJSONString(snap, SerializerFeature.WriteMapNullValue);
    }

    /**
     * 已落库快照的强类型视图（解析失败即视为快照错位，拒绝）。
     *
     * <p>{@code purchaseMode}：purchase 快照返回 {@code "FIRST_CARD"}，L2-B 充值快照返回 {@code null}——
     * 调用方以此分流两种模式。purchase 快照的 {@code cardStatusAtCreate}/{@code expireTimeAtCreate}
     * 为 {@code null}（创单时根本没有卡）；L2-B 快照的 {@code cardStatusAtCreate} 恒为 1。</p>
     */
    public record Parsed(String schemaVersion, String requestId, String packageId, String packageName,
                         long payAmount, long waterMl, long bonusAmount, String unitPriceSnap,
                         Integer expireDays, WaterCardScope cardScope, WaterCardScope packageScope,
                         Integer cardStatusAtCreate, String expireTimeAtCreate, String capturedTime,
                         String purchaseMode, Integer plannedCardType) {
    }

    /**
     * 严格解析已落库快照。缺字段、版本不符、结构错位一律拒绝——
     * 幂等复核宁可拒绝也不"尽力理解"一份自身不自洽的历史快照。
     */
    public static Parsed parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new JbkException("订单快照缺失");
        }
        JSONObject o;
        try {
            Object parsed = JSON.parse(json);
            if (!(parsed instanceof JSONObject)) {
                throw new JbkException("订单快照结构非法");
            }
            o = (JSONObject) parsed;
        } catch (JbkException e) {
            throw e;
        } catch (Exception e) {
            throw new JbkException("订单快照不是合法 JSON");
        }
        // 按 purchaseMode 是否出现分流两套严格封闭字段集（决策 A7）。
        // 分流不放松任何一侧：L2-B 快照混入 purchase 字段会因「资格快照字段不符」被拒，
        // purchase 快照缺 plannedCardType 或带 cardStatusAtCreate 同样被拒。
        boolean purchase = o.containsKey(F_PURCHASE_MODE);
        requireExactFields(o, purchase ? PURCHASE_SNAPSHOT_FIELDS : SNAPSHOT_FIELDS, "订单快照");
        String schemaVersion = requireString(o, F_SCHEMA, "订单快照版本");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new JbkException("订单快照版本不符，拒绝按当前合同解释");
        }
        String requestId = requireString(o, F_REQUEST_ID, "订单快照 requestId");
        RechargeOrderNo.requireCanonicalUuid(requestId);
        String packageId = requireString(o, F_PACKAGE_ID, "订单快照 packageId");
        requirePositiveDecimalId(packageId);
        String packageName = requireString(o, F_PACKAGE_NAME, "订单快照套餐名称");

        long pay = requireInteger(o, F_PAY_AMOUNT, "订单快照售价");
        long water = requireInteger(o, F_WATER_ML, "订单快照水量");
        long bonus = requireInteger(o, F_BONUS, "订单快照赠送余额");
        String unitPrice = requireString(o, F_UNIT_PRICE, "订单快照单价");
        Integer expireDays = requireNullableInteger(o, F_EXPIRE_DAYS, "订单快照有效期天数");

        String purchaseMode = null;
        Integer plannedCardType = null;
        Integer statusAtCreate = null;
        String expireAtCreate = null;
        String capturedTime;
        JSONObject eligibility = requireObject(o, F_ELIGIBILITY, "订单快照目标卡资格");
        if (purchase) {
            purchaseMode = requireString(o, F_PURCHASE_MODE, "订单快照购卡模式");
            if (!PURCHASE_MODE_FIRST_CARD.equals(purchaseMode)) {
                throw new JbkException("订单快照购卡模式非法，拒绝");
            }
            plannedCardType = requireInt(o, F_PLANNED_CARD_TYPE, "订单快照计划卡类型");
            // 决策 A5：一期 L2-A 只发虚拟卡；其它值说明快照被改写或来自未授权路径
            if (plannedCardType != PLANNED_CARD_TYPE_VIRTUAL) {
                throw new JbkException("订单快照计划卡类型非法，拒绝");
            }
            requireExactFields(eligibility, PURCHASE_ELIGIBILITY_FIELDS, "订单快照发卡资格");
            Object hadNoCard = eligibility.get(F_USER_HAD_NO_CARD);
            // 发卡资格快照的唯一合法事实是「创单时无卡」；false 或非布尔值都是被篡改的信号
            if (!Boolean.TRUE.equals(hadNoCard)) {
                throw new JbkException("订单快照发卡资格非法，拒绝");
            }
            capturedTime = requireString(eligibility, F_CAPTURED_TIME, "订单快照采集时间");
        } else {
            requireExactFields(eligibility, ELIGIBILITY_FIELDS, "订单快照目标卡资格");
            statusAtCreate = requireInt(eligibility, F_CARD_STATUS_AT_CREATE, "订单快照资格状态");
            capturedTime = requireString(eligibility, F_CAPTURED_TIME, "订单快照采集时间");
            expireAtCreate = requireNullableString(
                    eligibility, F_EXPIRE_AT_CREATE, "订单快照创建时卡有效期");
            // §4.3：cardStatusAtCreate 固定为通过创单校验的 1；其它值说明快照被改写或来自非法创建路径
            if (statusAtCreate != CARD_STATUS_AT_CREATE) {
                throw new JbkException("订单快照资格状态非法，拒绝");
            }
        }
        JSONObject cardScopeJson = requireObject(o, F_CARD_SCOPE, "订单快照目标卡范围");
        WaterCardScope cardScope = WaterCardScope.normalize(cardScopeJson.toJSONString(), "订单快照目标卡");
        JSONObject pkgScopeJson = requireNullableObject(o, F_PKG_SCOPE, "订单快照套餐范围");
        WaterCardScope pkgScope = pkgScopeJson == null
                ? null
                : WaterCardScope.normalize(pkgScopeJson.toJSONString(), "订单快照套餐");

        RechargeLimits.validateSnapshotValues(
                packageName, pay, water, bonus, unitPrice, expireDays);
        if (purchase) {
            // 决策 A6：新卡精确继承套餐范围。purchase 快照必须带非空套餐范围，
            // 且 targetCardScopeSnapshot（新卡初始范围）与之语义精确相等——不一致即快照被改写。
            if (pkgScope == null) {
                throw new JbkException("首次购卡快照缺少套餐范围，拒绝");
            }
            if (!pkgScope.sameAuthorityAs(cardScope)) {
                throw new JbkException("首次购卡快照新卡范围与套餐范围不一致，拒绝");
            }
        } else if ((expireDays == null) != (expireAtCreate == null)) {
            // 该交叉校验只属于 L2-B（卡与套餐有效期类型必须一致）；purchase 没有既有卡，不适用
            throw new JbkException("订单快照套餐与目标卡有效期类型不一致");
        }
        return new Parsed(SCHEMA_VERSION, requestId, packageId, packageName,
                pay, water, bonus, unitPrice, expireDays,
                cardScope, pkgScope, statusAtCreate, expireAtCreate, capturedTime,
                purchaseMode, plannedCardType);
    }

    private static void requireExactFields(JSONObject object, Set<String> expected, String label) {
        for (String field : expected) {
            if (!object.containsKey(field)) {
                throw new JbkException(label + "缺少字段 " + field);
            }
        }
        for (String field : object.keySet()) {
            if (!expected.contains(field)) {
                throw new JbkException(label + "含未知字段 " + field);
            }
        }
    }

    private static String requireString(JSONObject object, String field, String label) {
        Object raw = object.get(field);
        if (!(raw instanceof String value) || value.isEmpty()) {
            throw new JbkException(label + "必须是非空字符串");
        }
        return value;
    }

    private static String requireNullableString(JSONObject object, String field, String label) {
        Object raw = object.get(field);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String value) || value.isEmpty()) {
            throw new JbkException(label + "只能为 null 或非空字符串");
        }
        return value;
    }

    private static long requireInteger(JSONObject object, String field, String label) {
        Object raw = object.get(field);
        if (!(raw instanceof Integer) && !(raw instanceof Long)) {
            throw new JbkException(label + "必须是 JSON 整数");
        }
        return ((Number) raw).longValue();
    }

    private static int requireInt(JSONObject object, String field, String label) {
        Object raw = object.get(field);
        if (!(raw instanceof Integer value)) {
            throw new JbkException(label + "必须是 JSON 整数");
        }
        return value;
    }

    private static Integer requireNullableInteger(JSONObject object, String field, String label) {
        Object raw = object.get(field);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Integer value)) {
            throw new JbkException(label + "只能为 null 或 JSON 整数");
        }
        return value;
    }

    private static JSONObject requireObject(JSONObject object, String field, String label) {
        Object raw = object.get(field);
        if (!(raw instanceof JSONObject value)) {
            throw new JbkException(label + "必须是 JSON 对象");
        }
        return value;
    }

    private static JSONObject requireNullableObject(JSONObject object, String field, String label) {
        Object raw = object.get(field);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof JSONObject value)) {
            throw new JbkException(label + "只能为 null 或 JSON 对象");
        }
        return value;
    }

    private static void requirePositiveDecimalId(String packageId) {
        if (!POSITIVE_DECIMAL_ID.matcher(packageId).matches()) {
            throw new JbkException("订单快照 packageId 必须是规范正十进制字符串");
        }
        try {
            Long.parseLong(packageId);
        } catch (NumberFormatException e) {
            throw new JbkException("订单快照 packageId 超出 Long 范围");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}

package com.jbk.serve.service.mini.recharge;

import com.jbk.serve.service.mini.card.WaterCardScope;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.jbk.tool.data.product.po.WsPackage;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PACKAGE_SNAP v2 的构建与严格解析（L2 契约 §4.3）。
 * 快照是幂等复核与后续退款折算的唯一历史依据：解析一旦"尽力理解"，
 * 一份被改写或跨版本的快照就会被当成合法历史，按错误的价格/范围二次入账。
 */
class RechargeSnapshotParseTest {

    private static final String REQUEST_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String CREATE_TIME = "2026-07-21 10:00:00";
    private static final String F_ELIGIBILITY = "targetCardEligibilitySnapshot";

    private WsPackage pkg() {
        return new WsPackage()
                .setId(3L)
                .setPackageName("季卡 100L")
                .setPayAmount(9900L)
                .setWaterMl(100_000L)
                .setBonusAmount(500L)
                .setUnitPriceSnap("20.00")
                .setPackageStatus(1)
                .setExpireDays(null);
    }

    private WsCard card(String expireTime) {
        WsCard c = new WsCard();
        c.setId(100L);
        c.setUserId(9L);
        c.setCardStatus(1);
        c.setScopeJson("{\"scopeType\":\"specified\",\"stationIds\":[1]}");
        c.setExpireTime(expireTime);
        return c;
    }

    private WaterCardScope scope(String json) {
        return WaterCardScope.normalize(json, "水卡");
    }

    private WaterCardScope cardScope() {
        return scope("{\"scopeType\":\"specified\",\"stationIds\":[1]}");
    }

    /** 合法基线快照：永久卡 + 套餐范围为空（套餐不加新约束）。 */
    private String validSnap() {
        return RechargeSnapshot.build(REQUEST_ID, pkg(), card(null), cardScope(), null, CREATE_TIME);
    }

    /** 在合法基线上做一处篡改，用来证明每条守卫都真的在拦。 */
    private String tampered(Consumer<JSONObject> mutator) {
        JSONObject o = JSON.parseObject(validSnap());
        mutator.accept(o);
        return o.toJSONString();
    }

    private void rejectSnap(String json, String why) {
        assertThrows(JbkException.class, () -> RechargeSnapshot.parse(json), why);
    }

    /**
     * 断言拒绝理由。三类输入（空/结构错/非 JSON）的兜底路径彼此相邻，
     * 只断言"抛异常"时任何一条守卫失效都会被相邻兜底掩盖，排障时看到的原因也会指错方向。
     */
    private void rejectSnapBecause(String json, String expectMsgPart, String why) {
        JbkException e = assertThrows(JbkException.class, () -> RechargeSnapshot.parse(json), why);
        assertEquals(expectMsgPart, e.getMsg(), why + "：拒绝理由不符");
    }

    private void rejectTampered(String why, Consumer<JSONObject> mutator) {
        rejectSnap(tampered(mutator), why);
    }

    private void mutateEligibility(JSONObject o, Consumer<JSONObject> mutator) {
        JSONObject e = o.getJSONObject(F_ELIGIBILITY);
        mutator.accept(e);
        o.put(F_ELIGIBILITY, e);
    }

    // build → parse 必须逐字段无损；任何一个字段在往返中走样，
    // 幂等复核就会把同一笔请求判成"参数变了"或反过来放过一笔被改过的请求
    @Test
    void roundTripPreservesEveryField() {
        String json = validSnap();
        JSONObject raw = JSON.parseObject(json);
        assertTrue(raw.containsKey("expireDays"));
        assertNull(raw.get("expireDays"));
        assertTrue(raw.containsKey("packageScopeSnapshot"));
        assertNull(raw.get("packageScopeSnapshot"));
        JSONObject rawEligibility = raw.getJSONObject(F_ELIGIBILITY);
        assertTrue(rawEligibility.containsKey("expireTimeAtCreate"));
        assertNull(rawEligibility.get("expireTimeAtCreate"));

        RechargeSnapshot.Parsed p = RechargeSnapshot.parse(json);
        assertEquals(RechargeSnapshot.SCHEMA_VERSION, p.schemaVersion());
        assertEquals(REQUEST_ID, p.requestId());
        assertEquals("3", p.packageId(), "套餐 ID 必须以字符串形态无损带回");
        assertEquals("季卡 100L", p.packageName());
        assertEquals(9900L, p.payAmount());
        assertEquals(100_000L, p.waterMl());
        assertEquals(500L, p.bonusAmount());
        assertEquals("20.00", p.unitPriceSnap(), "单价快照是退款折算依据，必须原样带回");
        assertNull(p.expireDays());
        assertEquals(RechargeSnapshot.CARD_STATUS_AT_CREATE, p.cardStatusAtCreate());
        assertEquals(CREATE_TIME, p.capturedTime(), "capturedTime 必须等于创单时刻");
        assertNull(p.expireTimeAtCreate(), "永久卡在快照里必须是 null，不能变成空串再被当成有到期日");
        assertNotNull(p.cardScope());
        assertTrue(p.cardScope().sameAuthorityAs(cardScope()), "目标卡授权范围必须无损往返");
        assertNull(p.packageScope(), "套餐范围为空表示不加新约束，parse 后必须仍是 null");
    }

    // 赠送额 null 必须归 0：留成 null 会让下游按 0 与按"未知"两种口径分叉
    @Test
    void nullBonusNormalizedToZero() {
        String json = RechargeSnapshot.build(REQUEST_ID, pkg().setBonusAmount(null), card(null),
                cardScope(), null, CREATE_TIME);
        assertEquals(0L, RechargeSnapshot.parse(json).bonusAmount(), "赠送额缺省必须归 0");
    }

    // 有限期卡的到期时刻必须原样冻结在快照里，后续卡被续期也不能改变这笔订单的资格判定
    @Test
    void limitedCardExpireTimeCaptured() {
        String json = LegacySnapshots.forgeExpireDays(
                RechargeSnapshot.build(REQUEST_ID, pkg(), card("2026-12-31 23:59:59"),
                        cardScope(), null, CREATE_TIME), 90);
        assertEquals("2026-12-31 23:59:59", RechargeSnapshot.parse(json).expireTimeAtCreate());
    }

    // 套餐范围存在时也要无损往返，否则套餐侧的额外约束会在复核时凭空消失
    @Test
    void packageScopeRoundTripsWhenPresent() {
        WaterCardScope pkgScope = scope("{\"scopeType\":\"specified\",\"stationIds\":[1,2]}");
        String json = RechargeSnapshot.build(REQUEST_ID, pkg(), card(null), cardScope(), pkgScope, CREATE_TIME);
        RechargeSnapshot.Parsed p = RechargeSnapshot.parse(json);
        assertNotNull(p.packageScope());
        assertTrue(p.packageScope().sameAuthorityAs(pkgScope), "套餐范围必须无损往返");
    }

    // 空/畸形输入必须当场拒绝：容忍它们等于允许一条没有快照的订单走幂等复核
    @Test
    void blankOrMalformedRejected() {
        rejectSnapBecause(null, "订单快照缺失", "快照为 null");
        rejectSnapBecause("", "订单快照缺失", "空串");
        rejectSnapBecause("   ", "订单快照缺失", "纯空格");
        rejectSnap("not-json", "非 JSON");
        rejectSnapBecause("[1,2]", "订单快照结构非法", "JSON 数组不是快照对象");
        rejectSnapBecause("\"L2_V2\"", "订单快照结构非法", "JSON 字符串标量不是快照对象");
        rejectSnapBecause("123", "订单快照结构非法", "JSON 数字标量不是快照对象");
    }

    // 版本闸：将来 v3 上线时，历史 v2 快照绝不能被按新合同重新解释（字段含义可能已改）
    @Test
    void schemaVersionMustMatchExactly() {
        rejectTampered("缺 schemaVersion", o -> o.remove("schemaVersion"));
        rejectTampered("旧版本 L2_V1", o -> o.put("schemaVersion", "L2_V1"));
        rejectTampered("大小写不同不得放行", o -> o.put("schemaVersion", "l2_v2"));
        rejectTampered("前后空白不得放行", o -> o.put("schemaVersion", " L2_V2 "));
    }

    // 资格快照缺失 = 无法证明这笔订单创建时卡是可充值的，只能拒绝
    @Test
    void eligibilityBlockRequired() {
        rejectTampered("缺资格快照", o -> o.remove(F_ELIGIBILITY));
        rejectTampered("缺 cardStatusAtCreate", o -> mutateEligibility(o, e -> e.remove("cardStatusAtCreate")));
        rejectTampered("缺 capturedTime", o -> mutateEligibility(o, e -> e.remove("capturedTime")));
        rejectTampered("capturedTime 空串", o -> mutateEligibility(o, e -> e.put("capturedTime", "")));
    }

    // 普通充值通过创单校验的卡状态只可能是 1；出现别的值说明快照被改写或来自非法创建路径，
    // 放行等于允许给冻结/注销卡补一笔历史订单
    @Test
    void cardStatusAtCreateMustBeNormal() {
        rejectTampered("状态 0", o -> mutateEligibility(o, e -> e.put("cardStatusAtCreate", 0)));
        rejectTampered("状态 2（冻结）", o -> mutateEligibility(o, e -> e.put("cardStatusAtCreate", 2)));
        rejectTampered("状态 null", o -> mutateEligibility(o, e -> e.put("cardStatusAtCreate", null)));
    }

    /** 审计 P1-3：非转正快照出现自然过期状态 3 同样拒绝——3 只在转正单里合法。 */
    @Test
    void expiredStatusRejectedOnNonPromoteSnapshot() {
        rejectTampered("非转正快照状态 3", o -> mutateEligibility(o, e -> e.put("cardStatusAtCreate", 3)));
    }

    /** 审计 P1-3：转正快照记真实状态——3（自然过期）合法保存并解析，2（冻结）仍拒。 */
    @Test
    void promoteSnapshotAcceptsExpiredStatusButNotOthers() {
        // build 正门：永久套餐 × 已过期赠卡（真实状态 3）——不再硬编码 1
        WsPackage permanent = pkg();
        permanent.setExpireDays(null);
        WsCard expiredGift = card("20250101120000");
        expiredGift.setCardStatus(3);
        String promoted = RechargeSnapshot.build(REQUEST_ID, permanent, expiredGift,
                cardScope(), null, CREATE_TIME, true);
        RechargeSnapshot.Parsed parsed = RechargeSnapshot.parse(promoted);
        assertEquals(3, parsed.cardStatusAtCreate(), "转正快照必须保存真实状态 3，不得伪装 1");
        assertTrue(parsed.promoteToPermanent());

        // 保 null 序列化篡改：转正快照的状态 2 仍必须拒绝
        JSONObject o = JSON.parseObject(promoted, com.alibaba.fastjson.parser.Feature.OrderedField);
        o.getJSONObject(F_ELIGIBILITY).put("cardStatusAtCreate", 2);
        rejectSnap(JSON.toJSONString(o, com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue),
                "转正快照状态 2");
    }

    // 范围快照是"充值不得偷偷扩大用卡范围"的比对基准，缺失或非法都不得放行
    @Test
    void scopeSnapshotsValidated() {
        rejectTampered("缺目标卡范围", o -> o.remove("targetCardScopeSnapshot"));
        rejectTampered("目标卡范围非法", o -> o.put("targetCardScopeSnapshot",
                JSON.parseObject("{\"scopeType\":\"nope\"}")));
        rejectTampered("套餐范围存在但非法", o -> o.put("packageScopeSnapshot",
                JSON.parseObject("{\"scopeType\":\"nope\"}")));
    }

    // 金额/水量/标识任一缺失都会让幂等复核失去比对项，等于对该字段无条件放行
    @Test
    void requiredValueFieldsMissingRejected() {
        rejectTampered("缺 payAmount", o -> o.remove("payAmount"));
        rejectTampered("缺 waterMl", o -> o.remove("waterMl"));
        rejectTampered("缺 bonusAmount", o -> o.remove("bonusAmount"));
        rejectTampered("缺 packageId", o -> o.remove("packageId"));
        rejectTampered("缺 requestId", o -> o.remove("requestId"));
        rejectTampered("缺 packageName", o -> o.remove("packageName"));
        rejectTampered("缺 unitPriceSnap", o -> o.remove("unitPriceSnap"));
        rejectTampered("缺 expireDays", o -> o.remove("expireDays"));
        rejectTampered("缺 packageScopeSnapshot", o -> o.remove("packageScopeSnapshot"));
        rejectTampered("缺 expireTimeAtCreate",
                o -> mutateEligibility(o, e -> e.remove("expireTimeAtCreate")));
    }

    @Test
    void requiredValueFieldsBlankRejected() {
        rejectTampered("payAmount 空串", o -> o.put("payAmount", ""));
        rejectTampered("waterMl 空串", o -> o.put("waterMl", ""));
        rejectTampered("bonusAmount 空串", o -> o.put("bonusAmount", ""));
        rejectTampered("packageId 空串", o -> o.put("packageId", ""));
        rejectTampered("requestId 空串", o -> o.put("requestId", ""));
    }

    @Test
    void nestedBlocksRequireJsonObjectTypes() {
        rejectTampered("资格快照不得为字符串", o -> o.put(F_ELIGIBILITY, "x"));
        rejectTampered("目标卡范围不得为字符串", o -> o.put("targetCardScopeSnapshot", "x"));
        rejectTampered("套餐范围不得为字符串", o -> o.put("packageScopeSnapshot", "x"));
    }

    @Test
    void persistentSnapshotDoesNotCoerceFieldTypes() {
        rejectTampered("payAmount 数字字符串", o -> o.put("payAmount", "100"));
        rejectTampered("waterMl 数字字符串", o -> o.put("waterMl", "100"));
        rejectTampered("bonusAmount 数字字符串", o -> o.put("bonusAmount", "100"));
        rejectTampered("expireDays 数字字符串", o -> o.put("expireDays", "90"));
        rejectTampered("cardStatusAtCreate 数字字符串",
                o -> mutateEligibility(o, e -> e.put("cardStatusAtCreate", "1")));
        rejectTampered("packageId 必须保持字符串", o -> o.put("packageId", 3));
        rejectTampered("unitPriceSnap 必须保持字符串", o -> o.put("unitPriceSnap", 20));
        rejectTampered("packageName 必须保持字符串", o -> o.put("packageName", 20));
        rejectTampered("requestId 必须保持字符串", o -> o.put("requestId", 1));
    }

    @Test
    void snapshotValuesMustPassTheSameM7LimitsAsLivePackage() {
        rejectTampered("售价超过 M7", o -> o.put("payAmount", 1_000_001));
        rejectTampered("水量超过 M7", o -> o.put("waterMl", 50_000_001));
        rejectTampered("赠送额超过 M7", o -> o.put("bonusAmount", 1_000_001));
        rejectTampered("有效期超过 M7", o -> o.put("expireDays", 3651));
        rejectTampered("单价超过 M7", o -> o.put("unitPriceSnap", "100000.01"));
    }

    @Test
    void unitPriceAndPackageShapeMustRemainSelfConsistent() {
        rejectTampered("水量套餐单价不得为零", o -> o.put("unitPriceSnap", "0"));
        rejectTampered("纯金额套餐单价必须为零", o -> {
            o.put("waterMl", 0);
            o.put("unitPriceSnap", "20.00");
        });
        rejectTampered("有限套餐不能锚定永久卡", o -> o.put("expireDays", 90));
        rejectTampered("永久套餐不能锚定有限卡",
                o -> mutateEligibility(o, e -> e.put("expireTimeAtCreate", "20261231235959")));
    }

    @Test
    void unknownSnapshotFieldsAreRejected() {
        rejectTampered("未知顶层字段", o -> o.put("extraCredit", 100));
        rejectTampered("未知资格字段",
                o -> mutateEligibility(o, e -> e.put("legacyStatus", 1)));
    }

    @Test
    void creditDefensivelyRevalidatesConstructedParsedValue() {
        RechargeSnapshot.Parsed valid = RechargeSnapshot.parse(validSnap());
        RechargeSnapshot.Parsed polluted = new RechargeSnapshot.Parsed(
                valid.schemaVersion(), valid.requestId(), valid.packageId(), valid.packageName(),
                RechargeLimits.PAY_AMOUNT_MAX + 1, valid.waterMl(), valid.bonusAmount(),
                valid.unitPriceSnap(), valid.expireDays(), valid.cardScope(), valid.packageScope(),
                valid.cardStatusAtCreate(), valid.expireTimeAtCreate(), valid.capturedTime(),
                valid.purchaseMode(), valid.plannedCardType(), valid.promoteToPermanent());
        assertThrows(JbkException.class, () -> RechargeCredit.of(polluted),
                "即使绕过 JSON parser 手工构造 Parsed，事务 B 也不得按污染权益入账");
    }
}

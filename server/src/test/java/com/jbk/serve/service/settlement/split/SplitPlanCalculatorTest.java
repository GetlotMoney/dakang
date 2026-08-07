package com.jbk.serve.service.settlement.split;

import com.jbk.tool.consts.settlement.SplitV2Enum.AttributionSource;
import com.jbk.tool.consts.settlement.SplitV2Enum.BasisLine;
import com.jbk.tool.consts.settlement.SplitV2Enum.RateMode;
import com.jbk.tool.consts.settlement.SplitV2Enum.RegionLevel;
import com.jbk.tool.consts.settlement.SplitV2Enum.RoleCode;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分润 V2 纯计算器（E2E-08 S1 测试矩阵 1~10、13、14）。
 *
 * <p>所有比例都是<b>测试值</b>：省 1000 / 市 800 / 区县 500 / 机主 5000 / 推荐 500 /
 * 配送员 8000 万分比，仅为让级差与恒等式可断言，与甲方任何正式参数无关
 * ——正式比例尚未确认（任务书第三节），生产种子为空。</p>
 */
class SplitPlanCalculatorTest {

    private static final long OWNER = 11L;
    private static final long REFERRER = 22L;
    private static final long PROVINCE = 31L;
    private static final long CITY = 32L;
    private static final long COUNTY = 33L;
    private static final long COURIER = 44L;

    private static SplitPlanSnapshot plan() {
        return new SplitPlanSnapshot("TEST-V2-001", List.of(
                new SplitPlanSnapshot.Item(BasisLine.WATER_SALE, RoleCode.WATER_OWNER,
                        RegionLevel.NONE, 5_000, RateMode.FIXED),
                new SplitPlanSnapshot.Item(BasisLine.WATER_SALE, RoleCode.WATER_DIRECT_REFERRER,
                        RegionLevel.NONE, 500, RateMode.FIXED),
                new SplitPlanSnapshot.Item(BasisLine.WATER_SALE, RoleCode.REGION_PROVINCE,
                        RegionLevel.PROVINCE, 1_000, RateMode.REGIONAL_CUMULATIVE),
                new SplitPlanSnapshot.Item(BasisLine.WATER_SALE, RoleCode.REGION_CITY,
                        RegionLevel.CITY, 800, RateMode.REGIONAL_CUMULATIVE),
                new SplitPlanSnapshot.Item(BasisLine.WATER_SALE, RoleCode.REGION_COUNTY,
                        RegionLevel.COUNTY, 500, RateMode.REGIONAL_CUMULATIVE),
                new SplitPlanSnapshot.Item(BasisLine.DELIVERY_FEE, RoleCode.DELIVERY_COURIER,
                        RegionLevel.NONE, 8_000, RateMode.FIXED)));
    }

    private static List<SplitCalcInput.RegionNode> fullChain() {
        return List.of(
                new SplitCalcInput.RegionNode(RegionLevel.PROVINCE, PROVINCE),
                new SplitCalcInput.RegionNode(RegionLevel.CITY, CITY),
                new SplitCalcInput.RegionNode(RegionLevel.COUNTY, COUNTY));
    }

    private static SplitCalcInput water(long basis, Long owner, Long referrer,
                                        List<SplitCalcInput.RegionNode> chain,
                                        AttributionSource source) {
        return new SplitCalcInput(BasisLine.WATER_SALE, basis, owner, referrer, chain,
                source, null, "ORD-1", "20260806120000");
    }

    private static Map<RoleCode, SplitComponentDraft> byRole(List<SplitComponentDraft> out) {
        return out.stream().collect(Collectors.toMap(SplitComponentDraft::roleCode, Function.identity()));
    }

    private static long total(List<SplitComponentDraft> out) {
        return out.stream().mapToLong(SplitComponentDraft::splitAmountFen).sum();
    }

    // ==================== 矩阵 1：双基数彻底分离 ====================

    @Test
    void waterAndDeliveryBasesAreComputedSeparately() {
        // 售水 1000 分、配送费 300 分：两次独立调用，各自恒等，绝不存在 1300 合并入口
        List<SplitComponentDraft> water = SplitPlanCalculator.calculate(plan(),
                water(1_000, OWNER, REFERRER, fullChain(), AttributionSource.PRIVATE_REFERRAL));
        List<SplitComponentDraft> delivery = SplitPlanCalculator.calculate(plan(),
                new SplitCalcInput(BasisLine.DELIVERY_FEE, 300, OWNER, REFERRER, fullChain(),
                        AttributionSource.PRIVATE_REFERRAL, COURIER, "ORD-1", "20260806120000"));

        assertEquals(1_000, total(water), "售水线组件总额必须恰等于售水基数");
        assertEquals(300, total(delivery), "配送线组件总额必须恰等于配送费基数");
        assertTrue(water.stream().allMatch(d -> d.line() == BasisLine.WATER_SALE));
        assertTrue(delivery.stream().allMatch(d -> d.line() == BasisLine.DELIVERY_FEE));
        // 配送线只有配送员与平台：机主/推荐/区域不得默认参与（M5）
        assertEquals(2, delivery.size());
        assertEquals(240, byRole(delivery).get(RoleCode.DELIVERY_COURIER).splitAmountFen());
        assertEquals(60, byRole(delivery).get(RoleCode.PLATFORM_REMAINDER).splitAmountFen());
    }

    // ==================== 矩阵 2：同人多角色保留多条 ====================

    @Test
    void sameUserInThreeRolesKeepsThreeComponents() {
        long same = 77L;
        List<SplitComponentDraft> out = SplitPlanCalculator.calculate(plan(),
                water(10_000, same, same,
                        List.of(new SplitCalcInput.RegionNode(RegionLevel.PROVINCE, same)),
                        AttributionSource.PRIVATE_REFERRAL));
        Map<RoleCode, SplitComponentDraft> m = byRole(out);
        // 机主 5000bp + 推荐 500bp + 省全额 1000bp：三条角色组件一条不少（M2），绝不合并去重
        assertEquals(same, m.get(RoleCode.WATER_OWNER).receiverUserId());
        assertEquals(same, m.get(RoleCode.WATER_DIRECT_REFERRER).receiverUserId());
        assertEquals(same, m.get(RoleCode.REGION_PROVINCE).receiverUserId());
        assertEquals(5_000, m.get(RoleCode.WATER_OWNER).splitAmountFen());
        assertEquals(500, m.get(RoleCode.WATER_DIRECT_REFERRER).splitAmountFen());
        assertEquals(1_000, m.get(RoleCode.REGION_PROVINCE).splitAmountFen());
        assertEquals(10_000, total(out));
    }

    // ==================== 矩阵 3：一级推荐，绝不递归 ====================

    @Test
    void referralIsExactlyOneLevel() {
        // B 推了 C：C 的单，推荐组件只属于 B
        List<SplitComponentDraft> cOrder = SplitPlanCalculator.calculate(plan(),
                water(10_000, /* C */ 3L, /* B */ 2L, List.of(), AttributionSource.PRIVATE_REFERRAL));
        assertEquals(2L, byRole(cOrder).get(RoleCode.WATER_DIRECT_REFERRER).receiverUserId(),
                "C 的推荐收益只归 B");

        // A 推了 B，但 C 的输入里推荐人为空（B 的上级 A 不进入 C 的单）：
        // 不产生推荐组件，也绝不沿任何链向上找 A（规则 9）
        List<SplitComponentDraft> noReferrer = SplitPlanCalculator.calculate(plan(),
                water(10_000, 3L, null, List.of(), AttributionSource.PRIVATE_REFERRAL));
        assertFalse(byRole(noReferrer).containsKey(RoleCode.WATER_DIRECT_REFERRER),
                "推荐人为空必须无推荐组件——A 对 C 没有收益");
        assertEquals(10_000, total(noReferrer), "无推荐人时其份额归平台，恒等式不破");
    }

    // ==================== 矩阵 4/5：级差 ====================

    @Test
    void regionalLadderProducesDifferentials() {
        // 省1000 市800 县500（万分比）、基数 10000 分 → 县500 市300 省200（矩阵 4）
        Map<RoleCode, SplitComponentDraft> m = byRole(SplitPlanCalculator.calculate(plan(),
                water(10_000, OWNER, null, fullChain(), AttributionSource.PRIVATE_REFERRAL)));
        assertEquals(500, m.get(RoleCode.REGION_COUNTY).splitAmountFen());
        assertEquals(300, m.get(RoleCode.REGION_CITY).splitAmountFen());
        assertEquals(200, m.get(RoleCode.REGION_PROVINCE).splitAmountFen());
        // 生效比例记录的是级差后实得，不是累计上限——证据表必须能直接对账
        assertEquals(500, m.get(RoleCode.REGION_COUNTY).effectiveRateBp());
        assertEquals(300, m.get(RoleCode.REGION_CITY).effectiveRateBp());
        assertEquals(200, m.get(RoleCode.REGION_PROVINCE).effectiveRateBp());
    }

    @Test
    void provinceOnlyChainGetsFullProvinceCumulative() {
        // 省直招（矩阵 5）：省拿完整省级累计 1000bp
        Map<RoleCode, SplitComponentDraft> m = byRole(SplitPlanCalculator.calculate(plan(),
                water(10_000, OWNER, null,
                        List.of(new SplitCalcInput.RegionNode(RegionLevel.PROVINCE, PROVINCE)),
                        AttributionSource.PRIVATE_REFERRAL)));
        assertEquals(1_000, m.get(RoleCode.REGION_PROVINCE).splitAmountFen());
        assertFalse(m.containsKey(RoleCode.REGION_CITY));
        assertFalse(m.containsKey(RoleCode.REGION_COUNTY));
    }

    // ==================== 矩阵 6：血缘冻结，无地缘原料 ====================

    @Test
    void calculatorHasNoGeoInputAtAll() {
        // M8 的模型级保证：输入 record 里根本不存在行政区字段，想按地缘算都没有原料。
        // 跨省放机器 → 调用方给的仍是冻结的血缘链 → 结果只由链决定。
        List<String> fields = new ArrayList<>();
        for (var f : SplitCalcInput.class.getRecordComponents()) {
            fields.add(f.getName());
        }
        for (String field : fields) {
            String lower = field.toLowerCase();
            assertFalse(lower.contains("district") || lower.contains("geo")
                            || lower.contains("address") || lower.contains("station"),
                    "计算输入不得携带行政区/水站定位字段：" + field);
        }
        // 同一冻结链，无论设备在哪个省，结果逐字节一致
        List<SplitComponentDraft> a = SplitPlanCalculator.calculate(plan(),
                water(10_000, OWNER, REFERRER, fullChain(), AttributionSource.PRIVATE_REFERRAL));
        List<SplitComponentDraft> b = SplitPlanCalculator.calculate(plan(),
                water(10_000, OWNER, REFERRER, fullChain(), AttributionSource.PRIVATE_REFERRAL));
        assertEquals(a, b);
    }

    // ==================== 矩阵 7：公域裁剪 ====================

    @Test
    void publicUnassignedDropsReferrerAndRegion() {
        // 公域未分配：即便调用方误传了链与推荐人，也一律裁剪（M9），份额归平台
        Map<RoleCode, SplitComponentDraft> m = byRole(SplitPlanCalculator.calculate(plan(),
                water(10_000, OWNER, REFERRER, fullChain(), AttributionSource.PUBLIC_UNASSIGNED)));
        assertFalse(m.containsKey(RoleCode.WATER_DIRECT_REFERRER));
        assertFalse(m.containsKey(RoleCode.REGION_PROVINCE));
        assertFalse(m.containsKey(RoleCode.REGION_CITY));
        assertFalse(m.containsKey(RoleCode.REGION_COUNTY));
        assertEquals(5_000, m.get(RoleCode.WATER_OWNER).splitAmountFen(), "机主收益不受公域影响");
        assertEquals(5_000, m.get(RoleCode.PLATFORM_REMAINDER).splitAmountFen());
    }

    @Test
    void publicManualChainParticipatesInLadder() {
        // 公域人工分配（M9/会议 19:33）：分给区县后，省市按级差联动
        Map<RoleCode, SplitComponentDraft> m = byRole(SplitPlanCalculator.calculate(plan(),
                water(10_000, OWNER, null, fullChain(), AttributionSource.PUBLIC_MANUAL)));
        assertEquals(500, m.get(RoleCode.REGION_COUNTY).splitAmountFen());
        assertEquals(300, m.get(RoleCode.REGION_CITY).splitAmountFen());
        assertEquals(200, m.get(RoleCode.REGION_PROVINCE).splitAmountFen());
    }

    // ==================== 矩阵 8/9/10：fail-closed 族 ====================

    @Test
    void duplicateRegionLevelRejected() {
        List<SplitCalcInput.RegionNode> dup = List.of(
                new SplitCalcInput.RegionNode(RegionLevel.PROVINCE, 31L),
                new SplitCalcInput.RegionNode(RegionLevel.PROVINCE, 35L));
        JbkException e = assertThrows(JbkException.class, () -> SplitPlanCalculator.calculate(plan(),
                water(10_000, OWNER, null, dup, AttributionSource.PRIVATE_REFERRAL)));
        assertTrue(e.getMsg().contains("同层出现多个区域服务商"), "实际=" + e.getMsg());
    }

    @Test
    void invertedCumulativeRatesRejected() {
        // 市 1200 > 省 1000：累计上限倒挂，整版拒绝
        SplitPlanSnapshot bad = new SplitPlanSnapshot("TEST-BAD", List.of(
                new SplitPlanSnapshot.Item(BasisLine.WATER_SALE, RoleCode.WATER_OWNER,
                        RegionLevel.NONE, 5_000, RateMode.FIXED),
                new SplitPlanSnapshot.Item(BasisLine.WATER_SALE, RoleCode.WATER_DIRECT_REFERRER,
                        RegionLevel.NONE, 500, RateMode.FIXED),
                new SplitPlanSnapshot.Item(BasisLine.WATER_SALE, RoleCode.REGION_PROVINCE,
                        RegionLevel.PROVINCE, 1_000, RateMode.REGIONAL_CUMULATIVE),
                new SplitPlanSnapshot.Item(BasisLine.WATER_SALE, RoleCode.REGION_CITY,
                        RegionLevel.CITY, 1_200, RateMode.REGIONAL_CUMULATIVE),
                new SplitPlanSnapshot.Item(BasisLine.WATER_SALE, RoleCode.REGION_COUNTY,
                        RegionLevel.COUNTY, 500, RateMode.REGIONAL_CUMULATIVE),
                new SplitPlanSnapshot.Item(BasisLine.DELIVERY_FEE, RoleCode.DELIVERY_COURIER,
                        RegionLevel.NONE, 8_000, RateMode.FIXED)));
        JbkException e = assertThrows(JbkException.class, bad::validate);
        assertTrue(e.getMsg().contains("倒挂"), "实际=" + e.getMsg());
    }

    @Test
    void missingRequiredItemRejectedNotSilentZero() {
        // 缺 REGION_CITY 项：整版拒绝（矩阵 10）——V1 的静默按 0 正是要纠的偏差
        SplitPlanSnapshot missing = new SplitPlanSnapshot("TEST-MISS", List.of(
                new SplitPlanSnapshot.Item(BasisLine.WATER_SALE, RoleCode.WATER_OWNER,
                        RegionLevel.NONE, 5_000, RateMode.FIXED),
                new SplitPlanSnapshot.Item(BasisLine.WATER_SALE, RoleCode.WATER_DIRECT_REFERRER,
                        RegionLevel.NONE, 500, RateMode.FIXED),
                new SplitPlanSnapshot.Item(BasisLine.WATER_SALE, RoleCode.REGION_PROVINCE,
                        RegionLevel.PROVINCE, 1_000, RateMode.REGIONAL_CUMULATIVE),
                new SplitPlanSnapshot.Item(BasisLine.WATER_SALE, RoleCode.REGION_COUNTY,
                        RegionLevel.COUNTY, 500, RateMode.REGIONAL_CUMULATIVE),
                new SplitPlanSnapshot.Item(BasisLine.DELIVERY_FEE, RoleCode.DELIVERY_COURIER,
                        RegionLevel.NONE, 8_000, RateMode.FIXED)));
        JbkException e = assertThrows(JbkException.class, missing::validate);
        assertTrue(e.getMsg().contains("缺少必需角色项"), "实际=" + e.getMsg());
    }

    @Test
    void brokenChainRejectedUntilClientDecides() {
        // 有区县无市：缺失层份额归平台还是归上级未确认（第三节 2 条）——阻断而非猜
        List<SplitCalcInput.RegionNode> broken = List.of(
                new SplitCalcInput.RegionNode(RegionLevel.PROVINCE, PROVINCE),
                new SplitCalcInput.RegionNode(RegionLevel.COUNTY, COUNTY));
        JbkException e = assertThrows(JbkException.class, () -> SplitPlanCalculator.calculate(plan(),
                water(10_000, OWNER, null, broken, AttributionSource.PRIVATE_REFERRAL)));
        assertTrue(e.getMsg().contains("断层"), "实际=" + e.getMsg());
    }

    @Test
    void deliveryLineWithForeignRolesRejected() {
        // 配送线配机主比例：非法计划整版拒（M5 不是配置项能改的）
        SplitPlanSnapshot bad = new SplitPlanSnapshot("TEST-DLV", List.of(
                new SplitPlanSnapshot.Item(BasisLine.DELIVERY_FEE, RoleCode.WATER_OWNER,
                        RegionLevel.NONE, 1_000, RateMode.FIXED)));
        JbkException e = assertThrows(JbkException.class, bad::validate);
        assertTrue(e.getMsg().contains("配送费线只允许配置配送员比例"), "实际=" + e.getMsg());
    }

    @Test
    void deliveryWithoutCourierRejected() {
        JbkException e = assertThrows(JbkException.class, () -> SplitPlanCalculator.calculate(plan(),
                new SplitCalcInput(BasisLine.DELIVERY_FEE, 300, OWNER, null, List.of(),
                        AttributionSource.PRIVATE_REFERRAL, null, "ORD-1", "20260806120000")));
        assertTrue(e.getMsg().contains("缺少配送员"), "实际=" + e.getMsg());
    }

    // ==================== 矩阵 13：恒等式 ====================

    @Test
    void componentTotalsAlwaysEqualBasis() {
        long[] bases = { 1, 3, 99, 1_000, 9_999, 123_456_789 };
        List<List<SplitCalcInput.RegionNode>> chains = List.of(
                List.of(),
                List.of(new SplitCalcInput.RegionNode(RegionLevel.PROVINCE, PROVINCE)),
                List.of(new SplitCalcInput.RegionNode(RegionLevel.PROVINCE, PROVINCE),
                        new SplitCalcInput.RegionNode(RegionLevel.CITY, CITY)),
                fullChain());
        for (long basis : bases) {
            for (List<SplitCalcInput.RegionNode> chain : chains) {
                List<SplitComponentDraft> out = SplitPlanCalculator.calculate(plan(),
                        water(basis, OWNER, REFERRER, chain, AttributionSource.PRIVATE_REFERRAL));
                assertEquals(basis, total(out),
                        "恒等式破裂：基数 " + basis + " 链深 " + chain.size());
                // 非平台各行向下取整，平台行吃余数且不为负
                out.stream().filter(d -> d.roleCode() == RoleCode.PLATFORM_REMAINDER)
                        .forEach(d -> assertTrue(d.splitAmountFen() >= 0));
            }
        }
    }

    @Test
    void zeroBasisYieldsNoComponents() {
        assertTrue(SplitPlanCalculator.calculate(plan(),
                water(0, OWNER, REFERRER, fullChain(), AttributionSource.PRIVATE_REFERRAL)).isEmpty());
    }

    // ==================== 矩阵 14：溢出与负数 ====================

    @Test
    void hugeAmountsFailClosedInsteadOfOverflowing() {
        // Long.MAX_VALUE × 9999 必然溢出：必须抛业务异常，绝不产出看似正常的错数
        JbkException e = assertThrows(JbkException.class,
                () -> SplitPlanCalculator.mulDivFloor(Long.MAX_VALUE, 9_999));
        assertTrue(e.getMsg().contains("溢出"), "实际=" + e.getMsg());

        // 安全边界内的极大值正常算且非负
        long safe = Long.MAX_VALUE / 10_000;
        assertTrue(SplitPlanCalculator.mulDivFloor(safe, 9_999) >= 0);

        assertThrows(JbkException.class, () -> SplitPlanCalculator.calculate(plan(),
                water(-1, OWNER, null, List.of(), AttributionSource.PRIVATE_REFERRAL)));
    }

    // ==================== 证据字段完备性（任务书 5.3 输出要求） ====================

    @Test
    void everyComponentCarriesFullEvidence() {
        List<SplitComponentDraft> out = SplitPlanCalculator.calculate(plan(),
                water(10_000, OWNER, REFERRER, fullChain(), AttributionSource.PRIVATE_REFERRAL));
        for (SplitComponentDraft d : out) {
            assertEquals(BasisLine.WATER_SALE, d.line());
            assertEquals(10_000, d.basisAmountFen());
            assertEquals("TEST-V2-001", d.planVersion());
            assertEquals(AttributionSource.PRIVATE_REFERRAL, d.attributionSource());
            assertTrue(d.componentKey().startsWith("SPLITV2:ORD-1:WATER_SALE:"));
            if (d.roleCode() == RoleCode.PLATFORM_REMAINDER) {
                assertEquals(SplitPlanCalculator.PLATFORM_SENTINEL_USER, d.receiverUserId());
                assertEquals(SplitPlanCalculator.REMAINDER_RATE, d.effectiveRateBp(),
                        "余数不是比例，比例位必须是 -1 占位而非伪造值");
            }
        }
    }
}

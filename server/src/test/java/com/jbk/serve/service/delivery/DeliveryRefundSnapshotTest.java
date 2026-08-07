package com.jbk.serve.service.delivery;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jbk.tool.data.trade.po.WsOrder;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配送快照解析单测（E2E-04 包A，{@code ws_order.PACKAGE_SNAP} 的唯一解析器）。
 *
 * <p>解析器同时服务两种快照形状：用户下单（{@code buildSnap}，带 unitWaterPriceFen /
 * deliveryFeePerContainerFen）与自动补货周期单（{@code generateOne}，不带这两列）。
 * 两种都必须能解析，且单价<b>恒定</b>由总额整除数量派生、声明值只用来交叉核对——
 * 本类逐条钉死这一口径，以及「单价×数量=总额」「waterMl 可被数量整除」
 * 「payWay=3 余额侧水费恒 0」这几条硬不变式。</p>
 *
 * <p>所有坏形状一律 fail-closed：金额与配额不许猜，猜错的代价是多退或少退真金白银。</p>
 */
class DeliveryRefundSnapshotTest {

    // ==================== 两种快照形状的正常解析 ====================

    /** 用户下单快照（带单价字段）：8 个字段逐个断言，声明单价与派生单价一致。 */
    @Test
    void parsesUserOrderSnapshotWithDeclaredUnitPrices() {
        DeliveryRefundSnapshot.Parsed parsed = DeliveryRefundSnapshot.require(order(balanceSnap()));
        assertEquals(2, parsed.payWay());
        assertEquals(6500L, parsed.waterAmountFen());
        assertEquals(1000L, parsed.deliveryFeeFen());
        assertEquals(100_000L, parsed.waterMl());
        assertEquals(5, parsed.deliveryCount());
        assertEquals(1300L, parsed.unitWaterPriceFen());
        assertEquals(200L, parsed.deliveryFeePerContainerFen());
        assertEquals(6500L, parsed.priceWaterAmountFen());
        assertEquals(20_000L, parsed.unitWaterMl());
        assertFalse(parsed.payByMl());
    }

    /**
     * 自动补货周期单快照（无单价字段）：若在此硬性要求字段存在，全部周期单将永远无法退款。
     * 单价必须由同一份快照的总额整除数量派生出来。
     */
    @Test
    void parsesAutoRefillSnapshotWithoutDeclaredUnitPrices() {
        JSONObject snap = balanceSnap();
        snap.remove("unitWaterPriceFen");
        snap.remove("deliveryFeePerContainerFen");
        DeliveryRefundSnapshot.Parsed parsed = DeliveryRefundSnapshot.require(order(snap));
        assertEquals(1300L, parsed.unitWaterPriceFen(), "单价须由价目水费总额 ÷ 数量派生");
        assertEquals(200L, parsed.deliveryFeePerContainerFen(), "单桶配送费须由配送费总额 ÷ 数量派生");
        assertEquals(6500L, parsed.waterAmountFen());
    }

    /** payWay=3：余额侧水费恒 0，水品维度由 waterMl 承接；单价仍从价目水费派生（供展示与交叉核对）。 */
    @Test
    void parsesMlPaySnapshot() {
        DeliveryRefundSnapshot.Parsed parsed = DeliveryRefundSnapshot.require(order(mlSnap()));
        assertEquals(3, parsed.payWay());
        assertTrue(parsed.payByMl());
        assertEquals(0L, parsed.waterAmountFen(), "水量抵扣单的余额侧水费必须为 0");
        assertEquals(1000L, parsed.deliveryFeeFen(), "配送费在 payWay=3 下同样从余额扣");
        assertEquals(100_000L, parsed.waterMl());
        assertEquals(20_000L, parsed.unitWaterMl());
        assertEquals(1300L, parsed.unitWaterPriceFen());
    }

    // ==================== 单价×数量=总额 硬不变式 ====================

    /**
     * 声明单价与派生单价冲突 → 拒绝。这条钉死的是「单价 × 数量 = 总额」：
     * 若放行，售后会按被改写的单价乘以批准数量返还，而封顶基准仍是原扣款，多退的部分无人察觉。
     */
    @Test
    void declaredUnitPriceConflictingWithDerivedIsRejected() {
        JSONObject high = balanceSnap().set("unitWaterPriceFen", 1301L);
        JbkException e = assertThrows(JbkException.class,
                () -> DeliveryRefundSnapshot.require(order(high)));
        assertTrue(e.getMessage().contains("不自洽快照"), e.getMessage());
        // 低于派生值同样拒绝：少退用户的钱也是事故
        assertThrows(JbkException.class,
                () -> DeliveryRefundSnapshot.require(order(balanceSnap().set("unitWaterPriceFen", 1299L))));
        assertThrows(JbkException.class,
                () -> DeliveryRefundSnapshot.require(order(balanceSnap().set("unitWaterPriceFen", 0L))));
        // 单桶配送费同样逐一比对
        assertThrows(JbkException.class,
                () -> DeliveryRefundSnapshot.require(
                        order(balanceSnap().set("deliveryFeePerContainerFen", 201L))));
    }

    /** 总额不能被数量整除 → 拒绝：除不尽意味着「一桶」在该维度没有确定单价。 */
    @Test
    void totalsNotDivisibleByCountAreRejected() {
        // 价目水费 6501 / 5 除不尽
        JSONObject water = balanceSnap()
                .set("waterAmountFen", 6501L)
                .set("priceWaterAmountFen", 6501L)
                .set("unitWaterPriceFen", 1300L);
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(order(water)));
        // 配送费 1001 / 5 除不尽
        JSONObject fee = balanceSnap().set("deliveryFeeFen", 1001L)
                .set("deliveryFeePerContainerFen", 200L);
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(order(fee)));
    }

    /**
     * waterMl 不能被 deliveryCount 整除 → 拒绝。
     * 放行会让 payWay=3 的单桶水量出现舍入，按 q 桶返还必然与原扣款差出零头。
     */
    @Test
    void waterMlNotDivisibleByCountIsRejected() {
        JbkException e = assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(
                order(mlSnap().set("waterMl", 100_001L))));
        assertTrue(e.getMessage().contains("整除"), e.getMessage());
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(
                order(balanceSnap().set("waterMl", 99_999L))));
    }

    /** waterMl 必须为正：为 0 会让单桶水量退化成 0，返还静默变成 0。 */
    @Test
    void nonPositiveWaterMlIsRejected() {
        assertThrows(JbkException.class,
                () -> DeliveryRefundSnapshot.require(order(mlSnap().set("waterMl", 0L))));
        assertThrows(JbkException.class,
                () -> DeliveryRefundSnapshot.require(order(mlSnap().set("waterMl", -100_000L))));
    }

    // ==================== D-214 金额口径 ====================

    /** payWay=3 但余额侧水费不为 0 → 拒绝（水费已从水量扣过一次，再从余额退就是双份）。 */
    @Test
    void mlPayWithNonZeroBalanceWaterAmountIsRejected() {
        JbkException e = assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(
                order(mlSnap().set("waterAmountFen", 6500L))));
        assertTrue(e.getMessage().contains("必须为 0"), e.getMessage());
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(
                order(mlSnap().set("waterAmountFen", 1L))));
    }

    /** payWay=2 但实扣水费 ≠ 价目水费 → 拒绝（快照被改写或来自未授权创建路径）。 */
    @Test
    void balancePayWithWaterAmountDivergingFromPriceIsRejected() {
        JSONObject snap = balanceSnap().set("waterAmountFen", 0L);
        JbkException e = assertThrows(JbkException.class,
                () -> DeliveryRefundSnapshot.require(order(snap)));
        assertTrue(e.getMessage().contains("价目水费"), e.getMessage());
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(
                order(balanceSnap().set("priceWaterAmountFen", 6000L))));
    }

    /** 支付方式白名单：只有 2/3 走内部权益返还，1微信与未知值一律拒绝。 */
    @Test
    void payWayWhitelistRejectsWechatAndUnknown() {
        for (Object payWay : new Object[]{1, 0, 4, 99, -1}) {
            JbkException e = assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(
                    order(balanceSnap().set("payWay", payWay))), "payWay=" + payWay + " 必须拒绝");
            assertTrue(e.getMessage().contains("不支持内部返还"), e.getMessage());
        }
    }

    /** 配送数量界：< 1 或超过溢出闸一律拒绝。 */
    @Test
    void deliveryCountBoundsAreEnforced() {
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(
                order(balanceSnap().set("deliveryCount", 0))));
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(
                order(balanceSnap().set("deliveryCount", -5))));
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(
                order(balanceSnap().set("deliveryCount", 100_001))));
    }

    // ==================== 输入形状 fail-closed ====================

    @Test
    void missingOrderOrSnapshotIsRejected() {
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(null));
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(new WsOrder()));
        WsOrder blank = new WsOrder();
        blank.setPackageSnap("   ");
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(blank));
    }

    @Test
    void malformedJsonIsRejected() {
        for (String raw : new String[]{"{\"payWay\":", "not-json-at-all", "[1,2,3]", "<xml/>"}) {
            WsOrder order = new WsOrder();
            order.setPackageSnap(raw);
            assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(order),
                    "快照 " + raw + " 必须拒绝");
        }
    }

    /** 每个必需字段缺失都要各自拒绝：缺一个就按 0 兜底会让某一维额度凭空归零或放大。 */
    @Test
    void everyRequiredFieldIsMandatory() {
        for (String field : new String[]{"payWay", "deliveryCount", "waterAmountFen",
                "deliveryFeeFen", "priceWaterAmountFen", "waterMl"}) {
            JSONObject snap = balanceSnap();
            snap.remove(field);
            assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(order(snap)),
                    "缺字段 " + field + " 必须拒绝");
        }
    }

    /**
     * 严格整数：一份本该由服务端生成的金额快照里出现字符串或小数，本身就是被改写过的信号。
     * 刻意不走 hutool 的宽容转换（{@code "6500"}、{@code 6500.0} 都不接受）。
     */
    @Test
    void stringAndDecimalNumbersAreRejected() {
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(
                order(balanceSnap().set("waterAmountFen", "6500"))), "字符串数字必须拒绝");
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(
                order(balanceSnap().set("deliveryCount", "5"))), "字符串数量必须拒绝");
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(
                order(balanceSnap().set("waterMl", new BigDecimal("100000.5")))), "小数必须拒绝");
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(
                order(balanceSnap().set("payWay", true))), "布尔值必须拒绝");
        // 反面确认：整数写法（无小数点、无引号）必须照常解析，严格化不得误伤正常快照
        assertEquals(1000L, DeliveryRefundSnapshot.require(
                order(balanceSnap().set("deliveryFeeFen", new BigDecimal("1000")))).deliveryFeeFen());
    }

    /** 负金额与越界整数：拒绝而不是取绝对值或截断。 */
    @Test
    void negativeAndOutOfRangeNumbersAreRejected() {
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(
                order(balanceSnap().set("deliveryFeeFen", -1L))));
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(
                order(balanceSnap().set("waterAmountFen", -6500L))));
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(
                order(balanceSnap().set("deliveryCount",
                        new BigInteger("99999999999999999999")))), "越界整数必须拒绝");
        assertThrows(JbkException.class, () -> DeliveryRefundSnapshot.require(
                order(balanceSnap().set("deliveryCount", 3_000_000_000L))), "超 int 范围必须拒绝");
    }

    // ==================== helpers ====================

    /** 20L桶 × 5 余额支付：水费 1300/桶、配送费 200/桶、水量 20000ml/桶（对齐 DeliveryPricing 价目）。 */
    private static JSONObject balanceSnap() {
        return JSONUtil.createObj()
                .set("requestId", "550e8400-e29b-41d4-a716-446655440000")
                .set("containerSpec", "20L桶")
                .set("deliveryCount", 5)
                .set("planReturnCount", 0)
                .set("waterTypeId", 1L)
                .set("unitWaterPriceFen", 1300L)
                .set("deliveryFeePerContainerFen", 200L)
                .set("waterAmountFen", 6500L)
                .set("deliveryFeeFen", 1000L)
                .set("payWay", 2)
                .set("waterMl", 100_000L)
                .set("priceWaterAmountFen", 6500L)
                .set("deliveryMode", 1);
    }

    /** 同规格改走卡水量抵扣：余额侧水费归零，价目水费仍保留作单价来源。 */
    private static JSONObject mlSnap() {
        return balanceSnap().set("payWay", 3).set("waterAmountFen", 0L);
    }

    private static WsOrder order(JSONObject snap) {
        WsOrder order = new WsOrder();
        order.setId(9001L);
        order.setOrderNo("DO-9001");
        order.setPackageSnap(snap.toString());
        return order;
    }
}

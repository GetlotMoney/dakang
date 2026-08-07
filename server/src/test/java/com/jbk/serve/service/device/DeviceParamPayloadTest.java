package com.jbk.serve.service.device;

import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-213-S1 参数报文校验器单测。
 *
 * <p>钉两件事：结构校验在空注册表下也必须生效；登记只收紧不放松。</p>
 */
class DeviceParamPayloadTest {

    private static final Map<String, DeviceParamPayload.Definition> EMPTY = Map.of();

    private static Map<String, DeviceParamPayload.Definition> defs(DeviceParamPayload.Definition... items) {
        return List.of(items).stream()
                .collect(java.util.stream.Collectors.toMap(
                        DeviceParamPayload.Definition::paramKey, d -> d));
    }

    private static JbkException reject(String payload, Map<String, DeviceParamPayload.Definition> registry) {
        return assertThrows(JbkException.class, () -> DeviceParamPayload.require(payload, registry));
    }

    // ==================== 1. 结构校验：空注册表下也必须拦住 ====================

    @Test
    void structuralRulesApplyEvenWithEmptyRegistry() {
        // 空/非 JSON/非对象
        reject(null, EMPTY);
        reject("", EMPTY);
        reject("   ", EMPTY);
        reject("not-json", EMPTY);
        reject("[1,2,3]", EMPTY);
        reject("\"bare-string\"", EMPTY);
        reject("{}", EMPTY);

        // 嵌套与数组值
        reject("{\"a\":{\"b\":1}}", EMPTY);
        reject("{\"a\":[1,2]}", EMPTY);
        // null 值。注意：只测 {"a":null} 是不够的——Hutool 默认会在解析阶段丢掉 null 值键，
        // 那样它会因为「变成空对象」而被拒，看起来绿了，实则禁 null 规则从未被触达。
        // 必须用「多键且其中一个为 null」的形态，才真正走到 requireScalar 的 null 分支。
        reject("{\"a\":null}", EMPTY);
        assertTrue(reject("{\"a\":1,\"b\":null}", EMPTY).getMsg().contains("不能为空"),
                "含 null 的多键报文必须被拒，且拒因是 null 而不是别的");
        assertTrue(reject("{\"a\":null,\"b\":2}", EMPTY).getMsg().contains("不能为空"));

        // 非法键名：数字开头、含连字符、含点、空键、超 32 位
        reject("{\"1abc\":1}", EMPTY);
        reject("{\"a-b\":1}", EMPTY);
        reject("{\"a.b\":1}", EMPTY);
        reject("{\"\":1}", EMPTY);
        reject("{\"" + "a".repeat(33) + "\":1}", EMPTY);

        // 键数超限（33 个）
        StringBuilder many = new StringBuilder("{");
        for (int i = 0; i < 33; i++) {
            many.append(i > 0 ? "," : "").append("\"k").append(i).append("\":1");
        }
        reject(many.append("}").toString(), EMPTY);

        // 字符串值超 64 字符
        reject("{\"a\":\"" + "x".repeat(65) + "\"}", EMPTY);
    }

    @Test
    void flatScalarObjectPassesAndUnregisteredKeysAreReported() {
        DeviceParamPayload.Checked checked = DeviceParamPayload.require(
                "{\"limitMl\":5000,\"flushSec\":1.5,\"label\":\"abc\",\"enabled\":true}", EMPTY);

        assertEquals(4, checked.values().size());
        assertEquals("5000", checked.values().get("limitMl"));
        assertEquals("1.5", checked.values().get("flushSec"));
        assertEquals("abc", checked.values().get("label"));
        assertEquals("true", checked.values().get("enabled"));
        // 空注册表 == 今天的行为：全部放行，但必须被标出来，否则"未登记"就成了无声通过
        assertFalse(checked.allRegistered());
        assertEquals(java.util.Set.of("limitMl", "flushSec", "label", "enabled"), checked.unregisteredKeys());
    }

    @Test
    void boundaryValuesArePermitted() {
        // 边界恰好合法：32 位键名、64 字符值、32 项
        String key32 = "a".repeat(32);
        DeviceParamPayload.require("{\"" + key32 + "\":1}", EMPTY);
        DeviceParamPayload.require("{\"a\":\"" + "x".repeat(64) + "\"}", EMPTY);

        StringBuilder exactly32 = new StringBuilder("{");
        for (int i = 0; i < 32; i++) {
            exactly32.append(i > 0 ? "," : "").append("\"k").append(i).append("\":1");
        }
        DeviceParamPayload.Checked ok = DeviceParamPayload.require(exactly32.append("}").toString(), EMPTY);
        assertEquals(32, ok.values().size());
    }

    // ==================== 2. 登记校验：整数/小数/布尔/字符串 ====================

    @Test
    void registeredIntegerEnforcesTypeAndRange() {
        var registry = defs(new DeviceParamPayload.Definition(
                "limitMl", DeviceParamPayload.TYPE_INT, "毫升", "100", "20000", null));

        DeviceParamPayload.Checked ok = DeviceParamPayload.require("{\"limitMl\":5000}", registry);
        assertTrue(ok.allRegistered(), "已登记键不应再被标为未登记");

        // 边界含端点
        DeviceParamPayload.require("{\"limitMl\":100}", registry);
        DeviceParamPayload.require("{\"limitMl\":20000}", registry);

        assertTrue(reject("{\"limitMl\":99}", registry).getMsg().contains("下限"));
        assertTrue(reject("{\"limitMl\":20001}", registry).getMsg().contains("上限"));
        assertTrue(reject("{\"limitMl\":1.5}", registry).getMsg().contains("整数"));
        assertTrue(reject("{\"limitMl\":\"5000\"}", registry).getMsg().contains("整数"));
        reject("{\"limitMl\":true}", registry);
    }

    @Test
    void registeredDecimalAcceptsIntegerFormToo() {
        var registry = defs(new DeviceParamPayload.Definition(
                "flushSec", DeviceParamPayload.TYPE_DECIMAL, "秒", "0", "10", null));

        DeviceParamPayload.require("{\"flushSec\":1.5}", registry);
        DeviceParamPayload.require("{\"flushSec\":3}", registry);
        DeviceParamPayload.require("{\"flushSec\":0}", registry);
        reject("{\"flushSec\":10.1}", registry);
        reject("{\"flushSec\":-0.1}", registry);
        reject("{\"flushSec\":\"1.5\"}", registry);
    }

    @Test
    void registeredBooleanRejectsStringLookalike() {
        var registry = defs(new DeviceParamPayload.Definition(
                "enabled", DeviceParamPayload.TYPE_BOOL, null, null, null, null));

        DeviceParamPayload.require("{\"enabled\":true}", registry);
        DeviceParamPayload.require("{\"enabled\":false}", registry);
        // "true" 字符串不是布尔：类型模糊会让设备侧解析出分歧
        assertTrue(reject("{\"enabled\":\"true\"}", registry).getMsg().contains("布尔"));
        reject("{\"enabled\":1}", registry);
    }

    @Test
    void registeredStringRejectsNumberAndBoolean() {
        // 这正是 priceVersion 的登记形态：PC 示例值是 "PV-20260730-01"
        var registry = defs(new DeviceParamPayload.Definition(
                "priceVersion", DeviceParamPayload.TYPE_STRING, null, null, null, null));

        DeviceParamPayload.require("{\"priceVersion\":\"PV-20260730-01\"}", registry);
        assertTrue(reject("{\"priceVersion\":5}", registry).getMsg().contains("字符串"));
        reject("{\"priceVersion\":true}", registry);
    }

    @Test
    void registeredEnumRestrictsValues() {
        var registry = defs(new DeviceParamPayload.Definition(
                "mode", DeviceParamPayload.TYPE_STRING, null, null, null, "eco, normal ,boost"));

        DeviceParamPayload.require("{\"mode\":\"eco\"}", registry);
        // 枚举项两侧空白必须被容忍，否则配置里多一个空格就静默拒绝所有值
        DeviceParamPayload.require("{\"mode\":\"normal\"}", registry);
        DeviceParamPayload.require("{\"mode\":\"boost\"}", registry);
        reject("{\"mode\":\"turbo\"}", registry);
    }

    @Test
    void illegalDefinitionBoundFailsClosed() {
        // 定义表里的边界值自身非法：必须抛出，不能静默忽略——静默忽略等于校验闸悄悄失效
        var registry = defs(new DeviceParamPayload.Definition(
                "limitMl", DeviceParamPayload.TYPE_INT, "毫升", "abc", null, null));
        assertTrue(reject("{\"limitMl\":5000}", registry).getMsg().contains("定义下限"));

        var badType = defs(new DeviceParamPayload.Definition(
                "x", 99, null, null, null, null));
        assertTrue(reject("{\"x\":1}", badType).getMsg().contains("定义值类型非法"));
    }

    // ==================== 3. 单调收紧：登记前放行，登记后被拒 ====================

    @Test
    void registrationOnlyTightensNeverLoosens() {
        String payload = "{\"limitMl\":99999}";

        // 登记前：结构合法即放行（== 今天的行为，不构成回归）
        DeviceParamPayload.Checked before = DeviceParamPayload.require(payload, EMPTY);
        assertEquals("99999", before.values().get("limitMl"));
        assertFalse(before.allRegistered());

        // 登记后：同一报文因越界被拒
        var registry = defs(new DeviceParamPayload.Definition(
                "limitMl", DeviceParamPayload.TYPE_INT, "毫升", "100", "20000", null));
        assertTrue(reject(payload, registry).getMsg().contains("上限"));
    }

    @Test
    void mixedRegisteredAndUnregisteredKeysCoexist() {
        var registry = defs(new DeviceParamPayload.Definition(
                "limitMl", DeviceParamPayload.TYPE_INT, "毫升", "100", "20000", null));

        DeviceParamPayload.Checked checked =
                DeviceParamPayload.require("{\"limitMl\":5000,\"unknownKey\":\"v\"}", registry);

        assertEquals(2, checked.values().size());
        assertEquals(java.util.Set.of("unknownKey"), checked.unregisteredKeys());
        // 已登记键越界时，同一报文里的未登记键不能"顺带"把它救回来
        reject("{\"limitMl\":1,\"unknownKey\":\"v\"}", registry);
    }

    @Test
    void caseVariantKeyIsADistinctKeyNotABypass() {
        // 库侧 PARAM_KEY 已改列级 utf8mb4_bin，与这里的大小写敏感查找同构。
        // 若库侧仍是 ci，{"priceversion":123} 会绕过「必须是字符串」却落到 priceVersion 那一行。
        var registry = defs(new DeviceParamPayload.Definition(
                "priceVersion", DeviceParamPayload.TYPE_STRING, null, null, null, null));

        // 正确拼写：受登记约束
        reject("{\"priceVersion\":123}", registry);
        // 大小写变体：是另一个键，按未登记放行——但它在库里也是另一行，不会污染已登记键
        DeviceParamPayload.Checked variant = DeviceParamPayload.require("{\"priceversion\":123}", registry);
        assertEquals(java.util.Set.of("priceversion"), variant.unregisteredKeys());
        // 两个变体同时出现时是两个独立键，不得被折叠
        DeviceParamPayload.Checked both =
                DeviceParamPayload.require("{\"priceVersion\":\"PV-1\",\"priceversion\":\"PV-2\"}", registry);
        assertEquals(2, both.values().size());
        assertEquals("PV-1", both.values().get("priceVersion"));
        assertEquals("PV-2", both.values().get("priceversion"));
    }

    @Test
    void nullRegistryIsTreatedAsEmptyNotAsCrash() {
        DeviceParamPayload.Checked checked = DeviceParamPayload.require("{\"a\":1}", null);
        assertEquals(java.util.Set.of("a"), checked.unregisteredKeys());
    }
}

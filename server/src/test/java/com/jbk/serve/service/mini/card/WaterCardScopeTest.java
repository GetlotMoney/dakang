package com.jbk.serve.service.mini.card;

import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 范围规范化与授权语义相等（L2 契约 §2.2）。
 * 该算法是"充值不得偷偷扩大用卡范围"的唯一判定依据，故用例以证伪为主。
 */
class WaterCardScopeTest {

    private WaterCardScope ok(String json) {
        return WaterCardScope.normalize(json, "水卡");
    }

    private void reject(String json) {
        assertThrows(JbkException.class, () -> WaterCardScope.normalize(json, "水卡"), "应拒绝：" + json);
    }

    // 空范围=未配置=默认拒绝，绝不解释为全场通用
    @Test
    void blankScopeIsDeniedNotAll() {
        reject(null);
        reject("");
        reject("   ");
    }

    // 畸形 JSON / 非 object / 未知 scopeType / 未知顶层字段
    @Test
    void malformedOrUnknownRejected() {
        reject("not-json");
        reject("[1,2]");
        reject("\"all\"");
        reject("{\"scopeType\":\"ALL\"}");          // 大小写不放行
        reject("{\"scopeType\":\"any\"}");
        reject("{}");                                 // 缺 scopeType
        reject("{\"scopeType\":\"all\",\"foo\":1}"); // 未知顶层字段 fail-closed
    }

    // scopeType=all 三数组必须缺省或空；specified 至少一个非空
    @Test
    void typeArityRules() {
        assertEquals("all", ok("{\"scopeType\":\"all\"}").scopeType());
        assertEquals("all", ok("{\"scopeType\":\"all\",\"stationIds\":[]}").scopeType());
        reject("{\"scopeType\":\"all\",\"stationIds\":[1]}");
        reject("{\"scopeType\":\"specified\"}");
        reject("{\"scopeType\":\"specified\",\"stationIds\":[],\"deviceIds\":[],\"outletIds\":[]}");
    }

    // ID 元素：0/负/小数/指数/空串/重复/布尔/嵌套一律拒绝
    @Test
    void idElementRules() {
        reject("{\"scopeType\":\"specified\",\"stationIds\":[0]}");
        reject("{\"scopeType\":\"specified\",\"stationIds\":[-1]}");
        reject("{\"scopeType\":\"specified\",\"stationIds\":[1.5]}");
        reject("{\"scopeType\":\"specified\",\"stationIds\":[\"1.0\"]}");
        reject("{\"scopeType\":\"specified\",\"stationIds\":[\"1e3\"]}");
        reject("{\"scopeType\":\"specified\",\"stationIds\":[\"\"]}");
        reject("{\"scopeType\":\"specified\",\"stationIds\":[\"01\"]}");   // 前导零非规范十进制
        reject("{\"scopeType\":\"specified\",\"stationIds\":[1,1]}");      // 重复
        reject("{\"scopeType\":\"specified\",\"stationIds\":[true]}");
        reject("{\"scopeType\":\"specified\",\"stationIds\":[{\"a\":1}]}");
        reject("{\"scopeType\":\"specified\",\"stationIds\":\"1\"}");      // 非数组
        // 超 Long 范围
        reject("{\"scopeType\":\"specified\",\"stationIds\":[\"99999999999999999999\"]}");
    }

    // 历史 JSON 整数与新快照字符串等价；一律升序规范化
    @Test
    void integerAndStringIdsAreEquivalentAndSorted() {
        WaterCardScope a = ok("{\"scopeType\":\"specified\",\"stationIds\":[3,1,2]}");
        WaterCardScope b = ok("{\"scopeType\":\"specified\",\"stationIds\":[\"2\",\"3\",\"1\"]}");
        assertEquals(List.of("1", "2", "3"), a.stationIds());
        assertTrue(a.sameAuthorityAs(b), "历史整数与新字符串必须判为同一授权");
    }

    // 展示名：存在时必须字符串数组且与 ID 等长；不参与授权比较
    @Test
    void displayNamesValidatedButNotCompared() {
        reject("{\"scopeType\":\"specified\",\"stationIds\":[1,2],\"stationNames\":[\"甲\"]}");
        reject("{\"scopeType\":\"specified\",\"stationIds\":[1],\"stationNames\":[1]}");
        WaterCardScope withNames = ok("{\"scopeType\":\"specified\",\"stationIds\":[1],\"stationNames\":[\"甲\"]}");
        WaterCardScope without = ok("{\"scopeType\":\"specified\",\"stationIds\":[1]}");
        assertTrue(withNames.sameAuthorityAs(without), "展示名不得影响授权相等");
    }

    // 授权相等：不取并集、不取交集、不做表面字符串相等
    @Test
    void authorityEqualityIsExact() {
        WaterCardScope base = ok("{\"scopeType\":\"specified\",\"stationIds\":[1,2]}");
        assertTrue(base.sameAuthorityAs(base), "自反性");
        assertFalse(base.sameAuthorityAs(null), "null 不得判为相等");
        assertFalse(base.sameAuthorityAs(ok("{\"scopeType\":\"specified\",\"stationIds\":[1]}")),
                "子集不得判为相等（否则充值可悄悄缩/扩范围）");
        assertFalse(base.sameAuthorityAs(ok("{\"scopeType\":\"specified\",\"stationIds\":[1,2,3]}")),
                "超集不得判为相等");
        assertFalse(base.sameAuthorityAs(ok("{\"scopeType\":\"all\"}")),
                "all 与 specified 不得互认");
        assertFalse(base.sameAuthorityAs(ok("{\"scopeType\":\"specified\",\"deviceIds\":[1,2]}")),
                "不同维度不得互认");
        assertTrue(base.sameAuthorityAs(ok("{\"scopeType\":\"specified\",\"stationIds\":[2,1]}")),
                "仅顺序不同应判为相等");
    }

    // deviceIds / outletIds 的相等判定必须同样有牙齿（原用例只改 stationIds，删掉这两维比较不会红）
    @Test
    void deviceAndOutletDimensionsAlsoCompared() {
        WaterCardScope base = ok("{\"scopeType\":\"specified\",\"stationIds\":[1],\"deviceIds\":[7],\"outletIds\":[9]}");
        assertFalse(base.sameAuthorityAs(
                ok("{\"scopeType\":\"specified\",\"stationIds\":[1],\"deviceIds\":[8],\"outletIds\":[9]}")),
                "仅 deviceIds 不同必须判为不等");
        assertFalse(base.sameAuthorityAs(
                ok("{\"scopeType\":\"specified\",\"stationIds\":[1],\"deviceIds\":[7],\"outletIds\":[10]}")),
                "仅 outletIds 不同必须判为不等");
        assertFalse(base.sameAuthorityAs(
                ok("{\"scopeType\":\"specified\",\"stationIds\":[1],\"deviceIds\":[7]}")),
                "少一维必须判为不等");
    }

    // 不推导层级：站点授权不得被解释为覆盖其下设备
    @Test
    void noHierarchyInference() {
        WaterCardScope station = ok("{\"scopeType\":\"specified\",\"stationIds\":[1]}");
        WaterCardScope device = ok("{\"scopeType\":\"specified\",\"deviceIds\":[1]}");
        assertFalse(station.sameAuthorityAs(device), "层级模型未冻结前不得推导包含关系");
    }
}

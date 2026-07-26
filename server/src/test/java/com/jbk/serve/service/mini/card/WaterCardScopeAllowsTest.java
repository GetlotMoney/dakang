package com.jbk.serve.service.mini.card;

import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WaterCardScope.allows 命中矩阵（CARD-SCOPE 语义冻结）。
 *
 * <p>核心证伪目标是 AND 语义：站命中但设备不命中必须拒——这条没了就是
 * 「水站命中绕过设备限制」，限定到某台设备的卡会在同站任何设备可用。
 * 空范围/非法 JSON 到不了 allows（normalize 已拒绝），这里一并钉住该前置。</p>
 */
class WaterCardScopeAllowsTest {

    private static final Long S1 = 1L;
    private static final Long S2 = 2L;
    private static final Long D7 = 7L;
    private static final Long D8 = 8L;
    private static final Long O9 = 9L;
    private static final Long O10 = 10L;

    private WaterCardScope scope(String json) {
        return WaterCardScope.normalize(json, "水卡");
    }

    // ---------- all ----------

    @Test
    void allAllowsEverything() {
        WaterCardScope all = scope("{\"scopeType\":\"all\"}");
        assertTrue(all.allows(S1, D7, O9));
        assertTrue(all.allows(S2, D8, O10));
    }

    // ---------- 只有 stationIds：允许该站全部设备/出水口 ----------

    @Test
    void stationOnlyAllowsWholeStationButNotOtherStations() {
        WaterCardScope s = scope("{\"scopeType\":\"specified\",\"stationIds\":[1]}");
        assertTrue(s.allows(S1, D7, O9), "站命中：该站任意设备/出水口放行");
        assertTrue(s.allows(S1, D8, O10), "站命中：设备/出水口维度未配置=不设限");
        assertFalse(s.allows(S2, D7, O9), "站不命中必须拒");
        assertFalse(s.allows(null, D7, O9), "受限维度传 null 必须拒（fail-closed）");
    }

    // ---------- stationIds + deviceIds：两者都要命中（AND 证伪核心） ----------

    @Test
    void stationHitButDeviceMissMustBeDenied() {
        WaterCardScope s = scope("{\"scopeType\":\"specified\",\"stationIds\":[1],\"deviceIds\":[7]}");
        // AND 证伪：站命中但设备不命中必须拒。改成 OR（任一维命中即放行）本断言立刻变红。
        assertFalse(s.allows(S1, D8, O9), "站命中但设备不命中必须拒（禁止 OR）");
        assertFalse(s.allows(S2, D7, O9), "设备命中但站不命中同样拒");
        assertTrue(s.allows(S1, D7, O9), "两维同时命中才放行");
        assertTrue(s.allows(S1, D7, O10), "出水口维度未配置=不设限");
    }

    // ---------- 三维全给：三者都要命中 ----------

    @Test
    void allThreeDimensionsMustHitTogether() {
        WaterCardScope s = scope(
                "{\"scopeType\":\"specified\",\"stationIds\":[1],\"deviceIds\":[7],\"outletIds\":[9]}");
        assertTrue(s.allows(S1, D7, O9));
        assertFalse(s.allows(S1, D7, O10), "站+设备命中但出水口不命中必须拒");
        assertFalse(s.allows(S1, D8, O9), "站+出水口命中但设备不命中必须拒");
        assertFalse(s.allows(S2, D7, O9), "设备+出水口命中但站不命中必须拒");
        assertFalse(s.allows(S1, D7, null), "受限出水口维度传 null 必须拒");
    }

    // ---------- 只有 outletIds：仅指定出水口 ----------

    @Test
    void outletOnlyAllowsExactOutletsRegardlessOfStationDevice() {
        WaterCardScope s = scope("{\"scopeType\":\"specified\",\"outletIds\":[9]}");
        assertTrue(s.allows(S1, D7, O9));
        assertTrue(s.allows(S2, D8, O9), "站/设备维度未配置=不设限");
        assertFalse(s.allows(S1, D7, O10), "出水口不在名单必须拒");
    }

    // ---------- 只有 deviceIds ----------

    @Test
    void deviceOnlyAllowsWholeDevice() {
        WaterCardScope s = scope("{\"scopeType\":\"specified\",\"deviceIds\":[7]}");
        assertTrue(s.allows(S1, D7, O9));
        assertTrue(s.allows(S2, D7, O10), "站/出水口维度未配置=不设限");
        assertFalse(s.allows(S1, D8, O9));
    }

    // ---------- 多值集合命中 ----------

    @Test
    void multiValueDimensionsMatchAnyListedId() {
        WaterCardScope s = scope("{\"scopeType\":\"specified\",\"stationIds\":[1,2],\"deviceIds\":[7,8]}");
        assertTrue(s.allows(S1, D8, O9));
        assertTrue(s.allows(S2, D7, O10));
        assertFalse(s.allows(S2, 99L, O9), "集合外设备必须拒");
    }

    // ---------- 不推导层级 ----------

    @Test
    void noHierarchyInferenceInAllows() {
        // 站授权不因「设备属于该站」而扩散成设备授权判定的替代——deviceIds 一旦配置就必须精确命中。
        WaterCardScope s = scope("{\"scopeType\":\"specified\",\"stationIds\":[1],\"deviceIds\":[7]}");
        assertFalse(s.allows(S1, D8, O9), "同站其他设备不得因层级关系被放行");
    }

    // ---------- 空范围/非法 JSON/未知字段/重复/越界 ID 到不了 allows：normalize 全拒 ----------

    @Test
    void invalidScopesAreRejectedBeforeAllows() {
        assertThrows(JbkException.class, () -> scope(null));
        assertThrows(JbkException.class, () -> scope(""));
        assertThrows(JbkException.class, () -> scope("not-json"));
        assertThrows(JbkException.class, () -> scope("{}"));
        assertThrows(JbkException.class, () -> scope("{\"scopeType\":\"specified\"}"));
        assertThrows(JbkException.class, () -> scope("{\"scopeType\":\"all\",\"foo\":1}"));
        assertThrows(JbkException.class,
                () -> scope("{\"scopeType\":\"specified\",\"stationIds\":[1,1]}"));
        assertThrows(JbkException.class,
                () -> scope("{\"scopeType\":\"specified\",\"stationIds\":[\"99999999999999999999\"]}"));
        assertThrows(JbkException.class,
                () -> scope("{\"scopeType\":\"specified\",\"groupIds\":[1]}"),
                "机组层级未冻结，未知维度必须 fail-closed");
    }
}

package com.jbk.serve.service.mini.card;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.jbk.tool.exception.JbkException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 水卡可用范围（SCOPE_JSON）的唯一规范化表示与命中判定（L2 契约 §2.2 + CARD-SCOPE）。
 * 仓内不得出现第二套解析器——口径漂移即范围绕过缺陷。只有 scopeType + 三个 ID 集合
 * 参与授权比较，展示名不参与；不推导层级关系，未登记字段一律 fail-closed 拒绝。
 * 空范围不是「全场通用」而是「未配置默认拒绝」，null/空串直接抛出、绝不静默放行。
 */
public final class WaterCardScope {

    /** 授权判定字段（顶层只允许这些 + 三个展示名数组）。 */
    private static final String F_TYPE = "scopeType";
    private static final String F_STATIONS = "stationIds";
    private static final String F_DEVICES = "deviceIds";
    private static final String F_OUTLETS = "outletIds";
    /** 仅展示、不参与授权比较，但存在时必须与对应 ID 数组等长。 */
    private static final String F_STATION_NAMES = "stationNames";
    private static final String F_DEVICE_NAMES = "deviceNames";
    private static final String F_OUTLET_LABELS = "outletLabels";

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            F_TYPE, F_STATIONS, F_DEVICES, F_OUTLETS, F_STATION_NAMES, F_DEVICE_NAMES, F_OUTLET_LABELS);

    public static final String TYPE_ALL = "all";
    public static final String TYPE_SPECIFIED = "specified";

    /** 正十进制整数（无前导零、无符号、无小数、无指数）。 */
    private static final java.util.regex.Pattern DECIMAL_ID =
            java.util.regex.Pattern.compile("^[1-9]\\d*$");

    private final String scopeType;
    private final List<String> stationIds;
    private final List<String> deviceIds;
    private final List<String> outletIds;

    private WaterCardScope(String scopeType, List<String> stationIds, List<String> deviceIds, List<String> outletIds) {
        this.scopeType = scopeType;
        this.stationIds = stationIds;
        this.deviceIds = deviceIds;
        this.outletIds = outletIds;
    }

    public String scopeType() {
        return scopeType;
    }

    public List<String> stationIds() {
        return stationIds;
    }

    public List<String> deviceIds() {
        return deviceIds;
    }

    public List<String> outletIds() {
        return outletIds;
    }

    /**
     * 规范化范围 JSON。任一不合规一律抛 {@link JbkException}，绝不"尽力解析"。
     *
     * @param json  原始 SCOPE_JSON
     * @param label 出错提示用的对象名（如「水卡」「套餐」）
     */
    public static WaterCardScope normalize(String json, String label) {
        if (json == null || json.trim().isEmpty()) {
            // 空=未配置=默认拒绝（DDL 既有语义），不得被解释为全场通用。
            throw new JbkException(label + "可用范围未配置，默认拒绝");
        }
        JSONObject obj;
        try {
            Object parsed = JSON.parse(json);
            if (!(parsed instanceof JSONObject)) {
                throw new JbkException(label + "可用范围格式非法");
            }
            obj = (JSONObject) parsed;
        } catch (JbkException e) {
            throw e;
        } catch (Exception e) {
            throw new JbkException(label + "可用范围不是合法 JSON");
        }

        for (String key : obj.keySet()) {
            if (!ALLOWED_FIELDS.contains(key)) {
                // 未知顶层字段 fail-closed：层级/新语义未冻结前不得静默忽略。
                throw new JbkException(label + "可用范围含未知字段 " + key + "，拒绝");
            }
        }

        String type = obj.getString(F_TYPE);
        if (!TYPE_ALL.equals(type) && !TYPE_SPECIFIED.equals(type)) {
            throw new JbkException(label + "可用范围 scopeType 非法");
        }

        List<String> stations = ids(obj, F_STATIONS, label);
        List<String> devices = ids(obj, F_DEVICES, label);
        List<String> outlets = ids(obj, F_OUTLETS, label);

        requireNamesAligned(obj, F_STATION_NAMES, stations.size(), label);
        requireNamesAligned(obj, F_DEVICE_NAMES, devices.size(), label);
        requireNamesAligned(obj, F_OUTLET_LABELS, outlets.size(), label);

        if (TYPE_ALL.equals(type)) {
            if (!stations.isEmpty() || !devices.isEmpty() || !outlets.isEmpty()) {
                throw new JbkException(label + "可用范围 scopeType=all 时不得指定具体 ID");
            }
        } else if (stations.isEmpty() && devices.isEmpty() && outlets.isEmpty()) {
            throw new JbkException(label + "可用范围 scopeType=specified 时至少需要一个 ID");
        }
        return new WaterCardScope(type, stations, devices, outlets);
    }

    /** 解析并规范化一个 ID 数组：升序、去重校验、Long 范围校验，统一输出十进制字符串。 */
    private static List<String> ids(JSONObject obj, String field, String label) {
        if (!obj.containsKey(field) || obj.get(field) == null) {
            return List.of();
        }
        Object raw = obj.get(field);
        if (!(raw instanceof JSONArray arr)) {
            throw new JbkException(label + "可用范围 " + field + " 必须是数组");
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (Object item : arr) {
            long value = toPositiveLong(item, field, label);
            if (!seen.add(value)) {
                throw new JbkException(label + "可用范围 " + field + " 存在重复 ID");
            }
        }
        List<Long> sorted = new ArrayList<>(seen);
        sorted.sort(Long::compareTo);
        return sorted.stream().map(String::valueOf).toList();
    }

    /**
     * 元素只允许正十进制整数：兼容历史 JSON 整数，字符串必须是规范十进制。
     * 0/负数/小数/指数/空串/越界/布尔/嵌套一律拒绝。
     */
    private static long toPositiveLong(Object item, String field, String label) {
        if (item instanceof Integer || item instanceof Long) {
            long v = ((Number) item).longValue();
            if (v <= 0) {
                throw new JbkException(label + "可用范围 " + field + " 含非正整数 ID");
            }
            return v;
        }
        if (item instanceof String s) {
            if (!DECIMAL_ID.matcher(s).matches()) {
                throw new JbkException(label + "可用范围 " + field + " 含非十进制正整数 ID：" + s);
            }
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                // 位数超过 Long 范围
                throw new JbkException(label + "可用范围 " + field + " 的 ID 越界：" + s);
            }
        }
        // BigDecimal/Double/Boolean/嵌套结构/null 一律拒绝（含 1.0 这类"看起来是整数"的小数）。
        throw new JbkException(label + "可用范围 " + field + " 含非法 ID 类型");
    }

    /** 展示名数组若存在：必须是字符串数组且与对应 ID 数组等长（不参与授权比较）。 */
    private static void requireNamesAligned(JSONObject obj, String field, int idCount, String label) {
        if (!obj.containsKey(field) || obj.get(field) == null) {
            return;
        }
        Object raw = obj.get(field);
        if (!(raw instanceof JSONArray arr)) {
            throw new JbkException(label + "可用范围 " + field + " 必须是数组");
        }
        if (arr.size() != idCount) {
            throw new JbkException(label + "可用范围 " + field + " 与对应 ID 数组长度不一致");
        }
        for (Object item : arr) {
            if (!(item instanceof String)) {
                throw new JbkException(label + "可用范围 " + field + " 含非字符串元素");
            }
        }
    }

    /**
     * 「站-设备-出水口」三元组命中判定（取水链唯一出处）：all→允许；specified→已配置的维度
     * 必须同时命中（AND，禁止 OR——OR 会让站命中绕过设备限制），未配置的维度不设限。
     * 受限维度传入 null 一律拒绝（fail-closed）；不推导层级，站授权不扩散成设备授权。
     */
    public boolean allows(Long stationId, Long deviceId, Long outletId) {
        if (TYPE_ALL.equals(scopeType)) {
            return true;
        }
        // specified：normalize 已保证至少一维非空，逐维 AND；未配置的维度不设限。
        return dimensionAllows(stationIds, stationId)
                && dimensionAllows(deviceIds, deviceId)
                && dimensionAllows(outletIds, outletId);
    }

    /** 单维命中：该维未配置=不设限；已配置则实际 ID 必须非空且在集合内（集合元素为规范十进制串）。 */
    private static boolean dimensionAllows(List<String> allowedIds, Long actual) {
        if (allowedIds.isEmpty()) {
            return true;
        }
        return actual != null && allowedIds.contains(String.valueOf(actual));
    }

    /**
     * 授权语义精确相等：仅比较 scopeType 与三个 ID 集合（均已规范化排序）。
     * 不取并集、不取交集、不做字符串表面相等。
     */
    public boolean sameAuthorityAs(WaterCardScope other) {
        return other != null
                && Objects.equals(scopeType, other.scopeType)
                && stationIds.equals(other.stationIds)
                && deviceIds.equals(other.deviceIds)
                && outletIds.equals(other.outletIds);
    }

    /**
     * 已规范化范围的人类可读摘要。只描述本对象中已经过严格校验的 ID 集合，
     * 不再次解析 JSON，也不参与授权判定。
     */
    public String description() {
        if (TYPE_ALL.equals(scopeType)) {
            return "全场通用";
        }
        StringBuilder result = new StringBuilder("限定范围：");
        if (!stationIds.isEmpty()) {
            result.append("水站 ").append(stationIds.size()).append(" 个 ");
        }
        if (!deviceIds.isEmpty()) {
            result.append("设备 ").append(deviceIds.size()).append(" 台 ");
        }
        if (!outletIds.isEmpty()) {
            result.append("出水口 ").append(outletIds.size()).append(" 个");
        }
        return result.toString().trim();
    }

    /** 规范化后的授权指纹（用于快照写入与比对展示）。 */
    public JSONObject toCanonicalJson() {
        JSONObject o = new JSONObject(true);
        o.put(F_TYPE, scopeType);
        o.put(F_STATIONS, stationIds);
        o.put(F_DEVICES, deviceIds);
        o.put(F_OUTLETS, outletIds);
        return o;
    }

    @Override
    public String toString() {
        return toCanonicalJson().toJSONString();
    }
}

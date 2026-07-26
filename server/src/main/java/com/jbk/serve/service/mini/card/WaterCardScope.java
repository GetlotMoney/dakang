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
 * 水卡可用范围（SCOPE_JSON）的**唯一**规范化表示、授权语义相等判定与命中判定
 * （L2 契约 §2.2 范围规范化算法 + CARD-SCOPE 命中语义）。
 *
 * <p>充值、首次购卡、卡详情、扫码取水四条链统一复用本类；仓内不得再出现第二套
 * SCOPE_JSON 解析器——两套解析器一旦口径漂移，就会出现「充值校验通过、取水放行口径不同」
 * 的范围绕过缺陷。</p>
 *
 * <p>只有 {@code scopeType + 三个 ID 集合} 参与授权相等比较；展示名称（stationNames 等）
 * 仅作展示、不参与判断。本类不推导「站点—设备—出水口」层级关系——层级模型确认前，
 * 任何未登记字段一律 fail-closed 拒绝。</p>
 *
 * <p>空范围**不是**「全场通用」：数据库既有语义为「未配置并默认拒绝」，
 * 因此 {@code null}/空串在本类直接拒绝，由调用方决定如何处理，绝不静默放行。</p>
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
     * 「站-设备-出水口」三元组是否落在本范围内（CARD-SCOPE 语义冻结，取水链下单/预检唯一命中判定）：
     * {@code all}→允许；{@code specified}→**所有非空维度必须同时命中（AND，禁止 OR）**——
     * 只配 stationIds 时命中该站即放行其下全部设备/出水口（该两维未配置=不设限）；
     * stationIds+deviceIds 时两者都要命中；三维全给则三者都要命中；只配 outletIds 则仅指定出水口。
     *
     * <p>为什么必须 AND：若任一维命中即放行（OR），「站命中但设备不命中」会被放行，
     * 等于水站命中绕过设备限制，限定到设备/出水口的卡将在同站任意设备可用。</p>
     *
     * <p>受限维度传入 {@code null} 一律拒绝（fail-closed）；空范围/非法 JSON 到不了本方法——
     * {@link #normalize} 已拒绝。本方法不推导层级：站授权不因层级关系扩散成设备授权、反之亦然。</p>
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

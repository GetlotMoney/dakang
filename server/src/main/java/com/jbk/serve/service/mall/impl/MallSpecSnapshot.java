package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jbk.tool.exception.JbkException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SKU 规格快照唯一编解码器（E2E-09 R1-P1-3）。
 *
 * <p>写读同约束：JSON 对象、纯 string→string、≤{@value #MAX_ENTRIES} 键、
 * 键≤{@value #MAX_KEY_LEN} 字、值≤{@value #MAX_VALUE_LEN} 字、原文≤{@value #MAX_JSON_LEN} 字。
 * 读取严格 fail-closed：损坏 JSON、嵌套对象、数组、数字/布尔/null 值一律按快照
 * 损坏抛出——此前读取回空对象或 String.valueOf 强转，会把损坏快照渲染成"无规格/
 * 假规格"的正常商品（管理端与小程序端同病），损坏面被静默吞掉。</p>
 *
 * <p>库列 SPEC_SNAP 为 NOT NULL 且写入口最少落 {@code {}}：空串/NULL 同样按损坏处理。</p>
 */
final class MallSpecSnapshot {

    static final int MAX_ENTRIES = 8;
    static final int MAX_KEY_LEN = 20;
    static final int MAX_VALUE_LEN = 50;
    static final int MAX_JSON_LEN = 500;

    private MallSpecSnapshot() {
    }

    /**
     * 扁平规格键值 → SPEC_SNAP 原文。空 Map/null 落 {@code {}}；违约抛出（写侧用户可见提示）。
     */
    static String encode(Map<String, String> specs) {
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        if (specs != null) {
            if (specs.size() > MAX_ENTRIES) {
                throw new JbkException("规格键不能超过 " + MAX_ENTRIES + " 个");
            }
            for (Map.Entry<String, String> entry : specs.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().trim();
                String value = entry.getValue() == null ? "" : entry.getValue().trim();
                if (key.isEmpty() || value.isEmpty()) {
                    throw new JbkException("规格键值不能为空");
                }
                if (key.length() > MAX_KEY_LEN || value.length() > MAX_VALUE_LEN) {
                    throw new JbkException("规格键不超过 " + MAX_KEY_LEN + " 字、值不超过 "
                            + MAX_VALUE_LEN + " 字");
                }
                normalized.put(key, value);
            }
        }
        String json = JSONUtil.toJsonStr(normalized);
        if (json.length() > MAX_JSON_LEN) {
            throw new JbkException("规格内容过长");
        }
        return json;
    }

    /**
     * SPEC_SNAP 原文 → 扁平规格键值。与写入口同约束，任何违约=快照损坏，整次读取失败。
     */
    static Map<String, String> decode(String specSnap) {
        if (ObjectUtil.isEmpty(specSnap)) {
            throw new JbkException("SKU 规格快照缺失，数据损坏请人工核查");
        }
        if (specSnap.length() > MAX_JSON_LEN) {
            throw new JbkException("SKU 规格快照超长，数据损坏请人工核查");
        }
        JSONObject obj;
        try {
            obj = JSONUtil.parseObj(specSnap);
        }
        catch (Exception broken) {
            // 非对象（数组/裸值）与损坏 JSON 都在这里失败
            throw new JbkException("SKU 规格快照损坏（非法 JSON），请人工核查");
        }
        if (obj.size() > MAX_ENTRIES) {
            throw new JbkException("SKU 规格快照键数超限，数据损坏请人工核查");
        }
        LinkedHashMap<String, String> specs = new LinkedHashMap<>();
        for (String key : obj.keySet()) {
            Object value = obj.get(key);
            // 只认纯字符串值：数字/布尔/null/嵌套对象/数组都是写入口不可能产出的形态
            if (!(value instanceof String text)) {
                throw new JbkException("SKU 规格快照值形态非法，数据损坏请人工核查");
            }
            if (key == null || key.trim().isEmpty() || text.trim().isEmpty()) {
                throw new JbkException("SKU 规格快照键值为空，数据损坏请人工核查");
            }
            if (key.length() > MAX_KEY_LEN || text.length() > MAX_VALUE_LEN) {
                throw new JbkException("SKU 规格快照键值超长，数据损坏请人工核查");
            }
            specs.put(key, text);
        }
        return specs;
    }
}

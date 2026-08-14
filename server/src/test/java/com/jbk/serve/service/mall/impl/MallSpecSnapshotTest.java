package com.jbk.serve.service.mall.impl;

import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 规格快照唯一编解码器（R1-P1-3）：写读同约束、读取 fail-closed。
 *
 * <p>对抗面向此前的 fail-open 实现：损坏 JSON 回空对象、String.valueOf 把
 * number/boolean/嵌套强转成字符串——六类损坏形态现在必须逐一抛出。</p>
 */
@DisplayName("规格快照编解码器")
class MallSpecSnapshotTest {

    @Test
    @DisplayName("往返一致：合法扁平键值 编码→解码 逐字还原，空规格落 {}")
    void roundTripKeepsEntries() {
        LinkedHashMap<String, String> specs = new LinkedHashMap<>();
        specs.put("规格", "18.9L");
        specs.put("口味", "原味");
        String snap = MallSpecSnapshot.encode(specs);
        assertEquals(specs, MallSpecSnapshot.decode(snap));
        assertEquals("{}", MallSpecSnapshot.encode(null));
        assertEquals(Map.of(), MallSpecSnapshot.decode("{}"), "空对象合法（无规格 SKU）");
    }

    @Test
    @DisplayName("对抗①损坏 JSON：截断原文/裸值/数组一律拒绝")
    void corruptJsonRejected() {
        for (String bad : new String[]{"{broken", "not-json", "\"just-text\"", "[\"18.9L\"]", "123"}) {
            assertThrows(JbkException.class, () -> MallSpecSnapshot.decode(bad),
                    "应拒绝损坏原文：" + bad);
        }
    }

    @Test
    @DisplayName("对抗②嵌套对象：值为对象拒绝（不再被 String.valueOf 强转）")
    void nestedObjectRejected() {
        assertThrows(JbkException.class,
                () -> MallSpecSnapshot.decode("{\"规格\":{\"内层\":\"x\"}}"));
        assertThrows(JbkException.class,
                () -> MallSpecSnapshot.decode("{\"规格\":[\"x\"]}"), "值为数组同样拒绝");
    }

    @Test
    @DisplayName("对抗③数字值与布尔值：拒绝（写入口不可能产出）")
    void numberAndBooleanValuesRejected() {
        assertThrows(JbkException.class, () -> MallSpecSnapshot.decode("{\"规格\":18.9}"));
        assertThrows(JbkException.class, () -> MallSpecSnapshot.decode("{\"规格\":24}"));
        assertThrows(JbkException.class, () -> MallSpecSnapshot.decode("{\"有糖\":true}"));
    }

    @Test
    @DisplayName("对抗④null 值与空串/缺失原文：拒绝（列 NOT NULL，写入口最少落 {}）")
    void nullValueAndEmptySnapshotRejected() {
        assertThrows(JbkException.class, () -> MallSpecSnapshot.decode("{\"规格\":null}"));
        assertThrows(JbkException.class, () -> MallSpecSnapshot.decode(""));
        assertThrows(JbkException.class, () -> MallSpecSnapshot.decode(null));
        assertThrows(JbkException.class, () -> MallSpecSnapshot.decode("{\"规格\":\"  \"}"),
                "空白值拒绝");
    }

    @Test
    @DisplayName("对抗⑤超键数：读取与写入同界（>8 键拒绝）")
    void tooManyEntriesRejectedBothWays() {
        StringBuilder nine = new StringBuilder("{");
        for (int i = 0; i < 9; i++) {
            nine.append(i > 0 ? "," : "").append("\"键").append(i).append("\":\"值\"");
        }
        nine.append("}");
        assertThrows(JbkException.class, () -> MallSpecSnapshot.decode(nine.toString()));

        LinkedHashMap<String, String> tooMany = new LinkedHashMap<>();
        for (int i = 0; i < 9; i++) {
            tooMany.put("键" + i, "值");
        }
        assertThrows(JbkException.class, () -> MallSpecSnapshot.encode(tooMany));
    }

    @Test
    @DisplayName("对抗⑥超长键/值：读取与写入同界（键>20 或值>50 拒绝）")
    void oversizeKeyValueRejectedBothWays() {
        String longKey = "键".repeat(21);
        String longValue = "值".repeat(51);
        assertThrows(JbkException.class,
                () -> MallSpecSnapshot.decode("{\"" + longKey + "\":\"值\"}"));
        assertThrows(JbkException.class,
                () -> MallSpecSnapshot.decode("{\"键\":\"" + longValue + "\"}"));
        assertThrows(JbkException.class,
                () -> MallSpecSnapshot.encode(Map.of(longKey, "值")));
        assertThrows(JbkException.class,
                () -> MallSpecSnapshot.encode(Map.of("键", longValue)));
        // 原文整体超 500 字也按损坏处理
        assertThrows(JbkException.class,
                () -> MallSpecSnapshot.decode("{\"键\":\"" + "x".repeat(600) + "\"}"));
    }
}

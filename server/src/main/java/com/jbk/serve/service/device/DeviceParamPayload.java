package com.jbk.serve.service.device;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.jbk.tool.exception.JbkException;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 设备参数报文的唯一解析与校验实现（REQ-213-S1）。
 *
 * <p>单发（{@code IWsCommandService.send}）与批量（{@code IDeviceControlService.preview/confirm}）
 * 必须共用本类。此前两处各自只调 {@code JSONUtil.isTypeJSON}，宽严一致纯属巧合——
 * 只要有一天其中一处放松，运营就能从那一处把畸形报文发到设备上。</p>
 *
 * <h3>两层校验，边界刻意分开</h3>
 * <ol>
 *   <li><b>结构校验，恒生效，与厂家无关。</b>扁平 JSON 对象、键名受限、值只允许标量、
 *       禁嵌套禁 null、键数与串长有上限。这些规则拦的是我方运营手滑与畸形报文，
 *       不需要等任何外部确认就能定死。</li>
 *   <li><b>登记校验，登记即生效。</b>键在 {@code ws_device_param_def} 登记且启用时，
 *       额外校验类型/值域/枚举。未登记的键<b>放行</b>但标记出来。</li>
 * </ol>
 *
 * <h3>为什么未登记键放行而不是 fail-closed</h3>
 * <p>今天参数同步接受任意合法 JSON，这是 E2E-05 已验收的能力。若引入白名单并对空注册表
 * fail-closed，等于把已验收能力打回不可用——那是回归，不是加固。因此这里采用<b>单调收紧</b>：
 * 空注册表 == 今天的行为；登记一个键只会让它更严，永远不会更松。业务参数键在厂家答复
 * V-12.3 之前保持零登记，平台不发明设备参数。</p>
 *
 * <p>注意这与 B20 未登记故障码 fail-closed 的取向不同，且是刻意的：故障码是<b>设备上报</b>，
 * 未知码意味着平台看不懂设备状态，放行就是拿用户安全赌；参数是<b>平台下发</b>，
 * 下发内容由运营在受控后台产生，且真正的执行方（设备）自己会拒绝不认识的键。</p>
 */
public final class DeviceParamPayload {

    /** 键名：字母开头，字母数字下划线，总长 ≤32。 */
    private static final Pattern KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,31}$");
    private static final int MAX_KEYS = 32;
    private static final int MAX_VALUE_LEN = 64;

    /** 值类型（对齐 ws_device_param_def.VALUE_TYPE）。 */
    public static final int TYPE_INT = 1;
    public static final int TYPE_DECIMAL = 2;
    public static final int TYPE_STRING = 3;
    public static final int TYPE_BOOL = 4;

    private DeviceParamPayload() {
    }

    /**
     * 一条参数定义（由调用方从 {@code ws_device_param_def} 取，本类不碰数据库——
     * 保持纯函数便于直接单测，也避免校验器绑死在某个 Mapper 上）。
     */
    public record Definition(String paramKey, int valueType, String valueUnit,
                             String valueMin, String valueMax, String valueEnum) {
    }

    /** 校验结果：规范化后的键值对（保持下发顺序），以及其中未登记的键。 */
    public record Checked(Map<String, String> values, java.util.Set<String> unregisteredKeys) {

        public boolean allRegistered() {
            return unregisteredKeys.isEmpty();
        }
    }

    /**
     * 结构校验（恒生效）+ 登记校验（对已登记键生效）。
     *
     * @param payload 平台下发的 CMD_PAYLOAD 原文
     * @param defs    已登记且启用的定义，key 为 paramKey；可以为空 Map
     * @return 规范化结果
     * @throws JbkException 任一条不满足即拒绝，异常文案指出具体是哪个键的哪一条
     */
    public static Checked require(String payload, Map<String, Definition> defs) {
        Map<String, Definition> registry = defs == null ? Collections.emptyMap() : defs;
        if (StrUtil.isBlank(payload)) {
            throw new JbkException("参数报文不能为空");
        }
        JSON parsed;
        try {
            // 必须显式关掉 ignoreNullValue：Hutool 默认在**解析阶段**就把 null 值的键整个丢掉，
            // {"a":1,"b":null} 会变成 {"a":1}，下面的禁 null 分支永远够不到，
            // 而含 null 的原文照样落库并 publish 给设备——「禁止 null」就成了一句空话。
            parsed = JSONUtil.parse(payload, JSONConfig.create().setIgnoreNullValue(false));
        }
        catch (Exception malformed) {
            throw new JbkException("参数报文不是合法 JSON");
        }
        if (!(parsed instanceof JSONObject root)) {
            // 数组/裸标量都不行：参数是键值对，不是列表
            throw new JbkException("参数报文必须是 JSON 对象");
        }
        if (root.isEmpty()) {
            throw new JbkException("参数报文不能是空对象");
        }
        if (root.size() > MAX_KEYS) {
            throw new JbkException("参数项过多（" + root.size() + "），单次最多 " + MAX_KEYS + " 项");
        }

        Map<String, String> values = new LinkedHashMap<>();
        java.util.Set<String> unregistered = new java.util.LinkedHashSet<>();
        for (String key : root.keySet()) {
            if (!KEY.matcher(StrUtil.nullToEmpty(key)).matches()) {
                throw new JbkException("参数键名非法（" + key + "）：须字母开头、仅含字母数字下划线且不超过 32 位");
            }
            Object raw = root.get(key);
            String text = requireScalar(key, raw);
            Definition def = registry.get(key);
            if (def == null) {
                unregistered.add(key);
            }
            else {
                requireByDefinition(key, text, raw, def);
            }
            values.put(key, text);
        }
        return new Checked(Collections.unmodifiableMap(values), Collections.unmodifiableSet(unregistered));
    }

    /** 值必须是标量：字符串/数字/布尔。null、对象、数组一律拒绝。 */
    private static String requireScalar(String key, Object raw) {
        if (raw == null || raw == JSONNull.INSTANCE) {
            throw new JbkException("参数 " + key + " 的值不能为空");
        }
        if (raw instanceof JSONObject || raw instanceof JSONArray) {
            throw new JbkException("参数 " + key + " 的值必须是标量，不支持嵌套对象或数组");
        }
        String text = String.valueOf(raw);
        if (text.length() > MAX_VALUE_LEN) {
            throw new JbkException("参数 " + key + " 的值过长（" + text.length()
                    + "），最多 " + MAX_VALUE_LEN + " 字符");
        }
        return text;
    }

    /** 已登记键的类型、值域与枚举校验。 */
    private static void requireByDefinition(String key, String text, Object raw, Definition def) {
        switch (def.valueType()) {
            // 数值类必须先看 JSON 原始类型：只比对文本形态会让 "5000" 这个字符串
            // 冒充整数通过（引号在 JSON 里是有语义的，设备侧按类型解析会直接分歧）
            case TYPE_INT -> {
                if (!(raw instanceof Number) || !text.matches("^-?(?:0|[1-9]\\d*)$")) {
                    throw new JbkException("参数 " + key + " 必须是整数（实际 " + describe(raw, text) + "）");
                }
                requireRange(key, new BigDecimal(text), def);
            }
            case TYPE_DECIMAL -> {
                if (!(raw instanceof Number) || !text.matches("^-?(?:0|[1-9]\\d*)(?:\\.\\d+)?$")) {
                    throw new JbkException("参数 " + key + " 必须是数值（实际 " + describe(raw, text) + "）");
                }
                requireRange(key, new BigDecimal(text), def);
            }
            case TYPE_BOOL -> {
                // 只认真正的 JSON 布尔，不把 "true" 字符串当布尔——类型模糊会让设备侧解析出分歧
                if (!(raw instanceof Boolean)) {
                    throw new JbkException("参数 " + key + " 必须是布尔值（实际 " + text + "）");
                }
            }
            case TYPE_STRING -> {
                if (raw instanceof Boolean || raw instanceof Number) {
                    throw new JbkException("参数 " + key + " 必须是字符串（实际 " + text + "）");
                }
            }
            default -> throw new JbkException("参数 " + key + " 的定义值类型非法（" + def.valueType() + "）");
        }
        requireEnum(key, text, def);
    }

    private static void requireRange(String key, BigDecimal value, Definition def) {
        if (StrUtil.isNotBlank(def.valueMin())) {
            BigDecimal min = parseBound(key, def.valueMin(), "下限");
            if (value.compareTo(min) < 0) {
                throw new JbkException("参数 " + key + " 低于允许下限 " + def.valueMin() + unitSuffix(def));
            }
        }
        if (StrUtil.isNotBlank(def.valueMax())) {
            BigDecimal max = parseBound(key, def.valueMax(), "上限");
            if (value.compareTo(max) > 0) {
                throw new JbkException("参数 " + key + " 超出允许上限 " + def.valueMax() + unitSuffix(def));
            }
        }
    }

    private static void requireEnum(String key, String text, Definition def) {
        if (StrUtil.isBlank(def.valueEnum())) {
            return;
        }
        for (String allowed : def.valueEnum().split(",")) {
            if (StrUtil.equals(text, allowed.trim())) {
                return;
            }
        }
        throw new JbkException("参数 " + key + " 不在允许取值范围内（" + def.valueEnum() + "）");
    }

    /** 定义表里的边界值自身非法时必须抛出，不能静默忽略——那等于校验闸悄悄失效。 */
    private static BigDecimal parseBound(String key, String bound, String label) {
        try {
            return new BigDecimal(bound.trim());
        }
        catch (NumberFormatException invalid) {
            throw new JbkException("参数 " + key + " 的定义" + label + "配置非法（" + bound + "）");
        }
    }

    /** 报错时把字符串形态显式标出来，否则 "5000" 与 5000 在文案里长得一模一样，运营无从改起。 */
    private static String describe(Object raw, String text) {
        return raw instanceof String ? "字符串 \"" + text + "\"" : text;
    }

    private static String unitSuffix(Definition def) {
        return StrUtil.isBlank(def.valueUnit()) ? "" : " " + def.valueUnit();
    }

    /** Hutool 的 JSON null 单例，单独引出避免 import 与 JDK 的 null 混淆。 */
    private static final class JSONNull {
        private static final Object INSTANCE = cn.hutool.json.JSONNull.NULL;
    }
}

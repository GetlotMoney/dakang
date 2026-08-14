package com.jbk.serve.service.ops;

import cn.hutool.core.util.StrUtil;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.exception.JbkException;

/**
 * 告警活动幂等键——单一出处（E2E-05 包A/B，任务书 3.2）。键形 {@code 类型:设备[:来源]}，
 * 由 uk_alarm_active_dedupe 库层强制；来源段按类型取：离线无来源段、故障=故障码、
 * 指令超时=指令号、滤芯/SIM=滤芯位/ICCID。生成只许一份实现——raise/recover 拼法分叉会让
 * 撞键幂等与按键恢复同时静默失效；迁移回填 SQL 用同一键形，改这里必须同步改迁移。
 *
 * @author dakang
 * @since 2026-07-30
 */
public final class AlarmDedupeKey {

    /** 键列 varchar(100)：类型(2) + 设备ID(19) + 来源(max64) + 分隔符，上限内。 */
    private static final int MAX_SOURCE_LEN = 64;

    private AlarmDedupeKey() {
    }

    /**
     * 生成活动键。来源为空时省略来源段（离线类告警）。
     *
     * @param type      告警类型
     * @param deviceId  设备ID
     * @param sourceRef 来源引用（故障码/指令号/滤芯位），可空
     */
    public static String of(OpsEnum.AlarmType type, Long deviceId, String sourceRef) {
        if (type == null || deviceId == null || deviceId <= 0) {
            throw new JbkException("告警活动键入参缺失");
        }
        if (StrUtil.isBlank(sourceRef)) {
            return type.getValue() + ":" + deviceId;
        }
        if (sourceRef.length() > MAX_SOURCE_LEN) {
            // 超长来源截断而不是拒绝：来源只是幂等分量，截断后仍稳定；拒绝会让告警本身丢失
            sourceRef = sourceRef.substring(0, MAX_SOURCE_LEN);
        }
        return type.getValue() + ":" + deviceId + ":" + sourceRef;
    }
}

package com.jbk.serve.service.ops;

import cn.hutool.core.util.StrUtil;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.exception.JbkException;

/**
 * 告警活动幂等键——<b>单一出处</b>（E2E-05 包A/B，任务书 3.2）。
 *
 * <h3>键形：{@code 类型:设备[:来源]}</h3>
 * <p>「相同设备、告警类型和来源只能存在一条活动告警」的语义全部压缩在这个键里，
 * 由 {@code uk_alarm_active_dedupe} 在库层强制。来源段按告警类型决定取什么：</p>
 * <ul>
 *   <li>离线告警：无来源段——一台设备同时只能离线一次；</li>
 *   <li>故障告警：来源段=故障码——E003 与 E004 是两条独立告警，且任务书 3.6 要求
 *       「无故障状态只能恢复与当前故障匹配的故障告警」，恢复时按键精确定位；</li>
 *   <li>指令超时：来源段=指令号——不同指令的超时各自成告警；</li>
 *   <li>滤芯/SIM：来源段=滤芯位/ICCID——多滤芯设备逐位告警。</li>
 * </ul>
 *
 * <h3>为什么生成必须只有一份实现</h3>
 * <p>键是拼出来的字符串：raise 侧拼 {@code 2:5:E003} 而 recover 侧拼 {@code 2:5:e003}，
 * 撞键幂等与按键恢复就都静默失效——告警重复与恢复不掉是同一个 bug 的两个表现。
 * 迁移脚本对存量活动告警的回填 SQL 使用同一键形（CONCAT 表达式），改这里必须同步改迁移。</p>
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

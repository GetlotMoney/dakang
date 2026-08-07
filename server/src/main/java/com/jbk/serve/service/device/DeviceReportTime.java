package com.jbk.serve.service.device;

import cn.hutool.core.util.StrUtil;

/**
 * 设备上报时间的合法性判定（P0-06 时间双存的唯一规则实现）。
 *
 * <p>协议约定上行时间为 {@code yyyyMMddHHmmss} 东八区字符串。判定规则只此一份：
 * ACK 与执行结果两条路径分处不同类，各写一份「长度 14 且全数字」就会在放宽时各自漂移。
 * 本类只判定不留痕——回退时该记什么审计由各调用方按自己的事件口径决定。</p>
 *
 * @author dakang
 * @since 2026-08-03
 */
public final class DeviceReportTime {

    private DeviceReportTime() {
    }

    /**
     * @param deviceTs 设备上报的时间字符串
     * @return 合法则原样返回；缺失或非法返回 null，由调用方回退服务器时间并留痕
     */
    public static String acceptOrNull(String deviceTs) {
        boolean legal = StrUtil.isNotBlank(deviceTs) && deviceTs.length() == 14 && StrUtil.isNumeric(deviceTs);
        return legal ? deviceTs : null;
    }
}

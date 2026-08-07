package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 机主设备 Vo（对齐 miniapp device.ts DeviceSummary/DeviceDetail：列表只填摘要段，
 * 详情追加遥测摘要与出水口）。遥测无数据时字段为 null——前端如实降级展示，不伪造数值（任务书 3.7）。
 *
 * @author dakang
 * @since 2026-07-30
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "MiniOwnerDeviceVo", description = "机主设备（摘要/详情）")
public class MiniOwnerDeviceVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "设备编号（三端贯穿业务键）")
    private String deviceNo;

    @Schema(description = "设备名称")
    private String deviceName;

    @Schema(description = "水站ID")
    private Long stationId;

    @Schema(description = "水站名称")
    private String stationName;

    @Schema(description = "在线状态：ONLINE/OFFLINE（未激活按 OFFLINE 展示）")
    private String onlineStatus;

    @Schema(description = "运行状态：IDLE/DISPENSING/FAULT/MAINTENANCE/LOCKED")
    private String runStatus;

    @Schema(description = "最后心跳时间")
    private String lastHeartbeat;

    @Schema(description = "最近故障码")
    private String lastFaultCode;

    // ===== 以下仅详情返回 =====

    @Schema(description = "最近一次有效 TDS（无上报为 null，前端展示暂无数据）")
    private Integer tds;

    @Schema(description = "水温（摄氏度）")
    private Integer temperatureCelsius;

    @Schema(description = "信号强度(dBm，负值)")
    private Integer signalDbm;

    @Schema(description = "遥测上报时间")
    private String reportTime;

    @Schema(description = "SIM状态(1368)：1正常 2未激活 3欠费 4停用；档案口径，真实运营商查询未接入")
    private Integer simStatus;

    @Schema(description = "SIM到期时间")
    private String simExpireTime;

    @Schema(description = "出水口（详情）")
    private List<OutletSummaryVo> outlets;
}

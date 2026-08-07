package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 取水设备上下文 Vo（对齐 miniapp device.ts WaterDeviceContext，扁平字段，不嵌套 station 对象）。
 * <p>
 * onlineStatus/runStatus 用小程序侧字符串枚举：
 * onlineStatus ∈ ONLINE/OFFLINE；runStatus ∈
 * IDLE/DISPENSING/FAULT/MAINTENANCE/LOCKED。
 * </p>
 *
 * @author dakang
 * @since 2026-07-19
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WaterDeviceContextVo", description = "取水设备上下文")
public class WaterDeviceContextVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "扫码会话ID")
    private String scanSessionId;

    @Schema(description = "水站ID")
    private Long stationId;

    @Schema(description = "水站名称")
    private String stationName;

    @Schema(description = "设备编号")
    private String deviceNo;

    @Schema(description = "设备名称")
    private String deviceName;

    @Schema(description = "在线状态：ONLINE/OFFLINE")
    private String onlineStatus;

    @Schema(description = "运行状态：IDLE/DISPENSING/FAULT/MAINTENANCE/LOCKED")
    private String runStatus;

    @Schema(description = "出水口摘要")
    private OutletSummaryVo outlet;

    @Schema(description = "会话过期时间 yyyyMMddHHmmss")
    private String expiresAt;

    /**
     * 报价生成时间 yyyyMMddHHmmss（S2）。与 expiresAt 一起构成「本次扫码报价有效至 X」的展示依据，
     * 价格与水种在此刻定死，页面据此提示用户过期需重扫。
     */
    @Schema(description = "报价生成时间 yyyyMMddHHmmss")
    private String quotedAt;

}

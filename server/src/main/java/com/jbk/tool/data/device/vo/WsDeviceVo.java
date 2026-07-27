package com.jbk.tool.data.device.vo;

import com.jbk.tool.data.BaseEntityVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 设备响应对象
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsDeviceVo", description = "设备响应对象")
public class WsDeviceVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "设备唯一编号(max50)")
    private String deviceNo;

    @Schema(description = "设备名称(max50)")
    private String deviceName;

    @Schema(description = "设备型号(max50)")
    private String deviceModel;

    @Schema(description = "所属水站ID")
    private Long stationId;

    @Schema(description = "所属水站名称（关联派生）")
    private String stationName;

    @Schema(description = "机主用户ID")
    private Long ownerUserId;

    @Schema(description = "机主姓名（关联派生）")
    private String ownerUserName;

    @Schema(description = "固件版本(max50)")
    private String firmwareVersion;

    @Schema(description = "SIM卡ICCID(max50)")
    private String simIccid;

    @Schema(description = "SIM运营商(max20)")
    private String simCarrier;

    @Schema(description = "在线状态(1300)：1在线 2离线 3未激活")
    private Integer onlineStatus;

    @Schema(description = "运行状态(1301)：1空闲 2出水中 3故障 4维护中 5锁机")
    private Integer runStatus;

    @Schema(description = "最后心跳时间")
    private String lastHeartbeat;

    @Schema(description = "最近故障码(max20)")
    private String lastFaultCode;

    @Schema(description = "信号强度(dBm，负值)")
    private Integer signalStrength;

    @Schema(description = "出水口数（关联派生）")
    private Long outletCount;

    @Schema(description = "备注(max500)")
    private String deviceRemark;
}

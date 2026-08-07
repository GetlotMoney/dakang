package com.jbk.tool.data.device.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 设备表 Po
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_device")
@Schema(name = "WsDevice", description = "设备表")
public class WsDevice extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "设备唯一编号(max50)，MQTT clientId 与业务识别主键")
    @TableField("DEVICE_NO")
    private String deviceNo;

    @Schema(description = "设备名称(max50)")
    @TableField("DEVICE_NAME")
    private String deviceName;

    @Schema(description = "设备型号(max50)")
    @TableField("DEVICE_MODEL")
    private String deviceModel;

    @Schema(description = "所属水站ID")
    @TableField("STATION_ID")
    private Long stationId;

    @Schema(description = "机主用户ID（机主端数据范围过滤依据）")
    @TableField("OWNER_USER_ID")
    private Long ownerUserId;

    @Schema(description = "归属渠道用户ID（一期仅归属预留）")
    @TableField("CHANNEL_USER_ID")
    private Long channelUserId;

    @Schema(description = "固件版本(max50)")
    @TableField("FIRMWARE_VERSION")
    private String firmwareVersion;

    @Schema(description = "SIM卡ICCID(max50)")
    @TableField("SIM_ICCID")
    private String simIccid;

    @Schema(description = "SIM运营商(max20)")
    @TableField("SIM_CARRIER")
    private String simCarrier;

    @Schema(description = "SIM状态(1368)：1正常 2未激活 3欠费 4停用；档案维护或模拟器上报，真实运营商查询未接入")
    @TableField("SIM_STATUS")
    private Integer simStatus;

    @Schema(description = "SIM到期时间")
    @TableField("SIM_EXPIRE_TIME")
    private String simExpireTime;

    @Schema(description = "在线状态(1300)：1在线 2离线 3未激活")
    @TableField("ONLINE_STATUS")
    private Integer onlineStatus;

    @Schema(description = "运行状态(1301)：1空闲 2出水中 3故障 4维护中 5锁机")
    @TableField("RUN_STATUS")
    private Integer runStatus;

    @Schema(description = "最后心跳时间")
    @TableField("LAST_HEARTBEAT")
    private String lastHeartbeat;

    @Schema(description = "最近故障码(max20)")
    @TableField("LAST_FAULT_CODE")
    private String lastFaultCode;

    @Schema(description = "最近一次已应用状态报文的设备时间；用于拒绝乱序补传覆盖当前状态")
    @TableField("LAST_STATUS_DEVICE_TIME")
    private String lastStatusDeviceTime;

    @Schema(description = "信号强度(dBm，负值)")
    @TableField("SIGNAL_STRENGTH")
    private Integer signalStrength;

    @Schema(description = "备注(max500)")
    @TableField("DEVICE_REMARK")
    private String deviceRemark;
}

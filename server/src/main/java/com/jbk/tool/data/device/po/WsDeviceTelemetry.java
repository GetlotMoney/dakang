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
 * 设备遥测表 Po（TDS/水温/滤芯/信号周期上报）
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_device_telemetry")
@Schema(name = "WsDeviceTelemetry", description = "设备遥测表")
public class WsDeviceTelemetry extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "设备ID")
    @TableField("DEVICE_ID")
    private Long deviceId;

    @Schema(description = "出水TDS值(ppm)")
    @TableField("TDS_VALUE")
    private Integer tdsValue;

    @Schema(description = "原水TDS值(ppm)")
    @TableField("RAW_TDS_VALUE")
    private Integer rawTdsValue;

    @Schema(description = "水温(摄氏度)")
    @TableField("WATER_TEMP")
    private Integer waterTemp;

    @Schema(description = "滤芯寿命JSON：[{\"no\":1,\"restDay\":30,\"status\":1}]，status见字典1383")
    @TableField("FILTER_LIFE_JSON")
    private String filterLifeJson;

    @Schema(description = "信号强度(dBm)")
    @TableField("SIGNAL_STRENGTH")
    private Integer signalStrength;

    @Schema(description = "上报时间")
    @TableField("REPORT_TIME")
    private String reportTime;
}

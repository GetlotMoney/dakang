package com.jbk.tool.data.device.vo;

import com.jbk.tool.data.BaseEntityVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 设备遥测响应对象
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsDeviceTelemetryVo", description = "设备遥测响应对象")
public class WsDeviceTelemetryVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "设备ID")
    private Long deviceId;

    @Schema(description = "出水TDS值(ppm)")
    private Integer tdsValue;

    @Schema(description = "原水TDS值(ppm)")
    private Integer rawTdsValue;

    @Schema(description = "水温(摄氏度)")
    private Integer waterTemp;

    @Schema(description = "滤芯寿命JSON")
    private String filterLifeJson;

    @Schema(description = "信号强度(dBm)")
    private Integer signalStrength;

    @Schema(description = "上报时间")
    private String reportTime;
}

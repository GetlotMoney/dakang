package com.jbk.tool.data.station.vo;

import com.jbk.tool.data.BaseEntityVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 水站响应对象
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsStationVo", description = "水站响应对象")
public class WsStationVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "水站名称(max50)")
    private String stationName;

    @Schema(description = "水站编码(max50)")
    private String stationCode;

    @Schema(description = "所属区域(max50)")
    private String stationRegion;

    @Schema(description = "详细地址(max200)")
    private String stationAddress;

    @Schema(description = "经度(max20)")
    private String stationLng;

    @Schema(description = "纬度(max20)")
    private String stationLat;

    @Schema(description = "状态(10)：1正常 2禁用")
    private Integer stationStatus;

    @Schema(description = "机主用户ID")
    private Long ownerUserId;

    @Schema(description = "机主姓名（关联 ws_user 派生）")
    private String ownerUserName;

    @Schema(description = "机主手机号（关联 ws_user 派生）")
    private String ownerUserPhone;

    @Schema(description = "站内设备数（关联 ws_device 派生）")
    private Long deviceCount;

    @Schema(description = "备注(max500)")
    private String stationRemark;
}

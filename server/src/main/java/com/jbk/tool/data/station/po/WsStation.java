package com.jbk.tool.data.station.po;

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
 * 水站表 Po
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_station")
@Schema(name = "WsStation", description = "水站表")
public class WsStation extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "水站名称(max50)")
    @TableField("STATION_NAME")
    private String stationName;

    @Schema(description = "水站编码(max50)")
    @TableField("STATION_CODE")
    private String stationCode;

    @Schema(description = "所属区域(max50)")
    @TableField("STATION_REGION")
    private String stationRegion;

    @Schema(description = "详细地址(max200)")
    @TableField("STATION_ADDRESS")
    private String stationAddress;

    @Schema(description = "经度(max20)")
    @TableField("STATION_LNG")
    private String stationLng;

    @Schema(description = "纬度(max20)")
    @TableField("STATION_LAT")
    private String stationLat;

    @Schema(description = "状态(10)：1正常 2禁用")
    @TableField("STATION_STATUS")
    private Integer stationStatus;

    @Schema(description = "机主用户ID")
    @TableField("OWNER_USER_ID")
    private Long ownerUserId;

    @Schema(description = "渠道用户ID（一期仅归属预留）")
    @TableField("CHANNEL_USER_ID")
    private Long channelUserId;

    @Schema(description = "备注(max500)")
    @TableField("STATION_REMARK")
    private String stationRemark;
}

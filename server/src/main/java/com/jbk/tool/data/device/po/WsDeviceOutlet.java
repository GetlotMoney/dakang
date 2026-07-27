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
 * 设备出水口表 Po
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_device_outlet")
@Schema(name = "WsDeviceOutlet", description = "设备出水口表")
public class WsDeviceOutlet extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属设备ID")
    @TableField("DEVICE_ID")
    private Long deviceId;

    @Schema(description = "出水口编号（设备内序号 1/2/3...）")
    @TableField("OUTLET_NO")
    private Integer outletNo;

    @Schema(description = "水种ID（ws_water_type.ID）")
    @TableField("WATER_TYPE_ID")
    private Long waterTypeId;

    @Schema(description = "水种名称(max20)，展示冗余，随水种ID同步")
    @TableField("WATER_TYPE")
    private String waterType;

    @Schema(description = "单价(分)，每升价格")
    @TableField("OUTLET_PRICE")
    private String outletPrice;

    @Schema(description = "状态(10)：1正常 2禁用")
    @TableField("OUTLET_STATUS")
    private Integer outletStatus;
}

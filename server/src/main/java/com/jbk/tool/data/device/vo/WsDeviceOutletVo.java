package com.jbk.tool.data.device.vo;

import com.jbk.tool.data.BaseEntityVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 设备出水口响应对象
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsDeviceOutletVo", description = "设备出水口响应对象")
public class WsDeviceOutletVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "所属设备ID")
    private Long deviceId;

    @Schema(description = "出水口编号")
    private Integer outletNo;

    @Schema(description = "水种ID")
    private Long waterTypeId;

    @Schema(description = "水种名称")
    private String waterType;

    @Schema(description = "单价(分/升)")
    private String outletPrice;

    @Schema(description = "状态(10)：1正常 2禁用")
    private Integer outletStatus;
}

package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 出水口摘要 Vo（对齐 miniapp device.ts OutletSummary）。
 * <p>
 * 数据来源：ws_device_outlet；unitPriceFenPerLiter 由 OUTLET_PRICE(varchar 分/升) 解析为整数。
 * </p>
 *
 * @author dakang
 * @since 2026-07-19
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "OutletSummaryVo", description = "出水口摘要")
public class OutletSummaryVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "出水口ID")
    private Long outletId;

    @Schema(description = "出水口编号（设备内序号）")
    private Integer outletNo;

    @Schema(description = "水种ID（ws_water_type.ID）")
    private Long waterTypeId;

    @Schema(description = "水种名称")
    private String waterTypeName;

    @Schema(description = "单价(分/升)")
    private Integer unitPriceFenPerLiter;

    @Schema(description = "出水口是否可用（OUTLET_STATUS=1 为 true）")
    private Boolean available;
}

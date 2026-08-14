package com.jbk.tool.data.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 商城库存流水 Vo（E2E-09 S1）：管理端追溯视图。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class MallStockFlowVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "流水ID（string）")
    private String id;

    @Schema(description = "请求号（幂等键尾段）")
    private String requestId;

    @Schema(description = "前置仓ID（string）")
    private String warehouseId;

    @Schema(description = "前置仓名称（派生）")
    private String warehouseName;

    @Schema(description = "SKU ID（string）")
    private String skuId;

    @Schema(description = "SKU 名称（派生）")
    private String skuName;

    @Schema(description = "流水类型(1391)")
    private Integer flowType;

    @Schema(description = "可售变化量(件)")
    private Long availableChange;

    @Schema(description = "预占变化量(件)")
    private Long reservedChange;

    @Schema(description = "动作后可售终值(件)")
    private Long availableAfter;

    @Schema(description = "动作后预占终值(件)")
    private Long reservedAfter;

    @Schema(description = "动作原因")
    private String flowReason;

    @Schema(description = "操作人ID（string）")
    private String operatorId;

    @Schema(description = "动作时间")
    private String createTime;
}

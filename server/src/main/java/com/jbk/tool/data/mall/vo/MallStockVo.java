package com.jbk.tool.data.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 商城库存行 Vo（E2E-09 S1）：仓/SKU/商品名称均为关联派生。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class MallStockVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "库存行ID（string）")
    private String id;

    @Schema(description = "前置仓ID（string）")
    private String warehouseId;

    @Schema(description = "前置仓名称（派生）")
    private String warehouseName;

    @Schema(description = "SKU ID（string）")
    private String skuId;

    @Schema(description = "SKU 编号（派生）")
    private String skuNo;

    @Schema(description = "SKU 名称（派生）")
    private String skuName;

    @Schema(description = "商品ID（string）")
    private String productId;

    @Schema(description = "商品名称（派生）")
    private String productName;

    @Schema(description = "可售数量(件)")
    private Long availableQty;

    @Schema(description = "预占数量(件)")
    private Long reservedQty;

    @Schema(description = "版本")
    private Integer version;

    @Schema(description = "最近变动时间")
    private String updateTime;
}

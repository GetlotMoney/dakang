package com.jbk.tool.data.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 商城 SKU Vo（E2E-09 S1）：管理端；规格回结构化键值。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class MallSkuVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "SKU ID（string）")
    private String id;

    @Schema(description = "SKU 业务编号")
    private String skuNo;

    @Schema(description = "SKU 名称")
    private String skuName;

    @Schema(description = "规格键值（SPEC_SNAP 解析）")
    private Map<String, String> specs;

    @Schema(description = "售价(分)")
    private Long salePrice;

    @Schema(description = "划线价(分)：可空")
    private Long marketPrice;

    @Schema(description = "重量(克)")
    private Long weightGram;

    @Schema(description = "SKU 状态(1389)")
    private Integer skuStatus;

    @Schema(description = "版本")
    private Integer version;
}

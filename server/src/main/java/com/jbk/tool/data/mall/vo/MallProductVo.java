package com.jbk.tool.data.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 商城商品列表行 Vo（E2E-09 S1）：categoryName 关联 ws_mall_category 派生。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class MallProductVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "商品ID（string）")
    private String id;

    @Schema(description = "商品业务编号")
    private String productNo;

    @Schema(description = "分类ID（string）")
    private String categoryId;

    @Schema(description = "分类名称（关联 ws_mall_category 派生）")
    private String categoryName;

    @Schema(description = "商品名称")
    private String productName;

    @Schema(description = "副标题")
    private String productSubtitle;

    @Schema(description = "主图")
    private String coverUrl;

    @Schema(description = "商品状态(1388)")
    private Integer productStatus;

    @Schema(description = "版本（上下架 CAS 锚）")
    private Integer version;

    @Schema(description = "SKU 数（关联 ws_mall_sku 派生）")
    private Long skuCount;

    @Schema(description = "创建时间")
    private String createTime;
}

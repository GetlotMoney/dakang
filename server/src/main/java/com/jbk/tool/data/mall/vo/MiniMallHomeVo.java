package com.jbk.tool.data.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 小程序商城首页 Vo（E2E-09 S1，只读白名单出参）：只含启用分类与已上架商品卡；
 * 不含库存数字、成本、电话、范围 JSON 或任何内部字段。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class MiniMallHomeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "启用分类")
    private List<CategoryItem> categories;

    @Schema(description = "已上架商品卡")
    private List<ProductCard> products;

    /** 分类项：ID 恒 string。 */
    @Data
    @Accessors(chain = true)
    public static class CategoryItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "分类ID（string）")
        private String categoryId;

        @Schema(description = "分类名称")
        private String categoryName;
    }

    /** 商品卡：最低售价=启用 SKU 最低价；有货=启用仓可售聚合>0。 */
    @Data
    @Accessors(chain = true)
    public static class ProductCard implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "商品ID（string）")
        private String productId;

        @Schema(description = "商品名称")
        private String productName;

        @Schema(description = "副标题")
        private String productSubtitle;

        @Schema(description = "主图")
        private String coverUrl;

        @Schema(description = "分类ID（string）")
        private String categoryId;

        @Schema(description = "最低售价(分)")
        private Long minSalePriceFen;

        @Schema(description = "是否有货（启用仓可售聚合）")
        private Boolean inStock;
    }
}

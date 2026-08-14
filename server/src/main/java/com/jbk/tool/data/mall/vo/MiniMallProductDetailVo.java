package com.jbk.tool.data.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 小程序商品详情 Vo（E2E-09 S1，只读白名单出参）：SPU + 启用 SKU；
 * 库存只暴露有货/缺货布尔。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class MiniMallProductDetailVo implements Serializable {

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

    @Schema(description = "商品说明")
    private String productDesc;

    @Schema(description = "商品级是否有货（任一启用 SKU 有货）")
    private Boolean inStock;

    @Schema(description = "启用 SKU 列表")
    private List<SkuItem> skus;

    /** SKU 项：价格为分；库存只给布尔。 */
    @Data
    @Accessors(chain = true)
    public static class SkuItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "SKU ID（string）")
        private String skuId;

        @Schema(description = "SKU 名称")
        private String skuName;

        @Schema(description = "规格键值")
        private Map<String, String> specs;

        @Schema(description = "售价(分)")
        private Long salePriceFen;

        @Schema(description = "划线价(分)：可空")
        private Long marketPriceFen;

        @Schema(description = "重量(克)")
        private Long weightGram;

        @Schema(description = "是否有货")
        private Boolean inStock;
    }
}

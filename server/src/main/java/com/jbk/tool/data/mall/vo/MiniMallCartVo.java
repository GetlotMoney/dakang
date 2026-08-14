package com.jbk.tool.data.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 小程序购物车 Vo（E2E-09 S2）。
 *
 * <p>失效行（商品下架/SKU 停用/删除）保留在列表里并带失效原因，不静默丢弃——
 * 用户加过的东西凭空消失比显示"已下架"更让人困惑，也会让金额对不上预期。
 * 合计金额只累计有效行。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class MiniMallCartVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "购物车行")
    private List<CartLine> lines;

    @Schema(description = "有效行合计件数")
    private Integer totalQuantity;

    @Schema(description = "有效行合计金额(分)")
    private Long totalAmountFen;

    /** 购物车行：价格与规格为实时读取（未下单，不冻结）。 */
    @Data
    @Accessors(chain = true)
    public static class CartLine implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "SKU ID（string）")
        private String skuId;

        @Schema(description = "商品ID（string）")
        private String productId;

        @Schema(description = "商品名称")
        private String productName;

        @Schema(description = "SKU 名称")
        private String skuName;

        @Schema(description = "规格键值（扁平 string→string）")
        private Map<String, String> specs;

        @Schema(description = "商品主图")
        private String coverUrl;

        @Schema(description = "现价(分)")
        private Long salePriceFen;

        @Schema(description = "数量(件)")
        private Integer quantity;

        @Schema(description = "行金额(分)=现价×数量")
        private Long itemAmountFen;

        @Schema(description = "是否可下单：商品已上架且 SKU 启用")
        private Boolean purchasable;

        @Schema(description = "不可下单原因（可下单时为空）")
        private String unavailableReason;
    }
}

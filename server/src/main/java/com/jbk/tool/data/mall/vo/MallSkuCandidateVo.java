package com.jbk.tool.data.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 库存动作 SKU 候选 Vo（E2E-09 R2-P0）。数据源恒为 ws_mall_sku ⋈ ws_mall_product，绝不从 ws_mall_stock 反推：
 * 新建 SKU 无库存行时也必须可选中做首次入库。停用 SKU 照常返回（带状态标识），本轮不新增"停用禁调整"规则。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class MallSkuCandidateVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "SKU ID（string）")
    private String skuId;

    @Schema(description = "SKU 编号")
    private String skuNo;

    @Schema(description = "SKU 名称")
    private String skuName;

    @Schema(description = "所属商品ID（string）")
    private String productId;

    @Schema(description = "所属商品名称")
    private String productName;

    @Schema(description = "SKU状态(1389)：1启用 2停用")
    private Integer skuStatus;
}

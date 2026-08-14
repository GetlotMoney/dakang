package com.jbk.tool.data.mall.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 商品保存里的 SKU 条目 Bo（E2E-09 S1）：规格收结构化键值，SPEC_SNAP 由服务端
 * 统一序列化与校验，前端不拼 JSON。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
public class MallSkuItemBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "SKU ID（更新既有 SKU 时必填；新增为空）")
    private Long id;

    @Schema(description = "SKU 业务编号（新增必填；不复用不可改）")
    @Size(max = 50, message = "SKU 编号不能超过 50 字")
    private String skuNo;

    @Schema(description = "SKU 名称")
    @NotBlank(message = "请填写 SKU 名称")
    @Size(max = 100, message = "SKU 名称不能超过 100 字")
    private String skuName;

    @Schema(description = "规格键值（扁平 string→string，≤8 键）")
    private Map<String, String> specs;

    @Schema(description = "售价(分)：正整数")
    @NotNull(message = "请填写售价")
    @Positive(message = "售价必须为正整数分")
    private Long salePrice;

    @Schema(description = "划线价(分)：可空；有值时不得小于售价")
    @Positive(message = "划线价必须为正整数分")
    private Long marketPrice;

    @Schema(description = "重量(克)：非负整数")
    @NotNull(message = "请填写重量")
    @Min(value = 0, message = "重量不能为负")
    private Long weightGram;

    @Schema(description = "SKU 状态(1389)：1启用 2停用")
    @NotNull(message = "请选择 SKU 状态")
    private Integer skuStatus;
}

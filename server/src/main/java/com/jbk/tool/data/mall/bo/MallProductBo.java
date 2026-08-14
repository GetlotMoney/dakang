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
 * 商品（SPU+SKU 联动）保存 Bo（E2E-09 S1）。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
public class MallProductBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "商品ID（update 必填）")
    private Long id;

    @Schema(description = "商品业务编号（save 必填；不复用不可改）")
    @Size(max = 50, message = "商品编号不能超过 50 字")
    private String productNo;

    @Schema(description = "分类ID")
    @NotNull(message = "请选择商品分类")
    private Long categoryId;

    @Schema(description = "商品名称")
    @NotBlank(message = "请填写商品名称")
    @Size(max = 100, message = "商品名称不能超过 100 字")
    private String productName;

    @Schema(description = "副标题")
    @Size(max = 200, message = "副标题不能超过 200 字")
    private String productSubtitle;

    @Schema(description = "主图 URL/相对路径（本期无上传能力）")
    @Size(max = 500, message = "主图地址不能超过 500 字")
    private String coverUrl;

    @Schema(description = "商品说明")
    @Size(max = 4000, message = "商品说明不能超过 4000 字")
    private String productDesc;

    @Schema(description = "SKU 列表（save 至少一条）")
    @Valid
    private List<MallSkuItemBo> skus;
}

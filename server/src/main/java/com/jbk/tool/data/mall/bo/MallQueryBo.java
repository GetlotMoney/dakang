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
 * 商城管理端分页查询 Bo（E2E-09 S1）：四页共用，按页取用字段。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
public class MallQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "页码（≥1）")
    private Long current;

    @Schema(description = "每页条数（≤100）")
    private Long size;

    @Schema(description = "关键字：编号/名称模糊")
    @Size(max = 100, message = "关键字过长")
    private String keyword;

    @Schema(description = "分类ID（商品/库存页筛选）")
    private Long categoryId;

    @Schema(description = "商品ID（库存页筛选）")
    private Long productId;

    @Schema(description = "前置仓ID（库存/流水页筛选）")
    private Long warehouseId;

    @Schema(description = "SKU ID（流水页筛选）")
    private Long skuId;

    @Schema(description = "状态筛选（各页对应字典）")
    private Integer status;
}

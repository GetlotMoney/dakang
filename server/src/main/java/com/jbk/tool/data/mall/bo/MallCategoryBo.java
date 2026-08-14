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
 * 商城分类维护 Bo（E2E-09 S1）：save 必带编码；update 编码不可改（服务端忽略）。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
public class MallCategoryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "分类ID（update 必填）")
    private Long id;

    @Schema(description = "分类业务编码（save 必填；不复用）")
    @Size(max = 50, message = "分类编码不能超过 50 字")
    private String categoryCode;

    @Schema(description = "分类名称")
    @NotBlank(message = "请填写分类名称")
    @Size(max = 50, message = "分类名称不能超过 50 字")
    private String categoryName;

    @Schema(description = "排序号（小在前）")
    @NotNull(message = "请填写排序号")
    @Min(value = 0, message = "排序号不能为负")
    private Integer categorySort;
}

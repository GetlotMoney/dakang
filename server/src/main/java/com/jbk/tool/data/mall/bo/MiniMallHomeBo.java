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
 * 小程序商城首页查询 Bo（E2E-09 S1，只读）。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
public class MiniMallHomeBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "分类ID过滤（空=全部）")
    private Long categoryId;
}

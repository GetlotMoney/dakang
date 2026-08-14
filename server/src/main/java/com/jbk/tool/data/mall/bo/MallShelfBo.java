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
 * 商品上下架 Bo（E2E-09 S1）：VERSION CAS 必带版本。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
public class MallShelfBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "商品ID")
    @NotNull(message = "缺少商品信息")
    private Long id;

    @Schema(description = "当前版本（并发防覆盖锚）")
    @NotNull(message = "缺少版本信息，请刷新后重试")
    private Integer version;
}

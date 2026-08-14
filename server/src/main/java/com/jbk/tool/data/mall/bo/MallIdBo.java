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
 * 通用 ID 入参 Bo（E2E-09 S1）：详情/小程序商品详情用。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
public class MallIdBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "业务主键ID（前端 string 直传）")
    @NotNull(message = "缺少必要参数")
    private Long id;
}

package com.jbk.tool.data.mall.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 商城售后单号入参（E2E-09 S4）：操作人恒取会话。
 *
 * @author dakang
 * @since 2026-08-10
 */
@Data
@Accessors(chain = true)
public class MallAfterSaleNoBo {

    @Schema(description = "售后单号")
    @NotBlank(message = "售后单号不能为空")
    @Size(max = 32, message = "售后单号过长")
    private String afterSaleNo;
}

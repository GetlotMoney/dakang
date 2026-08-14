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
 * 库存人工动作 Bo（E2E-09 S1）：入库/出库/盘点调整统一入参。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
public class MallStockAdjustBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "请求号：规范 UUID，幂等锚")
    @NotBlank(message = "请求号不能为空")
    @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
            message = "请求号必须为规范 UUID")
    private String requestId;

    @Schema(description = "前置仓ID（前端 string 直传）")
    @NotNull(message = "请选择前置仓")
    private Long warehouseId;

    @Schema(description = "SKU ID（前端 string 直传）")
    @NotNull(message = "请选择 SKU")
    private Long skuId;

    @Schema(description = "动作类型(1391)：1人工入库 2人工出库 3盘点调增 4盘点调减")
    @NotNull(message = "请选择动作类型")
    private Integer flowType;

    @Schema(description = "数量(件)：正整数")
    @NotNull(message = "请填写数量")
    @Positive(message = "数量必须为正整数")
    private Long quantity;

    @Schema(description = "动作原因（审计必填）")
    @NotBlank(message = "请填写动作原因")
    @Size(max = 200, message = "动作原因不能超过 200 字")
    private String reason;
}

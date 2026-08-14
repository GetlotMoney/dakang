package com.jbk.tool.data.mall.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 购物车写入入参（E2E-09 S2）。归属用户恒取会话，不接受前端传 userId。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class MallCartSaveBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "SKU ID")
    @NotNull(message = "请选择商品规格")
    private Long skuId;

    @Schema(description = "数量(件)：1~999")
    @NotNull(message = "请填写数量")
    @Min(value = 1, message = "数量至少 1 件")
    @Max(value = 999, message = "单个规格一次最多 999 件")
    private Integer quantity;

    /**
     * 语义开关：true=在现有数量上累加（商品详情页加购），false=覆盖为该数量（购物车页改量）。
     * 两种语义都由服务端一条 upsert 原子完成，端上不需要先查再写。
     */
    @Schema(description = "是否累加：true加购累加 false覆盖设置")
    private Boolean increment;
}

package com.jbk.tool.data.mall.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 结算预览与创建订单共用入参（E2E-09 S2）。下单行由请求显式携带（SKU+数量），不隐式读购物车
 * （购物车创单后已清理，requestId 重放无从比对）。金额一律服务端按 SKU 现价重算，请求不带金额。
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class MallCheckoutBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "收货地址ID（必须属于会话用户，且已选区县码）")
    @NotNull(message = "请选择收货地址")
    private Long addressId;

    @Schema(description = "下单行：同一 SKU 不得重复出现")
    @NotEmpty(message = "请选择要购买的商品")
    @Size(max = 50, message = "单笔订单最多 50 个规格")
    @Valid
    private List<Line> lines;

    @Schema(description = "创单请求号（规范小写 UUID）：创建订单必填，预览忽略")
    private String requestId;

    /** 下单行。 */
    @Data
    @Accessors(chain = true)
    public static class Line implements Serializable {

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
    }
}

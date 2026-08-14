package com.jbk.tool.data.mall.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 商城履约动作入参（E2E-09 S3）：只带订单号，操作人恒取会话。
 *
 * @author dakang
 * @since 2026-08-09
 */
@Data
@Accessors(chain = true)
public class MallFulfillActionBo {

    @Schema(description = "商城订单号")
    @NotBlank(message = "订单号不能为空")
    @Size(max = 32, message = "订单号过长")
    private String orderNo;
}

package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

import java.io.Serializable;

/** 查询充值订单支付状态入参（§9.1）。归属由会话强制过滤，不收 userId。 */
@Data
@Schema(name = "MiniPayStatusBo", description = "支付状态查询入参")
public class MiniPayStatusBo implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "orderNo 不能为空")
    @Schema(description = "充值订单号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String orderNo;
}

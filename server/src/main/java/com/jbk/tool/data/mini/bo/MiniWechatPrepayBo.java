package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 微信支付 JSAPI 下单入参（WX-ECO S3）。只收订单号：金额一律取服务端支付单。
 */
@Data
public class MiniWechatPrepayBo implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "orderNo 不能为空")
    @Schema(description = "充值订单号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String orderNo;
}

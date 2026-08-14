package com.jbk.tool.data.mall.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 商城订单动作入参（详情/取消/支付状态查询，E2E-09 S2）。
 *
 * <p>一律按订单号定位而非自增 ID：订单号是确定性派生的业务键，跨端传递不暴露行序，
 * 也不会因为端上误传别人的 ID 而命中他人订单（归属仍在 Service 强校验）。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class MallOrderActionBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "商城订单号")
    @NotBlank(message = "订单号不能为空")
    @Size(max = 32, message = "订单号长度非法")
    private String orderNo;

    @Schema(description = "取消原因（取消动作可选，≤200）")
    @Size(max = 200, message = "取消原因不超过 200 字")
    private String cancelReason;
}

package com.jbk.tool.data.mall.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 创建第三方运单入参（E2E-09 L1）。
 *
 * <p>刻意没有运单号、承运方订单号与运费字段：运单号只能由承运方给出，
 * 收下前端传来的运单号等于允许把包裹挂到任意一个号上；
 * 物流成本与用户已支付的配送费是两件事，任何报价都不得反向修改已支付订单。</p>
 *
 * @author dakang
 * @since 2026-08-11
 */
@Data
@Accessors(chain = true)
public class MallShipmentCreateBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "商城订单号")
    @NotBlank(message = "订单号不能为空")
    @Size(max = 32, message = "订单号过长")
    private String orderNo;

    @Schema(description = "承运商编码：必须是已注册的适配器编码")
    @NotBlank(message = "请选择承运商")
    @Size(max = 32, message = "承运商编码过长")
    private String providerCode;
}

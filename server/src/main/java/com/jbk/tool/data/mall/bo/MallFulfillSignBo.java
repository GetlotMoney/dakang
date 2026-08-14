package com.jbk.tool.data.mall.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 商城订单签收入参（E2E-09 S3）。
 *
 * <p>签收时间刻意不由端上上传：客户端时钟不可信，且签收时间要与订单完成时间、轨迹、
 * 站内消息和审计同源，只能由服务端在事务内取一次。</p>
 *
 * @author dakang
 * @since 2026-08-09
 */
@Data
@Accessors(chain = true)
public class MallFulfillSignBo {

    @Schema(description = "商城订单号")
    @NotBlank(message = "订单号不能为空")
    @Size(max = 32, message = "订单号过长")
    private String orderNo;

    @Schema(description = "签收方式(1398)：1本人签收 2他人代收")
    @NotNull(message = "请选择签收方式")
    private Integer signMethod;

    @Schema(description = "签收备注")
    @Size(max = 200, message = "签收备注不超过200字")
    private String signRemark;
}

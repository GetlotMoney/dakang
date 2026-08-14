package com.jbk.tool.data.mall.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 商城履约分配配送员入参（E2E-09 S3）。
 *
 * <p>配送员 ID 由前端从候选接口选出，服务端仍要重新校验准入、停用与前置仓范围——
 * 候选接口只是给人看的列表，不是授权。</p>
 *
 * @author dakang
 * @since 2026-08-09
 */
@Data
@Accessors(chain = true)
public class MallFulfillAssignBo {

    @Schema(description = "商城订单号")
    @NotBlank(message = "订单号不能为空")
    @Size(max = 32, message = "订单号过长")
    private String orderNo;

    @Schema(description = "配送员ID")
    @NotNull(message = "请选择配送员")
    private Long courierId;
}

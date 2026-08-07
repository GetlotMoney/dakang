package com.jbk.tool.data.settlement.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 提现骨架入参（E2E-08）。mini 侧不收 userId（铁律6）；PC 驳回侧必传收益人。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WithdrawBo", description = "提现申请/驳回入参")
public class WithdrawBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "请求号（规范 UUID，幂等）")
    @NotBlank(message = "请求号不能为空")
    private String requestId;

    @Schema(description = "金额(分)")
    @NotNull(message = "金额不能为空")
    private Long amountFen;

    @Schema(description = "收益人（仅 PC 驳回侧使用）")
    private Long userId;
}

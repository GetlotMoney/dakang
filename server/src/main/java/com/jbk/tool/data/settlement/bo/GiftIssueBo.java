package com.jbk.tool.data.settlement.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 运营赠卡发放入参（E2E-08 包D / D-213 现行口径）。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "GiftIssueBo", description = "运营赠卡发放入参")
public class GiftIssueBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "发放请求号（规范 UUID，幂等锚：卡号由它确定性派生）")
    @NotBlank(message = "请求号不能为空")
    private String requestId;

    @Schema(description = "收卡用户ID")
    @NotNull(message = "收卡用户不能为空")
    private Long userId;

    @Schema(description = "赠送余额(分)，与水量至少其一为正")
    private Long grantFen;

    @Schema(description = "赠送水量(毫升)")
    private Long grantMl;

    @Schema(description = "有效期天数（1~3650，D-213 赠卡必带有效期）")
    @NotNull(message = "有效期不能为空")
    private Integer expireDays;

    @Schema(description = "可用范围 JSON（缺省=不限；语义同 ws_card.SCOPE_JSON）")
    private String scopeJson;

    @Schema(description = "发放备注")
    private String remark;
}

package com.jbk.tool.data.mall.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 商城售后审核入参（E2E-09 S4）：只表达通过/驳回与说明，不含金额与库存。
 *
 * @author dakang
 * @since 2026-08-10
 */
@Data
@Accessors(chain = true)
public class MallAfterSaleAuditBo {

    @Schema(description = "售后单号")
    @NotBlank(message = "售后单号不能为空")
    @Size(max = 32, message = "售后单号过长")
    private String afterSaleNo;

    @Schema(description = "是否通过")
    @NotNull(message = "请给出审核结论")
    private Boolean approved;

    @Schema(description = "审核说明；驳回时必填")
    @Size(max = 200, message = "审核说明不超过200字")
    private String remark;
}

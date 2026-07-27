package com.jbk.tool.data.delivery.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * PC 裁决申诉入参（E2E-03 规则17/18：只允许 3不成立驳回 / 5补送待执行 / 2成立待补偿，
 * 资金补偿只落待处理态，绝不写退款成功——真实退款属 E2E-04）。
 */
@Data
@Schema(name = "DeliveryAppealDecideBo", description = "裁决配送申诉入参")
public class DeliveryAppealDecideBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "appealId 不能为空")
    @Schema(description = "申诉ID（正十进制字符串）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String appealId;

    @NotNull(message = "裁决结果不能为空")
    @Schema(description = "裁决结果(1352)：3不成立驳回 / 5补送待执行 / 2成立待补偿", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer outcome;

    @NotBlank(message = "处理结果说明不能为空")
    @Size(max = 500, message = "处理结果说明过长")
    @Schema(description = "处理结果说明(max500)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String handleResult;
}

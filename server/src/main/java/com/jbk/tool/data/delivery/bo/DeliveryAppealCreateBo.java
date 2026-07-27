package com.jbk.tool.data.delivery.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 用户创建配送申诉入参（E2E-03 规则15：限订单本人且签收后 24h 内）。
 */
@Data
@Schema(name = "DeliveryAppealCreateBo", description = "创建配送申诉入参")
public class DeliveryAppealCreateBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "orderNo 不能为空")
    @Schema(description = "配送订单号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String orderNo;

    @NotBlank(message = "taskNo 不能为空")
    @Schema(description = "配送任务号（与订单共键一致）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskNo;

    @NotBlank(message = "申诉原因不能为空")
    @Schema(description = "原因码：QUANTITY/QUALITY/DAMAGE/PLACEMENT/OTHER", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reason;

    @NotBlank(message = "申诉说明不能为空")
    @Size(max = 500, message = "申诉说明过长")
    @Schema(description = "申诉说明(max500)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String description;

    @NotNull(message = "实收数量不能为空")
    @Min(value = 0, message = "实收数量不能为负")
    @Max(value = 99, message = "实收数量超出上限")
    @Schema(description = "用户实收数量（结构化数字）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer receivedCount;

    @Schema(description = "举证受控媒体键列表（可空）")
    private List<String> evidenceRefs;
}

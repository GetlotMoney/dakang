package com.jbk.tool.data.delivery.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 配送员追加申诉举证入参（E2E-03 规则16：限任务归属配送员，任务须处于申诉中）。
 */
@Data
@Schema(name = "DeliveryAppealEvidenceBo", description = "配送员追加申诉举证入参")
public class DeliveryAppealEvidenceBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "taskNo 不能为空")
    @Schema(description = "任务号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskNo;

    @NotBlank(message = "appealId 不能为空")
    @Schema(description = "申诉ID（正十进制字符串）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String appealId;

    @NotBlank(message = "举证说明不能为空")
    @Size(max = 500, message = "举证说明过长")
    @Schema(description = "举证说明(max500)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String description;

    @Schema(description = "举证受控媒体键列表（可空）")
    private List<String> evidenceRefs;
}

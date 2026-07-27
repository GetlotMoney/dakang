package com.jbk.tool.data.delivery.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 配送异常上报入参（限任务归属配送员；任务须处于 2已接单/3配送中/4已送达待确认）。
 */
@Data
@Schema(name = "DeliveryExceptionReportBo", description = "配送异常上报入参")
public class DeliveryExceptionReportBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "taskNo 不能为空")
    @Schema(description = "任务号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskNo;

    @NotNull(message = "expectedVersion 不能为空")
    @Schema(description = "期望乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer expectedVersion;

    @NotNull(message = "异常原因不能为空")
    @Schema(description = "异常原因(1354)：1联系不上用户 2地址异常 3数量问题 4货物破损 5其他", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer reason;

    @NotBlank(message = "异常说明不能为空")
    @Size(max = 500, message = "异常说明过长")
    @Schema(description = "异常说明(max500)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String description;

    @Schema(description = "举证受控媒体键列表（可空）")
    private List<String> evidenceRefs;
}

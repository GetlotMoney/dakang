package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 小程序配送推进入参（E2E-03 规则10：3离站/4送达；合法当前态由
 * DeliveryTransitions 在 Service 层裁决，Controller 不复制状态机）。
 */
@Data
@Schema(name = "MiniDeliveryAdvanceBo", description = "小程序配送推进入参")
public class MiniDeliveryAdvanceBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "taskNo 不能为空")
    @Size(max = 32, message = "taskNo 不合法")
    @Schema(description = "配送任务号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskNo;

    @NotNull(message = "targetStatus 不能为空")
    @Schema(description = "目标状态：3配送中（离站）/ 4已送达待确认（送达）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer targetStatus;

    @NotNull(message = "expectedVersion 不能为空")
    @Schema(description = "期望乐观锁版本（规则11）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer expectedVersion;
}

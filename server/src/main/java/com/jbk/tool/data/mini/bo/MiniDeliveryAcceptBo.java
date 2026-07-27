package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 小程序配送接单入参（E2E-03 规则9/11：CAS 并发唯一，必须回传期望版本）。
 */
@Data
@Schema(name = "MiniDeliveryAcceptBo", description = "小程序配送接单入参")
public class MiniDeliveryAcceptBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "taskNo 不能为空")
    @Size(max = 32, message = "taskNo 不合法")
    @Schema(description = "配送任务号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskNo;

    @NotNull(message = "expectedVersion 不能为空")
    @Schema(description = "期望乐观锁版本（规则11）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer expectedVersion;
}

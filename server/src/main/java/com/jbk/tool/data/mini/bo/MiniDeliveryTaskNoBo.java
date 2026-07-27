package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 小程序按任务号查询入参（E2E-03 包B：详情/异常记录/申诉查看共用）。
 * <p>访问归属（可接可见性/本人任务/证据只读收口）全部在 Service 层判定，Controller 不复制。</p>
 */
@Data
@Schema(name = "MiniDeliveryTaskNoBo", description = "小程序配送任务号入参")
public class MiniDeliveryTaskNoBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "taskNo 不能为空")
    @Size(max = 32, message = "taskNo 不合法")
    @Schema(description = "配送任务号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskNo;
}

package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 小程序配送任务列表入参（E2E-03 包B / D01）。
 *
 * <p>铁律6：只收视图名，不收配送员ID/范围——配送员身份与服务范围一律由
 * 会话登录人在 Service 层解析（CourierAccess），前端传参不得圈定数据范围。</p>
 */
@Data
@Schema(name = "MiniDeliveryTaskQueryBo", description = "小程序配送任务列表入参")
public class MiniDeliveryTaskQueryBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "view 不能为空")
    @Schema(description = "任务视图：available可接 / active进行中 / history已完成", requiredMode = Schema.RequiredMode.REQUIRED)
    private String view;
}

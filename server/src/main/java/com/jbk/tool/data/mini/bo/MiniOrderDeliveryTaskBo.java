package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 小程序本人订单配送任务查询入参（E2E-03 包B / U06 消费者视角）。
 * <p>铁律6：只收 orderNo；订单归属由会话登录人在 Service 强制圈定，
 * 与配送员端 task/detail 数据范围互不越权。</p>
 */
@Data
@Schema(name = "MiniOrderDeliveryTaskBo", description = "本人订单配送任务查询入参")
public class MiniOrderDeliveryTaskBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "orderNo 不能为空")
    @Size(max = 64, message = "orderNo 不合法")
    @Schema(description = "配送订单号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String orderNo;
}

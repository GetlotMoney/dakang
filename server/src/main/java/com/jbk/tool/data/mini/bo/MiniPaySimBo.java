package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * Pay-Sim 模拟支付入参（仅隔离测试环境）。
 *
 * <p>只收订单号。<b>金额、支付来源、成功时间一律由服务端从订单与适配器推导</b>——
 * 允许前端传金额就等于允许前端自定义"付了多少钱"。</p>
 */
@Data
@Schema(name = "MiniPaySimBo", description = "模拟支付入参（仅测试环境）")
public class MiniPaySimBo implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "orderNo 不能为空")
    @Schema(description = "充值订单号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String orderNo;
}

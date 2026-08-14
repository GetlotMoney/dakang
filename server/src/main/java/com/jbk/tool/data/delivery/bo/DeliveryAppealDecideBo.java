package com.jbk.tool.data.delivery.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * PC 裁决申诉入参（E2E-04 包A：策略码驱动）。刻意不带 outcome：终态由
 * {@code AfterSaleStrategy.deriveOutcome(strategyCode)} 从策略码唯一派生，矛盾态物理不可表达。
 * 前端只提交策略码/数量/说明，绝不提交金额与水量：额度由服务端按订单冻结快照与批准数量计算。
 */
@Data
@Schema(name = "DeliveryAppealDecideBo", description = "裁决配送申诉入参")
public class DeliveryAppealDecideBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "appealId 不能为空")
    @Schema(description = "申诉ID（正十进制字符串）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String appealId;

    @NotBlank(message = "补偿策略码不能为空")
    @Size(max = 20, message = "补偿策略码过长")
    @Schema(description = "补偿策略码：PRODUCT_ONLY 仅退水品 / SERVICE_FEE_ONLY 仅退配送费 / "
            + "PRODUCT_AND_SERVICE 水品与配送费同退 / RESEND 补送 / REJECT 驳回。"
            + "白名单单一出处见 AfterSaleEnum.StrategyCode；申诉终态由服务端从本码派生",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String strategyCode;

    @Min(value = 1, message = "批准数量必须为正")
    @Schema(description = "运营批准的受影响数量（桶）。上界随申诉原因码变化（数量不符取「计划−实收」，"
            + "水质/破损取实际签收数量），由服务端 AfterSaleStrategy.requireApprovedCount 判定；"
            + "REJECT 时本字段被忽略，可不传")
    private Integer approvedCount;

    @NotBlank(message = "处理结果说明不能为空")
    @Size(max = 500, message = "处理结果说明过长")
    @Schema(description = "处理结果说明(max500)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String handleResult;
}

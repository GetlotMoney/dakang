package com.jbk.tool.data.delivery.bo;

import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * PC 配送申诉查询 Bo（E2E-03 包C，DRIVER_MANAGE 会话）。
 * <p>分页用 {@code PageGroup}；证据详情用 {@code IdGroup}（id=申诉ID）。
 * 裁决提交走独立的 {@code DeliveryAppealDecideBo}（包A 冻结契约），本 Bo 只管查询。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "AdminDeliveryAppealBo", description = "管理端配送申诉查询参数")
public class AdminDeliveryAppealBo extends PageBo {

    @Schema(description = "申诉ID（证据详情用）")
    @NotNull(message = "申诉ID不能为空", groups = IdGroup.class)
    private Long id;

    @Schema(description = "申诉状态(1352)：1待处理 2成立待补偿 3不成立驳回 4撤销 5补送待执行")
    private Integer appealStatus;

    @Schema(description = "关联订单号（模糊）")
    @Size(max = 32, message = "订单号过长")
    private String orderNo;
}

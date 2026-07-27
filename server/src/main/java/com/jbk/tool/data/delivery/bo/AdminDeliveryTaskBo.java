package com.jbk.tool.data.delivery.bo;

import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * PC 配送任务查询 Bo（E2E-03 包C，DRIVER_MANAGE 会话）。
 * <p>分页用 {@code PageGroup}（current/size）；详情用 {@code IdGroup}（id=任务ID）。
 * 管理端只读监控，不承载任何履约推进入参（PC 不伪造接单/签收，铁律边界）。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "AdminDeliveryTaskBo", description = "管理端配送任务查询参数")
public class AdminDeliveryTaskBo extends PageBo {

    @Schema(description = "配送任务ID（详情用）")
    @NotNull(message = "任务ID不能为空", groups = IdGroup.class)
    private Long id;

    @Schema(description = "任务状态(1351)：1待接单 2已接单 3配送中 4已送达待确认 5已签收 6已取消 7申诉中")
    private Integer taskStatus;

    @Schema(description = "关键词（订单号/任务号/用户姓名/手机号模糊）")
    @Size(max = 50, message = "关键词过长")
    private String keyword;
}

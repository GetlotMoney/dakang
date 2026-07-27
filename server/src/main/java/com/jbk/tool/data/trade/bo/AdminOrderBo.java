package com.jbk.tool.data.trade.bo;

import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理端订单查询 Bo（PC 订单中心，DRIVER_MANAGE 会话）。
 * <p>分页用 {@code PageGroup}（current/size）；详情追溯用 {@code IdGroup}（id）。</p>
 *
 * @author dakang
 * @since 2026-07-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "AdminOrderBo", description = "管理端订单查询参数")
public class AdminOrderBo extends PageBo {

    @Schema(description = "订单ID（详情追溯用）")
    @NotNull(message = "订单ID不能为空", groups = IdGroup.class)
    private Long id;

    @Schema(description = "订单号（模糊）")
    @Size(max = 32, message = "订单号过长")
    private String orderNo;

    @Schema(description = "订单类型(1340)：1扫码取水 2购卡充值 3水配送")
    private Integer orderType;

    @Schema(description = "订单状态(1341)：1待支付 2已支付 3出水中 4已完成 5已取消 6异常待补偿 7已退款 8部分退款")
    private Integer orderStatus;

    @Schema(description = "用户关键词（姓名/手机号模糊）")
    @Size(max = 50, message = "用户关键词过长")
    private String userKeyword;
}

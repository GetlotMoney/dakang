package com.jbk.tool.data.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 订单详情 Vo（嵌套结构，严格对齐 miniapp order.ts OrderDetail，保证小程序零改）。
 * <p>{@code order} 为订单主体（OrderItemVo）；commandNo/commandStatus <b>当前恒为 null</b>——本接口尚未查询 ws_command，接真属 L2-READ；
 * trace 为追溯时间线；flowCount 为钱包流水条数。deliveryTaskNo/appealId 仅配送单（ORDER_TYPE=3）
 * 有值（E2E-03 包B：U06 借此定位任务证据与申诉记录），取水/充值单恒为空。</p>
 *
 * @author dakang
 * @since 2026-07-19
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "OrderDetailVo", description = "订单详情")
public class OrderDetailVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单主体")
    private OrderItemVo order;

    @Schema(description = "关联出水指令号：当前接口未查询 ws_command，恒为 null（接真属 L2-READ），前端须按“未接入”呈现，不得解读为“无指令”")
    private String commandNo;

    @Schema(description = "关联出水指令状态：当前接口未查询 ws_command，恒为 null（接真属 L2-READ），前端须按“未接入”呈现，不得解读为任何真实指令状态")
    private Integer commandStatus;

    @Schema(description = "订单追溯时间线")
    private List<OrderTraceNodeVo> trace;

    @Schema(description = "钱包流水条数")
    private Integer flowCount;

    @Schema(description = "关联配送任务号；仅配送单有值（关联 ws_delivery_task 派生）")
    private String deliveryTaskNo;

    @Schema(description = "最新配送申诉ID；仅配送单且存在申诉时有值（关联 ws_delivery_appeal 派生）")
    private Long appealId;
}

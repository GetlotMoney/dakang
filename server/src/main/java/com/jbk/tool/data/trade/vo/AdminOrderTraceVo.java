package com.jbk.tool.data.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 管理端订单全链路追溯 Vo（PC 订单详情抽屉数据源）。
 * <p>
 * 对齐前端 order.ts 的 OrderTraceVo 契约。H2 覆盖订单本体 + 指令追溯 + 流水追溯；
 * E2E-03 包C 起 delivery/appeals/auditEvents 三区块对配送单返回真实聚合
 * （履约链/资金链分层共键核验，非配送单保持 null/空数组不变）。
 * </p>
 *
 * @author dakang
 * @since 2026-07-20
 */
@Data
@Schema(name = "AdminOrderTraceVo", description = "管理端订单全链路追溯")
public class AdminOrderTraceVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单本体")
    private AdminOrderItemVo order;

    @Schema(description = "指令追溯（取水单，无则 null）")
    private AdminCommandTraceVo command;

    @Schema(description = "充值追溯（购卡充值单，无则 null）")
    private AdminRechargeTraceVo recharge;

    @Schema(description = "支付/扣减流水")
    private List<FlowTraceVo> flows;

    @Schema(description = "配送区块（配送单真实聚合；非配送单 null）")
    private com.jbk.tool.data.delivery.vo.AdminDeliveryTraceVo delivery;

    @Schema(description = "申诉记录（配送单逐行共键核验；非配送单空数组）")
    private List<com.jbk.tool.data.delivery.vo.AdminAppealTraceVo> appeals;

    @Schema(description = "审计事件（配送单按订单号+任务号聚合；非配送单空数组）")
    private List<AdminAuditEventVo> auditEvents;
}

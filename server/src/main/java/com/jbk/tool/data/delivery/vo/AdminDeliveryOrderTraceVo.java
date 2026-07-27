package com.jbk.tool.data.delivery.vo;

import com.jbk.tool.data.trade.vo.AdminAuditEventVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 配送订单追溯聚合结果（E2E-03 包C：供 AdminOrderServiceImpl 装配
 * AdminOrderTraceVo 的 delivery/appeals/auditEvents 三个区块）。
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Schema(name = "AdminDeliveryOrderTraceVo", description = "配送订单追溯聚合结果")
public class AdminDeliveryOrderTraceVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "配送区块（履约链 + 资金链核验）")
    private AdminDeliveryTraceVo delivery;

    @Schema(description = "申诉记录（逐行共键核验）")
    private List<AdminAppealTraceVo> appeals;

    @Schema(description = "审计事件（订单号 + 任务号双键聚合）")
    private List<AdminAuditEventVo> auditEvents;
}

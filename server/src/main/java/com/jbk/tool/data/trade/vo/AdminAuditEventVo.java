package com.jbk.tool.data.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 订单追溯·审计事件行 Vo（ws_domain_event 只读投影，E2E-03 包C 先覆盖配送单）。
 * <p>操作者标签由服务端按 actorPortal/actorRole 组装，前端不做身份推导；
 * detail 取事件快照的旧值→新值描述，原样呈现不再加工。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Accessors(chain = true)
@Schema(name = "AdminAuditEventVo", description = "订单追溯审计事件行")
public class AdminAuditEventVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "事件ID")
    private Long id;

    @Schema(description = "事件类型(1363)")
    private Integer eventType;

    @Schema(description = "事件类型名称")
    private String eventTypeLabel;

    @Schema(description = "业务键（订单号/任务号）")
    private String eventKey;

    @Schema(description = "操作/触发方标签")
    private String actorLabel;

    @Schema(description = "状态变化与结果描述（旧值 → 新值）")
    private String detail;

    @Schema(description = "事件时间")
    private String time;
}

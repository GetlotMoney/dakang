package com.jbk.tool.data.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 用户审计记录行（ws_domain_event 只读投影，用户档案「审计记录」分区）。
 *
 * <p>刻意**不下发** EVENT_PAYLOAD：事件快照是原始报文，可能带着号码、身份键和内部字段，
 * 审计视图要的是「谁在什么时候对什么对象做了什么」，不是把报文原样贴到页面上。
 * 需要更细的内容时走带独立权限与脱敏的专用出口，不在本分区放开。</p>
 *
 * @author dakang
 * @since 2026-08-13
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsUserAuditVo", description = "用户审计记录行")
public class WsUserAuditVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "事件类型(1363)")
    private Integer eventType;

    @Schema(description = "事件类型名称")
    private String eventTypeName;

    @Schema(description = "业务对象键（订单号/设备编号/工单号等）")
    private String eventKey;

    @Schema(description = "来源端(1364)")
    private Integer actorPortal;

    @Schema(description = "来源端名称")
    private String actorPortalName;

    @Schema(description = "发生时间")
    private String createTime;
}

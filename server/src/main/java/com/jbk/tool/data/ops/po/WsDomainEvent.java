package com.jbk.tool.data.ops.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 领域事件表 Po（兼任状态级审计：REQ-024 订单/命令/ACK 状态变化必写；REQ-051 携带身份上下文）
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_domain_event")
@Schema(name = "WsDomainEvent", description = "领域事件表")
public class WsDomainEvent extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "事件类型(1363)：1订单状态变化 2设备状态变化 3告警产生 4工单状态变化 5配送节点变化 6支付结果 7分账结果 8指令状态变化")
    @TableField("EVENT_TYPE")
    private Integer eventType;

    @Schema(description = "业务键(max64)：订单号/设备编号/工单号/指令号")
    @TableField("EVENT_KEY")
    private String eventKey;

    @Schema(description = "事件快照JSON（旧值/新值/时间）")
    @TableField("EVENT_PAYLOAD")
    private String eventPayload;

    @Schema(description = "操作者ID（系统或设备触发为空）")
    @TableField("ACTOR_ID")
    private Long actorId;

    @Schema(description = "操作端口(1364)：1公司后台 2用户端 3机主端 4配送端 5渠道端 6系统 7设备")
    @TableField("ACTOR_PORTAL")
    private Integer actorPortal;

    @Schema(description = "操作者角色/身份名称快照(max20)")
    @TableField("ACTOR_ROLE")
    private String actorRole;

    @Schema(description = "是否n8n白名单可订阅(1)：1否 2是")
    @TableField("WHITELIST_FLAG")
    private Integer whitelistFlag;

    @Schema(description = "是否已被通知消费(1)：1否 2是")
    @TableField("CONSUMED_FLAG")
    private Integer consumedFlag;

    @Schema(description = "业务幂等键(max64)：仅需数据库级幂等的领域事件填写；uk_domain_event_biz_key 保证同键最多一条")
    @TableField("BIZ_IDEMPOTENCY_KEY")
    private String bizIdempotencyKey;
}

package com.jbk.tool.data.mini.po;

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
 * 微信订阅通知出站队列（WX-ECO S2）。业务事务只登记，Worker 在事务提交后才发送（外呼不进持锁事务，D-424）。
 * 不落 openid：只存 RECEIVER_USER_ID，发送时按 ID 现查，减少泄漏面。
 *
 * @author dakang
 * @since 2026-08-12
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_wechat_notify_outbox")
@Schema(name = "WsWechatNotifyOutbox", description = "微信订阅通知出站队列")
public class WsWechatNotifyOutbox extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "事件类型，见 WechatNotifyEnum.EventType")
    @TableField("EVENT_TYPE")
    private String eventType;

    @Schema(description = "业务对象类型")
    @TableField("BIZ_OBJECT_TYPE")
    private String bizObjectType;

    @Schema(description = "业务对象编号")
    @TableField("BIZ_OBJECT_NO")
    private String bizObjectNo;

    @Schema(description = "幂等键 WXN:<事件类型>:<对象类型>:<对象编号>")
    @TableField("BIZ_NOTIFY_KEY")
    private String bizNotifyKey;

    @Schema(description = "收件人用户ID")
    @TableField("RECEIVER_USER_ID")
    private Long receiverUserId;

    @Schema(description = "模板数据快照（业务事务内冻结）")
    @TableField("PAYLOAD_SNAP")
    private String payloadSnap;

    @Schema(description = "处理状态(1408)：1待处理 2处理中 3已处理 4待重试 5需人工")
    @TableField("PROCESSING_STATUS")
    private Integer processingStatus;

    @Schema(description = "已重试次数")
    @TableField("RETRY_COUNT")
    private Integer retryCount;

    @Schema(description = "下次可重试时间")
    @TableField("NEXT_RETRY_TIME")
    private String nextRetryTime;

    @Schema(description = "认领时间")
    @TableField("CLAIM_TIME")
    private String claimTime;

    @Schema(description = "租约到期")
    @TableField("LEASE_UNTIL")
    private String leaseUntil;

    @Schema(description = "未发送原因，见 WechatNotifyEnum.SkipReason")
    @TableField("SKIP_REASON")
    private String skipReason;

    @Schema(description = "最近一次失败原因")
    @TableField("LAST_ERROR")
    private String lastError;
}

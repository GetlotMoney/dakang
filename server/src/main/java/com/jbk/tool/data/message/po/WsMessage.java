package com.jbk.tool.data.message.po;

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
 * 站内消息表 Po（E2E-03 A6：站内消息与触发它的业务动作同事务落库、时间同源；
 * 一期不接微信订阅消息）。
 *
 * @author dakang
 * @since 2026-07-23
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_message")
@Schema(name = "WsMessage", description = "站内消息表")
public class WsMessage extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "收件用户ID（查询必须按会话用户强制过滤，铁律6）")
    @TableField("USER_ID")
    private Long userId;

    @Schema(description = "消息领域(1312)：1取水 2卡券 3配送 4机主 5系统")
    @TableField("MSG_DOMAIN")
    private Integer msgDomain;

    @Schema(description = "标题(max100)")
    @TableField("MSG_TITLE")
    private String msgTitle;

    @Schema(description = "正文(max500)，不含明文手机号等敏感信息")
    @TableField("MSG_CONTENT")
    private String msgContent;

    @Schema(description = "渠道：1站内")
    @TableField("MSG_CHANNEL")
    private Integer msgChannel;

    @Schema(description = "发送状态(1313)：站内落库即送达=4")
    @TableField("SEND_STATUS")
    private Integer sendStatus;

    @Schema(description = "发送时间（与业务动作时间同源）")
    @TableField("SEND_TIME")
    private String sendTime;

    @Schema(description = "已读：0未读 1已读")
    @TableField("READ_FLAG")
    private Integer readFlag;

    @Schema(description = "关联对象类型：order/task/appeal/device/service")
    @TableField("OBJECT_TYPE")
    private String objectType;

    @Schema(description = "关联对象业务键：订单号/任务号/申诉ID")
    @TableField("OBJECT_ID")
    private String objectId;
}

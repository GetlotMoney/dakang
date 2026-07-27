package com.jbk.tool.data.delivery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 配送站内消息证据 Vo（ws_message；一期只有站内渠道，与业务动作同事务落库）。
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Accessors(chain = true)
@Schema(name = "AdminDeliveryNotificationVo", description = "配送站内消息证据")
public class AdminDeliveryNotificationVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "消息ID")
    private Long id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "正文（不含明文手机号等敏感信息）")
    private String content;

    @Schema(description = "发送状态(1313)：站内落库即送达=4")
    private Integer sendStatus;

    @Schema(description = "发送时间（与业务动作时间同源）")
    private String sendTime;

    @Schema(description = "关联对象类型：order/appeal")
    private String objectType;

    @Schema(description = "关联对象业务键")
    private String objectId;
}

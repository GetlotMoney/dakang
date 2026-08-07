package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 小程序消息 Vo（E2E-07 包B）。字段全部为收件人本人消息内容，不含任何收件人身份列
 * （USER_ID 不下发——会话即身份，回传只会成为伪造他人视角的素材）。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "MiniMessageVo", description = "小程序站内消息")
public class MiniMessageVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "消息ID")
    private Long messageId;

    @Schema(description = "消息领域(1312)：1取水 2卡券 3配送 4机主 5系统")
    private Integer msgDomain;

    @Schema(description = "标题")
    private String msgTitle;

    @Schema(description = "摘要（正文截断派生，列表展示用）")
    private String summary;

    @Schema(description = "正文")
    private String msgContent;

    @Schema(description = "渠道：1站内；2微信订阅（骨架占位，一期不真实发送）")
    private Integer msgChannel;

    @Schema(description = "发送状态(1313)：站内落库即送达=4")
    private Integer sendStatus;

    @Schema(description = "发送时间（与业务动作时间同源）")
    private String sendTime;

    @Schema(description = "已读：0未读 1已读")
    private Integer readFlag;

    @Schema(description = "关联对象类型：order/task/appeal/device/service")
    private String objectType;

    @Schema(description = "关联对象业务键")
    private String objectId;
}

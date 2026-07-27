package com.jbk.tool.data.user.po;

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
 * 水卡成员授权表 Po（一卡多人，REQ-009）
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_card_member")
@Schema(name = "WsCardMember", description = "水卡成员授权表")
public class WsCardMember extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "水卡ID")
    @TableField("CARD_ID")
    private Long cardId;

    @Schema(description = "被授权用户ID（ws_user.ID）")
    @TableField("MEMBER_USER_ID")
    private Long memberUserId;

    @Schema(description = "成员备注名(max50)")
    @TableField("MEMBER_NAME")
    private String memberName;

    @Schema(description = "单日限额(毫升)，空=不限")
    @TableField("DAY_LIMIT_ML")
    private Long dayLimitMl;

    @Schema(description = "授权生效时间(yyyyMMddHHmmss)，空=立即生效")
    @TableField("EFFECTIVE_TIME")
    private String effectiveTime;

    @Schema(description = "授权失效时间(yyyyMMddHHmmss)，空=长期有效")
    @TableField("EXPIRE_TIME")
    private String expireTime;

    @Schema(description = "授权状态(1333)：1生效 2已解除")
    @TableField("MEMBER_STATUS")
    private Integer memberStatus;
}

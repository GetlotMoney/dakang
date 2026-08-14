package com.jbk.tool.data.user.vo;

import com.jbk.tool.data.BaseEntityVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 水卡成员授权响应对象
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsCardMemberVo", description = "水卡成员授权响应对象")
public class WsCardMemberVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "水卡ID")
    private Long cardId;

    @Schema(description = "被授权用户ID")
    private Long memberUserId;

    @Schema(description = "被授权用户姓名（关联 ws_user 派生）")
    private String memberUserName;

    @Schema(description = "被授权用户手机号（关联 ws_user 派生，已脱敏）")
    private String memberUserPhone;

    @Schema(description = "成员备注名(max50)")
    private String memberName;

    @Schema(description = "单日限额(毫升)，空=不限")
    private Long dayLimitMl;

    @Schema(description = "授权状态(1333)：1生效 2已解除")
    private Integer memberStatus;
}

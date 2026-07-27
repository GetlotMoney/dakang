package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotNull;

import java.io.Serializable;

/**
 * 小程序水卡成员授权撤销入参（CARD-MEMBER）。
 *
 * <p>铁律6：不收 userId——卡主身份只从 KH_USER 会话取；撤销用条件状态更新并校验影响行数。</p>
 */
@Data
@Schema(name = "MiniCardMemberRevokeBo", description = "小程序水卡成员授权撤销入参")
public class MiniCardMemberRevokeBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "cardId 不能为空")
    @Schema(description = "水卡ID（必须为会话用户本人持有）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long cardId;

    @NotNull(message = "memberId 不能为空")
    @Schema(description = "成员记录ID（ws_card_member.ID）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long memberId;
}

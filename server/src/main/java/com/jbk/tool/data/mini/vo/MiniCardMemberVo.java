package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 小程序水卡授权成员 Vo（L2-READ 只读）。
 *
 * <p>字段对齐 miniapp {@code CardMember} 契约。手机号一律脱敏后下发，绝不返回明文。</p>
 */
@Data
@Schema(name = "MiniCardMemberVo", description = "小程序水卡授权成员")
public class MiniCardMemberVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "成员记录ID（ws_card_member.ID）")
    private Long memberId;

    @Schema(description = "被授权用户ID")
    private Long memberUserId;

    @Schema(description = "成员备注名")
    private String memberName;

    @Schema(description = "脱敏手机号（形如 138****5678）")
    private String maskedPhone;

    @Schema(description = "单日限额(毫升)，空=不限")
    private Long dayLimitMl;

    @Schema(description = "授权生效时间(yyyyMMddHHmmss)，空=立即生效")
    private String effectiveTime;

    @Schema(description = "授权失效时间(yyyyMMddHHmmss)，空=长期有效")
    private String expireTime;

    @Schema(description = "授权是否生效（MEMBER_STATUS=1）")
    private Boolean enabled;
}

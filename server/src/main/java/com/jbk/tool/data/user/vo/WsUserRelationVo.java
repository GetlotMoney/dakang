package com.jbk.tool.data.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 用户关系归属（用户档案「关系归属」分区，只读）。
 *
 * <p>只呈现 {@code ws_user.REFERRER_USER_ID} 这一列权威事实：上一级邀请人是谁、本人直接下级有几个。
 * 刻意不做多级树——库里只有一列自引用，没有深度列、没有路径列、没有环检测，画多级层级等于
 * 用界面制造数据证明不了的关系；业务侧收益也只计一级，多画的层在业务上不存在。</p>
 *
 * <p>这里的关系是**用户邀请关系**，与机主加盟推荐是两条独立关系，不得混为一谈。</p>
 *
 * @author dakang
 * @since 2026-08-13
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsUserRelationVo", description = "用户关系归属")
public class WsUserRelationVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "本人邀请码")
    private String ownInviteCode;

    @Schema(description = "注册推送码")
    private String promoCode;

    @Schema(description = "上一级邀请人用户ID；空=未绑定")
    private Long referrerUserId;

    @Schema(description = "上一级邀请人姓名")
    private String referrerUserName;

    @Schema(description = "上一级邀请人手机号（已脱敏）")
    private String referrerUserPhone;

    @Schema(description = "上一级邀请人是否已不可用（数据缺失时为真，展示侧按「已失效」处理）")
    private Boolean referrerMissing;

    @Schema(description = "直接下级人数（只统计直接绑定本人的一级，不做递归）")
    private Long directInviteeCount;
}

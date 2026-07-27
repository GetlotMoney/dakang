package com.jbk.tool.data.user.vo;

import com.jbk.tool.data.BaseEntityVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * C 端用户响应对象；不返回身份证密文，也不返回微信 openid（复审 P1-4）。
 *
 * <p>普通用户列表/详情属管理侧只读视图，微信 openid 为身份密钥，不得随普通响应下发。
 * 如后续确需身份核验，另建带独立权限、脱敏与审计的专用接口，不复用本 VO。</p>
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsUserVo", description = "C端用户响应")
public class WsUserVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "用户名称(max50)")
    private String userName;

    @Schema(description = "性别(20)：1男 2女")
    private Integer userGender;

    @Schema(description = "手机号(max20)")
    private String userPhone;

    @Schema(description = "用户头像URL(max500)")
    private String userAvatar;

    @Schema(description = "归属渠道用户ID")
    private Long channelUserId;

    @Schema(description = "推送人用户ID")
    private Long referrerUserId;

    @Schema(description = "注册推送码(max20)")
    private String promoCode;

}

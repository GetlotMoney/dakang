package com.jbk.tool.data.user.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;

import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * C 端用户主体。
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_user")
@Schema(name = "WsUser", description = "C端用户")
public class WsUser extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户名称(max50)")
    @TableField("USER_NAME")
    private String userName;

    @Schema(description = "性别(20)：1男 2女")
    @TableField("USER_GENDER")
    private Integer userGender;

    @Schema(description = "手机号(max20)")
    @TableField("USER_PHONE")
    private String userPhone;

    @Schema(description = "身份证密文(max500)")
    @TableField("USER_IDENTITY_CIPHER")
    private String userIdentityCipher;

    @Schema(description = "用户头像URL(max500)")
    @TableField("USER_AVATAR")
    private String userAvatar;

    @Schema(description = "是否禁用(10)：1正常 2禁用")
    @TableField("DISABLED_FLAG")
    private Integer disabledFlag;

    @Schema(description = "用户状态：1正常 2注销")
    @TableField("USER_STATUS")
    private Integer userStatus;

    @Schema(description = "积分数")
    @TableField("POINTS")
    private Integer points;


    @Schema(description = "用户小程序openid(max50)")
    @TableField("WECHAT_XCX_OPENID")
    private String wechatXcxOpenid;

    @Schema(description = "归属渠道用户ID")
    @TableField("CHANNEL_USER_ID")
    private Long channelUserId;

    @Schema(description = "推送人用户ID")
    @TableField("REFERRER_USER_ID")
    private Long referrerUserId;

    @Schema(description = "注册推送码(max20)")
    @TableField("PROMO_CODE")
    private String promoCode;

}



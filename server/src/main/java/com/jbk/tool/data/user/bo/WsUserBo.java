package com.jbk.tool.data.user.bo;

import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.Serializable;

/**
 * C 端用户请求对象。
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsUserBo", description = "C端用户请求")
public class WsUserBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class, message = "用户信息不为空")
    private Long id;

    @Schema(description = "用户名称(max50)")
    @NotEmpty(groups = InsertGroup.class, message = "用户名称不为空")
    @Size(groups = InsertGroup.class, max = 50, message = "用户名称长度不能超过50")
    private String userName;

    @Schema(description = "性别(20)：1男 2女")
    @NotNull(groups = InsertGroup.class, message = "用户性别不为空")
    private Integer userGender;

    @Schema(description = "手机号(max20)")
    @NotEmpty(groups = InsertGroup.class, message = "手机号不为空")
    @Size(groups = InsertGroup.class, max = 20, message = "手机号长度不能超过20")
    private String userPhone;

    @Schema(description = "用户头像URL(max500)")
    @Size(groups = InsertGroup.class, max = 500, message = "用户头像URL长度不能超过500")
    private String userAvatar;


    @Schema(description = "用户小程序openid(max50)")
    @Size(groups = InsertGroup.class, max = 50, message = "用户小程序openid长度不能超过50")
    private String wechatXcxOpenid;


}

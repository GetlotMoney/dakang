package com.jbk.tool.data.api.bo;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.validator.group.InsertGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * @author xs
 * @since 2025-09-05
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("api_employee")
@Schema(name = "AuthBo", description = "$!{table.comment}")
public class AuthBo implements Serializable {

    private static final long serialVersionUID = 1L;
    @Schema(description = "登录账号")
    @NotEmpty(groups = InsertGroup.class, message = "登录账号不为空")
    private String loginName;

    @Schema(description = "登录密码")
    @NotEmpty(groups = InsertGroup.class, message = "登录密码不为空")
    private String loginPwd;
}



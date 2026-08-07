package com.jbk.tool.data.api.po;

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
 * <p>
 * 
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("api_employee")
@Schema(name = "ApiEmployee", description = "$!{table.comment}")
public class ApiEmployee extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "登录账号")
    @TableField("LOGIN_NAME")
    private String loginName;

    @Schema(description = "登录密码")
    @TableField("LOGIN_PWD")
    private String loginPwd;

    @Schema(description = "员工名称max10")
    @TableField("EMPLOYEE_NAME")
    private String employeeName;

    @Schema(description = "员工性别100")
    @TableField("EMPLOYEE_GENDER")
    private Integer employeeGender;

    @Schema(description = "手机号码")
    @TableField("EMPLOYEE_PHONE")
    private String employeePhone;

    @Schema(description = "部门ID")
    @TableField("DEPT_ID")
    private Long deptId;

    @Schema(description = "职务ID")
    @TableField("POSITION_ID")
    private Long positionId;

    @Schema(description = "是否被禁用1")
    @TableField("DISABLED_FLAG")
    private Integer disabledFlag;

    @Schema(description = "是否需强制修改密码1：建号/重置后为2是，本人改密后回1否")
    @TableField("PWD_CHANGE_FLAG")
    private Integer pwdChangeFlag;
}



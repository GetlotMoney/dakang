package com.jbk.tool.data.api.bo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.List;

/**
 * @author xs
 * @since 2025-09-05
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("api_employee")
@Schema(name = "ApiEmployeeBo", description = "$!{table.comment}")
public class ApiEmployeeBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class, message = "员工信息不为空")
    private Long id;

    @Schema(description = "登录账号max20")
    @NotEmpty(groups = InsertGroup.class, message = "登录账号")
    private String loginName;

    @Schema(description = "登录密码")
    private String loginPwd;

    @Schema(description = "设置的新密码")
    private String newLoginPwd;

    @Schema(description = "员工名称max10")
    @NotEmpty(groups = InsertGroup.class, message = "员工名称不为空")
    private String employeeName;

    @Schema(description = "员工性别100")
    @NotNull(groups = InsertGroup.class, message = "员工性别不为空")
    private Integer employeeGender;

    @Schema(description = "手机号码max11")
    @NotEmpty(groups = InsertGroup.class, message = "手机号不为空")
    private String employeePhone;

    @Schema(description = "部门ID")
    @NotNull(groups = InsertGroup.class, message = "部门不为空")
    private Long deptId;

    @Schema(description = "职务ID")
    @TableField("POSITION_ID")
    private Long positionId;

    @Schema(description = "是否被禁用1")
    @TableField("DISABLED_FLAG")
    private Integer disabledFlag;

    @Schema(description = "用户标签")
    private List<Long> tagIdList;

    @Schema(description = "用户标签")
    private Long tagId;
}



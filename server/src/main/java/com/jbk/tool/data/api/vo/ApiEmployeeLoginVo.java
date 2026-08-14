package com.jbk.tool.data.api.vo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntityVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

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
@Schema(name = "ApiEmployeeLoginVo", description = "$!{table.comment}")
public class ApiEmployeeLoginVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "登录账号")
    @TableField("LOGIN_NAME")
    private String loginName;

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

    private List<ApiRbacMenuVo> rbacMenuList;

    @Schema(description = "会话token（前端存储后经 dakang-token 请求头回传，避免同机多项目 Cookie 串号）")
    @TableField(exist = false)
    private String tokenValue;

    @Schema(description = "是否需先修改初始密码：true 时除改密/退出外的接口会被服务端拒绝，前端应直接进入改密页")
    @TableField(exist = false)
    private Boolean pwdChangeRequired;
}



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
@TableName("api_rbac_role_employee")
@Schema(name = "ApiRbacRoleEmployee", description = "$!{table.comment}")
public class ApiRbacRoleEmployee extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "角色ID")
    @TableField("ROLE_ID")
    private Long roleId;

    @Schema(description = "员工ID")
    @TableField("EMPLOYEE_ID")
    private Long employeeId;
}



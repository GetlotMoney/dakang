package com.jbk.tool.data.api.bo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.InsertGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;

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
@TableName("api_rbac_role")
@Schema(name = "ApiRbacRoleBo", description = "$!{table.comment}")
public class ApiRbacRoleBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "角色名称max10")
    @NotEmpty(groups = InsertGroup.class, message = "角色名称不为空")
    private String roleName;

    @Schema(description = "角色标识max10")
    @NotEmpty(groups = InsertGroup.class, message = "角色标识不为空")
    private String roleCode;

    @Schema(description = "角色备注max300")
    @TableField("ROLE_REMARK")
    private String roleRemark;

    @Schema(description = "角色排序max10000")
    @NotNull(groups = InsertGroup.class, message = "角色排序不为空")
    private Integer roleSort;
}



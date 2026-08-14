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

/**
 * @author xs
 * @since 2025-09-05
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("api_rbac_role")
@Schema(name = "ApiRbacRoleVo", description = "$!{table.comment}")
public class ApiRbacRoleVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "角色名称max10")
    @TableField("ROLE_NAME")
    private String roleName;

    @Schema(description = "角色标识max10")
    @TableField("ROLE_CODE")
    private String roleCode;

    @Schema(description = "角色备注max300")
    @TableField("ROLE_REMARK")
    private String roleRemark;

    @Schema(description = "角色排序max10000")
    @TableField("ROLE_SORT")
    private Integer roleSort;
}



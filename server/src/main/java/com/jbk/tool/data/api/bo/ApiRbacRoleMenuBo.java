package com.jbk.tool.data.api.bo;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.validator.group.InsertGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

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
@TableName("api_rbac_role_menu")
@Schema(name = "ApiRbacRoleMenuBo", description = "$!{table.comment}")
public class ApiRbacRoleMenuBo implements Serializable {

    @Schema(description = "角色ID")
    @NotNull(groups = InsertGroup.class,message = "角色不为空")
    private Long roleId;

    @Schema(description = "菜单ID")
    @NotNull(groups = InsertGroup.class,message = "菜单信息必须定义")
    private List<Long> menuIdList;
}



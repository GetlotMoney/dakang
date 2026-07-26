package com.jbk.serve.service.api;

import com.github.yulichang.base.MPJBaseService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.UserPermission;
import com.jbk.tool.data.api.bo.ApiRbacRoleBo;
import com.jbk.tool.data.api.bo.ApiRbacRoleEmployeeBo;
import com.jbk.tool.data.api.bo.ApiRbacRoleMenuBo;
import com.jbk.tool.data.api.po.ApiRbacRole;
import com.jbk.tool.data.api.vo.ApiEmployeeVo;
import com.jbk.tool.data.api.vo.ApiRbacMenuVo;
import com.jbk.tool.data.api.vo.ApiRbacRoleVo;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
public interface IApiRbacRoleService extends MPJBaseService<ApiRbacRole> {

    UserPermission getUserPermission(Long employeeId);

    Long saveData(ApiRbacRoleBo rbacRoleBo);

    Boolean updateData(ApiRbacRoleBo rbacRoleBo);

    List<ApiRbacRoleVo> listData();

    List<Long> listMenuId(Long roleId);

    Boolean updateMenu(ApiRbacRoleMenuBo rbacRoleMenuBo);

    PageDataVo<ApiEmployeeVo> pageEmoloyee(ApiRbacRoleBo rbacRoleBo);

    List<ApiRbacMenuVo> listMenuByUser(Long id);

    Boolean delData(Long id);

    Boolean addEmployeeToRoleList(ApiRbacRoleEmployeeBo rbacRoleEmployeeBo);

    List<ApiRbacRoleVo> listRoleByEmployee(Long employeeId);

    Boolean delByMenuIdCache(Long id);
}



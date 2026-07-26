package com.jbk.serve.service.api;

import com.github.yulichang.base.MPJBaseService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.bo.ApiRbacRoleBo;
import com.jbk.tool.data.api.bo.ApiRbacRoleEmployeeBo;
import com.jbk.tool.data.api.po.ApiRbacRoleEmployee;
import com.jbk.tool.data.api.vo.ApiEmployeeVo;
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
public interface IApiRbacRoleEmployeeService extends MPJBaseService<ApiRbacRoleEmployee> {

    List<ApiRbacRoleVo> listRoleByUserId(Long employeeId);

    PageDataVo<ApiEmployeeVo> pageEmoloyeeByRole(ApiRbacRoleBo rbacRoleBo);

    Boolean addEmployeeToRoleList(ApiRbacRoleEmployeeBo rbacRoleEmployeeBo);

    Boolean delByRoleCache(Long roleId);

    Boolean delByRoleListCache(List<Long> roleIdList);
}



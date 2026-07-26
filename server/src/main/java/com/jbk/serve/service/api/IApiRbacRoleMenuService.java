package com.jbk.serve.service.api;

import com.github.yulichang.base.MPJBaseService;
import com.jbk.tool.data.api.bo.ApiRbacRoleMenuBo;
import com.jbk.tool.data.api.po.ApiRbacRoleMenu;
import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.data.api.vo.ApiRbacMenuVo;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
public interface IApiRbacRoleMenuService extends MPJBaseService<ApiRbacRoleMenu> {
    List<ApiRbacMenuVo> listMenuByRoleList(List<Long> roleIdList);

    List<Long> listMenuIdByRole(Long roleId);

    Boolean delByRole(Long roleId);

    Boolean saveBatchByRole(ApiRbacRoleMenuBo rbacRoleMenuBo);
}



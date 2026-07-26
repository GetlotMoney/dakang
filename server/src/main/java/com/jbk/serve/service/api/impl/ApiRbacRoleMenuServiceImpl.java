package com.jbk.serve.service.api.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.yulichang.base.MPJBaseServiceImpl;
import com.github.yulichang.toolkit.MPJWrappers;
import com.google.common.collect.Lists;
import com.jbk.tool.consts.ApiEnum;
import com.jbk.tool.data.api.bo.ApiRbacRoleMenuBo;
import com.jbk.tool.data.api.po.ApiRbacMenu;
import com.jbk.tool.data.api.po.ApiRbacRoleMenu;
import com.jbk.serve.mapper.api.ApiRbacRoleMenuMapper;
import com.jbk.serve.service.api.IApiRbacRoleMenuService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.tool.data.api.vo.ApiRbacMenuVo;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
@Service
public class ApiRbacRoleMenuServiceImpl extends MPJBaseServiceImpl<ApiRbacRoleMenuMapper, ApiRbacRoleMenu> implements IApiRbacRoleMenuService {

    @Override
    public List<ApiRbacMenuVo> listMenuByRoleList(List<Long> roleIdList) {
        if (ObjectUtil.isEmpty(roleIdList)) {
            return Lists.newArrayList();
        }
        List<ApiRbacMenuVo> menuList = selectJoinList(ApiRbacMenuVo.class,
                MPJWrappers.lambdaJoin(ApiRbacRoleMenu.class)
                        .leftJoin(ApiRbacMenu.class, ApiRbacMenu::getId, ApiRbacRoleMenu::getMenuId)
                        // MPJ 不会替联表对象自动追加 @TableLogic 条件；必须显式排除已软删除菜单。
                        // 角色和菜单都必须处于启用态，防止失效绑定在登录时重新下发。
                        .eq(ApiRbacMenu::getDataStatus, 0)
                        .eq(ApiRbacMenu::getMenuDisabledFlag, ApiEnum.Flag.NO.value())
                        .in(ApiRbacRoleMenu::getRoleId, roleIdList)
                        .orderByAsc(ApiRbacMenu::getMenuParentId)
                        .orderByDesc(ApiRbacMenu::getMenuSort)
                        .selectAsClass(ApiRbacMenu.class, ApiRbacMenuVo.class)
        );
        return menuList;
    }

    @Override
    public List<Long> listMenuIdByRole(Long roleId) {
        List<ApiRbacRoleMenu> roleMenuList = list(Wrappers.lambdaQuery(ApiRbacRoleMenu.class)
                .eq(ApiRbacRoleMenu::getRoleId, roleId)
        );
        if (ObjectUtil.isEmpty(roleMenuList)) {
            return Lists.newArrayList();
        }
        List<Long> menuIdList = roleMenuList.stream().map(ApiRbacRoleMenu::getMenuId).collect(Collectors.toList());
        return menuIdList;
    }

    @Override
    public Boolean delByRole(Long roleId) {
        remove(Wrappers.lambdaQuery(ApiRbacRoleMenu.class)
                .eq(ApiRbacRoleMenu::getRoleId, roleId)
        );
        return Boolean.TRUE;
    }

    @Override
    public Boolean saveBatchByRole(ApiRbacRoleMenuBo rbacRoleMenuBo) {
        if (ObjectUtil.isEmpty(rbacRoleMenuBo.getMenuIdList())) {
            return Boolean.TRUE;
        }
        List<ApiRbacRoleMenu> roleMenuList = rbacRoleMenuBo.getMenuIdList().stream().map(e -> {
            ApiRbacRoleMenu roleMenu = new ApiRbacRoleMenu();
            roleMenu.setRoleId(rbacRoleMenuBo.getRoleId());
            roleMenu.setMenuId(e);
            return roleMenu;
        }).collect(Collectors.toList());
        saveBatch(roleMenuList);
        return Boolean.TRUE;
    }
}



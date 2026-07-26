package com.jbk.serve.service.api.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.yulichang.base.MPJBaseServiceImpl;
import com.github.yulichang.toolkit.MPJWrappers;
import com.google.common.collect.Lists;
import com.jbk.serve.service.api.IApiRbacRoleEmployeeService;
import com.jbk.serve.service.api.IApiRbacRoleMenuService;
import com.jbk.tool.config.system.redis.consts.RedisExpire;
import com.jbk.tool.config.system.redis.consts.RedisKeys;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.UserPermission;
import com.jbk.tool.data.api.bo.ApiRbacRoleBo;
import com.jbk.tool.data.api.bo.ApiRbacRoleEmployeeBo;
import com.jbk.tool.data.api.bo.ApiRbacRoleMenuBo;
import com.jbk.tool.data.api.po.ApiRbacRole;
import com.jbk.serve.mapper.api.ApiRbacRoleMapper;
import com.jbk.serve.service.api.IApiRbacRoleService;
import com.jbk.tool.data.api.po.ApiRbacRoleEmployee;
import com.jbk.tool.data.api.po.ApiRbacRoleMenu;
import com.jbk.tool.data.api.vo.ApiEmployeeVo;
import com.jbk.tool.data.api.vo.ApiRbacMenuVo;
import com.jbk.tool.data.api.vo.ApiRbacRoleVo;
import com.jbk.tool.utils.OptionalUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
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
public class ApiRbacRoleServiceImpl extends MPJBaseServiceImpl<ApiRbacRoleMapper, ApiRbacRole> implements IApiRbacRoleService {

    @Autowired
    private IApiRbacRoleEmployeeService roleEmployeeService;

    @Autowired
    private IApiRbacRoleMenuService roleMenuService;

    @Override
    @Cached(
            name = RedisKeys.Comm.RBAC,
            key = "#employeeId",
            expire = RedisExpire.HOUR_SIX_EXPIRE_,
            cacheType = CacheType.REMOTE)
    public UserPermission getUserPermission(Long employeeId) {
        UserPermission userPermission = new UserPermission();
        userPermission.setPermissionList(new ArrayList<>());
        userPermission.setRoleList(new ArrayList<>());
        if (ObjectUtil.isNull(employeeId)) {
            return userPermission;
        }
        // 查询用户角色
        List<ApiRbacRoleVo> roleList = roleEmployeeService.listRoleByUserId(employeeId);
        userPermission.getRoleList().addAll(roleList.stream().map(ApiRbacRoleVo::getRoleCode).collect(Collectors.toSet()));
        // 前端菜单和功能点清单
        List<Long> roleIdList = roleList.stream().map(ApiRbacRoleVo::getId).collect(Collectors.toList());
        List<ApiRbacMenuVo> menuList = roleMenuService.listMenuByRoleList(roleIdList);
        // 权限列表
        HashSet<String> permissionSet = new HashSet<>();
        for (ApiRbacMenuVo menu : menuList) {
            String perms = menu.getMenuApiPerms();
            if (StrUtil.isEmpty(perms)) {
                continue;
            }
            //接口权限
            String[] split = perms.split(",");
            permissionSet.addAll(Arrays.asList(split));
        }
        userPermission.getPermissionList().addAll(permissionSet);
        return userPermission;
    }

    @Override
    public List<ApiRbacMenuVo> listMenuByUser(Long employeeId) {
        // 查询用户角色
        List<ApiRbacRoleVo> roleList = roleEmployeeService.listRoleByUserId(employeeId);
        // 前端菜单和功能点清单
        List<Long> roleIdList = roleList.stream().map(ApiRbacRoleVo::getId).collect(Collectors.toList());
        List<ApiRbacMenuVo> menuList = roleMenuService.listMenuByRoleList(roleIdList);
        if (ObjectUtil.isEmpty(menuList)) {
            return Lists.newArrayList();
        }
        List<ApiRbacMenuVo> menuVoList = BeanUtil.copyToList(menuList, ApiRbacMenuVo.class);
        return menuVoList;
    }

    @Override
    public Boolean updateData(ApiRbacRoleBo rbacRoleBo) {
        ApiRbacRole rbacRole = BeanUtil.copyProperties(rbacRoleBo, ApiRbacRole.class);
        updateById(rbacRole);
        return Boolean.TRUE;
    }

    @Override
    public Boolean delData(Long id) {
        long count = roleEmployeeService.count(Wrappers.lambdaQuery(ApiRbacRoleEmployee.class)
                .eq(ApiRbacRoleEmployee::getRoleId, id)
        );
        OptionalUtils.gtZeroElseThrow(count, "当前角色下关联用户无法删除");
        removeById(id);
        roleMenuService.remove(Wrappers.lambdaQuery(ApiRbacRoleMenu.class)
                .eq(ApiRbacRoleMenu::getRoleId, id)
        );
        return Boolean.TRUE;
    }


    @Override
    public Long saveData(ApiRbacRoleBo rbacRoleBo) {
        long count = count(Wrappers.lambdaQuery(ApiRbacRole.class)
                .eq(ApiRbacRole::getRoleName, rbacRoleBo.getRoleName())
                .or()
                .eq(ApiRbacRole::getRoleCode, rbacRoleBo.getRoleCode())
        );
        OptionalUtils.gtZeroElseThrow(count, "角色编码或名称已存在");
        ApiRbacRole rbacRole = BeanUtil.copyProperties(rbacRoleBo, ApiRbacRole.class);
        save(rbacRole);
        return rbacRole.getId();
    }

    @Override
    public List<ApiRbacRoleVo> listData() {
        List<ApiRbacRole> rbacRoleList = list(Wrappers.lambdaQuery(ApiRbacRole.class)
                .orderByDesc(ApiRbacRole::getRoleSort)
        );
        if (ObjectUtil.isEmpty(rbacRoleList)) {
            return Lists.newArrayList();
        }
        return BeanUtil.copyToList(rbacRoleList, ApiRbacRoleVo.class);
    }

    @Override
    public List<Long> listMenuId(Long roleId) {
        return roleMenuService.listMenuIdByRole(roleId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateMenu(ApiRbacRoleMenuBo rbacRoleMenuBo) {
        // 删除原有权限
        roleMenuService.delByRole(rbacRoleMenuBo.getRoleId());
        // 创建权限
        if (ObjectUtil.isNotEmpty(rbacRoleMenuBo.getMenuIdList())) {
            roleMenuService.saveBatchByRole(rbacRoleMenuBo);
        }
        // 刷新缓存
        roleEmployeeService.delByRoleCache(rbacRoleMenuBo.getRoleId());
        return Boolean.TRUE;
    }

    @Override
    public PageDataVo<ApiEmployeeVo> pageEmoloyee(ApiRbacRoleBo rbacRoleBo) {
        return roleEmployeeService.pageEmoloyeeByRole(rbacRoleBo);
    }

    @Override
    public Boolean addEmployeeToRoleList(ApiRbacRoleEmployeeBo rbacRoleEmployeeBo) {
        return roleEmployeeService.addEmployeeToRoleList(rbacRoleEmployeeBo);
    }

    @Override
    public List<ApiRbacRoleVo> listRoleByEmployee(Long employeeId) {
        return roleEmployeeService.listRoleByUserId(employeeId);
    }

    @Override
    public Boolean delByMenuIdCache(Long id) {
        // 查询该权限绑定的所有角色
        List<ApiRbacRoleVo> roleList = roleMenuService.selectJoinList(ApiRbacRoleVo.class,
                MPJWrappers.lambdaJoin(ApiRbacRoleMenu.class)
                        .leftJoin(ApiRbacRole.class, ApiRbacRole::getId, ApiRbacRoleMenu::getRoleId)
                        .eq(ApiRbacRoleMenu::getMenuId, id)
                        .selectAsClass(ApiRbacRole.class, ApiRbacRoleVo.class)
        );
        if (ObjectUtil.isEmpty(roleList)) {
            return Boolean.TRUE;
        }
        List<Long> roleIdList = roleList.stream().map(ApiRbacRoleVo::getId).collect(Collectors.toList());
        return roleEmployeeService.delByRoleListCache(roleIdList);
    }


}



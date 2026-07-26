package com.jbk.serve.service.api.impl;

import cn.hutool.core.util.ObjectUtil;
import com.alicp.jetcache.Cache;
import com.alicp.jetcache.CacheManager;
import com.alicp.jetcache.anno.CacheInvalidate;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.template.QuickConfig;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseServiceImpl;
import com.github.yulichang.toolkit.MPJWrappers;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.jbk.tool.config.system.redis.consts.RedisExpire;
import com.jbk.tool.config.system.redis.consts.RedisKeys;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.UserPermission;
import com.jbk.tool.data.api.bo.ApiRbacRoleBo;
import com.jbk.tool.data.api.bo.ApiRbacRoleEmployeeBo;
import com.jbk.tool.data.api.po.ApiEmployee;
import com.jbk.tool.data.api.po.ApiRbacRole;
import com.jbk.tool.data.api.po.ApiRbacRoleEmployee;
import com.jbk.serve.mapper.api.ApiRbacRoleEmployeeMapper;
import com.jbk.serve.service.api.IApiRbacRoleEmployeeService;
import com.jbk.tool.data.api.vo.ApiEmployeeVo;
import com.jbk.tool.data.api.vo.ApiRbacRoleVo;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
public class ApiRbacRoleEmployeeServiceImpl extends MPJBaseServiceImpl<ApiRbacRoleEmployeeMapper, ApiRbacRoleEmployee> implements IApiRbacRoleEmployeeService {

    @Autowired
    private CacheManager cacheManager;

    private Cache<Long, UserPermission> jetCacheUserPermission;

    @PostConstruct
    public void init() {
        QuickConfig cacheTeTextConfig = QuickConfig.newBuilder(RedisKeys.Comm.RBAC)
                .expire(Duration.ofSeconds(RedisExpire.HOUR_SIX_EXPIRE_))
                .cacheType(CacheType.REMOTE)
                .build();
        jetCacheUserPermission = cacheManager.getOrCreateCache(cacheTeTextConfig);
    }

    @Override
    public List<ApiRbacRoleVo> listRoleByUserId(Long employeeId) {
        if (ObjectUtil.isNull(employeeId)) {
            return Lists.newArrayList();
        }
        List<ApiRbacRoleVo> apiRbacRoles = selectJoinList(ApiRbacRoleVo.class,
                MPJWrappers.lambdaJoin(ApiRbacRoleEmployee.class)
                        .leftJoin(ApiRbacRole.class, ApiRbacRole::getId, ApiRbacRoleEmployee::getRoleId)
                        // 联表对象的逻辑删除条件需显式声明，避免已删除角色继续参与菜单与权限计算。
                        .eq(ApiRbacRole::getDataStatus, 0)
                        .eq(ApiRbacRoleEmployee::getEmployeeId, employeeId)
                        .selectAsClass(ApiRbacRole.class, ApiRbacRoleVo.class)
        );
        return apiRbacRoles;
    }

    @Override
    public PageDataVo<ApiEmployeeVo> pageEmoloyeeByRole(ApiRbacRoleBo rbacRoleBo) {
        Page<ApiEmployeeVo> employeeVoPage = selectJoinListPage(
                new Page<>(rbacRoleBo.getCurrent(), rbacRoleBo.getSize()),
                ApiEmployeeVo.class,
                MPJWrappers.lambdaJoin(ApiRbacRoleEmployee.class)
                        .leftJoin(ApiEmployee.class, ApiEmployee::getId, ApiRbacRoleEmployee::getEmployeeId)
                        .eq(ApiRbacRoleEmployee::getRoleId, rbacRoleBo.getId())
                        .orderByDesc(ApiRbacRoleEmployee::getId)
                        .selectAsClass(ApiEmployee.class, ApiEmployeeVo.class)
        );
        return PageDataVo.getPageData(employeeVoPage);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheInvalidate(
            name = RedisKeys.Comm.RBAC,
            key = "#rbacRoleEmployeeBo.employeeId")
    public Boolean addEmployeeToRoleList(ApiRbacRoleEmployeeBo rbacRoleEmployeeBo) {
        // 删除原有角色
        remove(Wrappers.lambdaQuery(ApiRbacRoleEmployee.class)
                .eq(ApiRbacRoleEmployee::getEmployeeId,rbacRoleEmployeeBo.getEmployeeId())
        );
        List<ApiRbacRoleEmployee> apiRbacRoleEmployeeList = rbacRoleEmployeeBo.getRoleIdList().stream().map(e -> {
            ApiRbacRoleEmployee apiRbacRoleEmployee = new ApiRbacRoleEmployee();
            apiRbacRoleEmployee.setEmployeeId(rbacRoleEmployeeBo.getEmployeeId());
            apiRbacRoleEmployee.setRoleId(e);
            return apiRbacRoleEmployee;
        }).collect(Collectors.toList());
        // 保存现有角色
        if(ObjectUtil.isEmpty(apiRbacRoleEmployeeList)){
            return Boolean.TRUE;
        }
        saveBatch(apiRbacRoleEmployeeList);
        return Boolean.TRUE;
    }

    @Override
    public Boolean delByRoleCache(Long roleId) {
        // 查询所有拥有该角色的人员
        List<ApiRbacRoleEmployee> list = list(Wrappers.lambdaQuery(ApiRbacRoleEmployee.class)
                .eq(ApiRbacRoleEmployee::getRoleId, roleId));
        if (ObjectUtil.isEmpty(list)){
            return Boolean.TRUE;
        }
        Set<Long> userSet = Sets.newHashSet();
        list.forEach(e -> userSet.add(e.getEmployeeId()));
        jetCacheUserPermission.removeAll(userSet);
        return Boolean.TRUE;
    }

    @Override
    public Boolean delByRoleListCache(List<Long> roleIdList) {
        // 查询所有拥有该角色的人员
        List<ApiRbacRoleEmployee> list = list(Wrappers.lambdaQuery(ApiRbacRoleEmployee.class)
                .in(ApiRbacRoleEmployee::getRoleId, roleIdList));
        if (ObjectUtil.isEmpty(list)){
            return Boolean.TRUE;
        }
        Set<Long> userSet = Sets.newHashSet();
        list.forEach(e -> userSet.add(e.getEmployeeId()));
        jetCacheUserPermission.removeAll(userSet);
        return Boolean.TRUE;
    }

}



package com.jbk.tool.config.system.satoken;

import cn.dev33.satoken.stp.StpInterface;
import cn.hutool.core.util.ObjectUtil;
import com.google.common.collect.Lists;
import com.jbk.serve.service.api.IApiRbacRoleService;
import com.jbk.tool.data.api.UserPermission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @ClassName StpInterfaceImpI
 * @Author xs
 * @Date 2024/7/25 9:30
 * @Version 1.0
 */
@Component
public class StpInterfaceImpI implements StpInterface {

    @Autowired
    private IApiRbacRoleService rbacRoleService;

    @Override
    public List<String> getPermissionList(Object o, String s) {
        if(ObjectUtil.isNull(o)){
            return Lists.newArrayList();
        }
        UserPermission userPermission = rbacRoleService.getUserPermission(Long.valueOf(String.valueOf(o)));
        return userPermission.getPermissionList();
    }

    @Override
    public List<String> getRoleList(Object o, String s) {
        if(ObjectUtil.isNull(o)){
            return Lists.newArrayList();
        }
        UserPermission userPermission = rbacRoleService.getUserPermission(Long.valueOf(String.valueOf(o)));
        return userPermission.getRoleList();
    }
}



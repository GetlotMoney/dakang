package com.jbk.tool.interceptor;

import cn.dev33.satoken.annotation.*;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.exception.NotSafeException;
import cn.dev33.satoken.stp.StpLogic;
import cn.hutool.core.util.StrUtil;
import com.google.common.collect.Lists;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.exception.ErrorMsg;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.satoken.StpKit;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @ClassName SaCheckOrAspect
 * @Author xs
 * @Date 2024/7/25 10:36
 * @Version 1.0
 */
@Component
@Aspect
@Slf4j
@Order(1)
public class SaCheckOrAspect {

    /**
     * execution （【权限修饰符】【返回类型】【类全路径】【方法名称】(【参数列表】)）
     */
    @Pointcut("execution(public * com.jbk.serve.controller..*Controller.*(..)) && @annotation(com.jbk.tool.annotation.MySaCheckOr)")
    public void pointcut() {

    }

    @Before("pointcut()")
    public void doBefore(JoinPoint joinPoint) throws Throwable {
        checkSys(joinPoint);
    }


    // 校验系统====SaToken
    private void checkSys(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        MySaCheckOr annotation = signature.getMethod().getAnnotation(MySaCheckOr.class);
        // 登录
        SaCheckLogin[] loginOrs = annotation.login();
        checkLogin(loginOrs);
        // 权限
        SaCheckPermission[] permissionOrs = annotation.permission();
        checkPermission(permissionOrs);
        // 角色
        SaCheckRole[] roleOrs = annotation.role();
        checkRole(roleOrs);
        // 二级认证
        SaCheckSafe[] safeOrs = annotation.safe();
        checkSafe(safeOrs);
    }

    // 二级认证
    private Boolean checkSafe(SaCheckSafe[] safeOrs) {
        if (safeOrs.length == 0) {
            return Boolean.TRUE;
        }
        List<String> loginDriverOrs = Lists.newArrayList();
        for (SaCheckSafe saCheckSafe : safeOrs) {
            loginDriverOrs.add(saCheckSafe.type());
        }
        // 校验登录状态
        checkLogin(loginDriverOrs);
        // 校验二级认证
        NotSafeException safeException = null;
        for (SaCheckSafe safeOr : safeOrs) {
            StpLogic stpLogic = StpKit.getStpByDevice(safeOr.type());
            if (!stpLogic.isLogin()) {
                continue;
            }
            try {
                stpLogic.checkSafe();
                return Boolean.TRUE;
            } catch (NotSafeException e) {
                safeException = e;
            }
        }
        throw safeException;
    }

    // 判断权限
    private Boolean checkRole(SaCheckRole[] roleOrs) {
        if (roleOrs.length == 0) {
            return Boolean.TRUE;
        }
        List<String> loginDriverOrs = Lists.newArrayList();
        for (SaCheckRole role : roleOrs) {
            loginDriverOrs.add(role.type());
        }
        // 校验登录状态
        checkLogin(loginDriverOrs);
        // 校验角色
        String errorRole = StrUtil.EMPTY;
        for (SaCheckRole role : roleOrs) {
            StpLogic stpLogic = StpKit.getStpByDevice(role.type());
            if (!stpLogic.isLogin()) {
                continue;
            }
            String[] roleList = role.value();
            SaMode mode = role.mode();
            if (roleList.length == 0) {
                continue;
            }
            try {
                if (mode.equals(SaMode.AND)) {
                    stpLogic.checkRoleAnd(roleList);
                } else {
                    stpLogic.checkRoleOr(roleList);
                }
                return Boolean.TRUE;
            } catch (NotRoleException e) {
                if (mode.equals(SaMode.AND)) {
                    errorRole = StrUtil.join(" and ", roleList);
                } else {
                    errorRole = StrUtil.join(" or ", roleList);
                }
            }
        }
        throw new JbkException(ErrorMsg.ROLE_FAIL.getMsg() + ",缺少角色【" + errorRole + "】");
    }

    // 判断权限
    private Boolean checkPermission(SaCheckPermission[] permissionOrs) {
        if (permissionOrs.length == 0) {
            return Boolean.TRUE;
        }
        List<String> loginDriverOrs = Lists.newArrayList();
        for (SaCheckPermission permission : permissionOrs) {
            loginDriverOrs.add(permission.type());
        }
        // 校验登录状态
        checkLogin(loginDriverOrs);
        // 校验权限
        String errorPermission = StrUtil.EMPTY;
        for (SaCheckPermission permission : permissionOrs) {
            StpLogic stpLogic = StpKit.getStpByDevice(permission.type());
            if (!stpLogic.isLogin()) {
                continue;
            }
            SaMode mode = permission.mode();
            String[] perAndList = permission.value();
            String[] perOrList = permission.orRole();
            if (perAndList.length == 0 && perOrList.length == 0) {
                continue;
            }
            if (perAndList.length != 0 && perOrList.length != 0) {
                throw new JbkException(ErrorMsg.ERROR_CUSTOM);
            }
            try {
                if (perAndList.length != 0 && mode.equals(SaMode.AND)) {
                    stpLogic.checkPermissionAnd(perAndList);
                }
                if (perAndList.length != 0 && mode.equals(SaMode.OR)) {
                    stpLogic.checkPermissionOr(perAndList);
                }
                if (perOrList.length != 0) {
                    stpLogic.checkPermissionOr(perOrList);
                }
                return Boolean.TRUE;
            } catch (NotPermissionException e) {
                if (perAndList.length != 0 && mode.equals(SaMode.AND)) {
                    errorPermission = StrUtil.join(" and ", perAndList);
                }
                if (perAndList.length != 0 && mode.equals(SaMode.OR)) {
                    errorPermission = StrUtil.join(" or ", perAndList);
                }
                if (perOrList.length != 0) {
                    errorPermission = StrUtil.join(" or ", perOrList);
                }
            }
        }
        throw new JbkException(ErrorMsg.PERMISSION_FAIL.getMsg() + ",缺少权限【" + errorPermission + "】");
    }

    // 判断登录
    private Boolean checkLogin(SaCheckLogin[] loginOrs) {
        if (loginOrs.length == 0) {
            return Boolean.TRUE;
        }
        List<String> loginDriverOrs = Lists.newArrayList();
        for (SaCheckLogin login : loginOrs) {
            loginDriverOrs.add(login.type());
        }
        // 校验登录状态
        checkLogin(loginDriverOrs);
        return Boolean.TRUE;
    }

    private void checkLogin(List<String> loginOrs) {
        for (String driver : loginOrs) {
            StpLogic stpLogic = StpKit.getStpByDevice(driver);
            if (stpLogic.isLogin()) {
                return;
            }
        }
        for (String driver : loginOrs) {
            StpLogic stpLogic = StpKit.getStpByDevice(driver);
            try {
                stpLogic.checkLogin();
            } catch (NotLoginException e) {
                if (
                        e.getType().equals(NotLoginException.TOKEN_TIMEOUT) ||
                                e.getType().equals(NotLoginException.BE_REPLACED) ||
                                e.getType().equals(NotLoginException.KICK_OUT) ||
                                e.getType().equals(NotLoginException.TOKEN_FREEZE)
                ) {
                    throw e;
                }
            }
        }
        throw new JbkException(ErrorMsg.TOKEN_AUTH_FAIL);
    }

}

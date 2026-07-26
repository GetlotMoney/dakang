package com.jbk.serve.service.api.impl;

import cn.dev33.satoken.stp.SaLoginModel;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.service.api.IApiEmployeeService;
import com.jbk.serve.service.api.IApiLogLoginService;
import com.jbk.serve.service.api.IApiRbacRoleService;
import com.jbk.serve.service.api.IAuthService;
import com.jbk.tool.consts.ApiEnum;
import com.jbk.tool.data.api.bo.AuthBo;
import com.jbk.tool.data.api.po.ApiEmployee;
import com.jbk.tool.data.api.po.ApiLogLogin;
import com.jbk.tool.data.api.vo.ApiEmployeeLoginVo;
import com.jbk.tool.data.api.vo.ApiEmployeeVo;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.OptionalUtils;
import com.jbk.tool.utils.RSAUtils;
import com.jbk.tool.utils.auth.LogLoginUtils;
import com.jbk.tool.utils.satoken.StpKit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 *@ClassName AuthServiceImpl
 *@Author xs
 *@Date 2025/9/8 9:43
 *@Version 1.0
 */
@Service
public class AuthServiceImpl implements IAuthService {

    @Autowired
    private IApiLogLoginService logLoginService;

    @Autowired
    private IApiEmployeeService employeeService;

    @Autowired
    private IApiRbacRoleService roleService;

    @Override
    public ApiEmployeeLoginVo loginEmployee(AuthBo authBo) {
        ApiEmployee employee = null;
        try {
            employee = employeeService.getOne(Wrappers.lambdaQuery(ApiEmployee.class)
                    .eq(ApiEmployee::getLoginName, authBo.getLoginName())
            );
        } catch (Exception e) {
            throw new JbkException("查询错误，请联系管理员");
        }
        OptionalUtils.nullToElseThrow(employee, "账号或密码错误");
        if (employee.getDisabledFlag().intValue() == ApiEnum.Flag.YES.value()) {
            throw new JbkException("该账号已被禁用");
        }
        // 比较密码
        try {
            String yesPwd = RSAUtils.decrypt(employee.getLoginPwd());
            String oldPwd = RSAUtils.decrypt(authBo.getLoginPwd());
            if (!yesPwd.equals(oldPwd)) {
                throw new JbkException("账号或密码错误");
            }
            // 保存登录成功日志
            ApiLogLogin logLogin = LogLoginUtils.createLogLogin(
                    ApiEnum.LoginType.SUCCESS,
                    employee.getId(),
                    StpKit.DRIVER_MANAGE,
                    employee.getEmployeeName()
            );
            logLoginService.saveData(logLogin);
        } catch (Exception e) {
            // 保存错误日志
            ApiLogLogin logLogin = LogLoginUtils.createLogLogin(
                    ApiEnum.LoginType.PWD_FAIL,
                    employee.getId(),
                    StpKit.DRIVER_MANAGE,
                    employee.getEmployeeName()
            );
            logLoginService.saveData(logLogin);
            throw new JbkException("账号或密码错误");
        }
        StpKit.MANAGE.login(employee.getId(), SaLoginModel.create()
                .setExtra(StpKit.EXTRA_NAME, employee.getEmployeeName())
        );
        ApiEmployeeLoginVo employeeVo = BeanUtil.copyProperties(employee, ApiEmployeeLoginVo.class);
        // 查看人员菜单权限
        employeeVo.setRbacMenuList(
                roleService.listMenuByUser(employeeVo.getId())
        );
        // 响应体携带 token：PC 端存储后经请求头回传（Cookie 同域串号防护，见 CLAUDE.md）
        employeeVo.setTokenValue(StpKit.MANAGE.getTokenValue());
        return employeeVo;
    }

    @Override
    public Boolean openSafe(String loginPwd) {
        long userId = StpKit.MANAGE.getLoginIdAsLong();
        ApiEmployeeVo employeeVo;
        try {
            employeeVo = employeeService.getData(userId);
        } catch (Exception e) {
            StpKit.MANAGE.logout();
            throw e;
        }
        if (employeeVo.getDisabledFlag().intValue() == ApiEnum.Flag.YES.value()) {
            StpKit.MANAGE.logout();
            throw new JbkException("该账号已被禁用");
        }
        // 修复底座缺陷：原实现比较的是"库内密码 vs 库内密码"恒为真，二级认证形同虚设
        String yesPwd = RSAUtils.decrypt(employeeVo.getLoginPwd());
        String inputPwd = RSAUtils.decrypt(loginPwd);
        if (!yesPwd.equals(inputPwd)) {
            throw new JbkException("密码错误");
        }
        StpKit.MANAGE.openSafe(2 * 30);
        return Boolean.TRUE;
    }

    @Override
    public Boolean loginOut() {
        long userId = StpKit.MANAGE.getLoginIdAsLong();
        try {
            // 保存退出登录日志
            ApiLogLogin logLogin = LogLoginUtils.createLogLogin(
                    ApiEnum.LoginType.LOG_OUT,
                    userId,
                    StpKit.DRIVER_MANAGE,
                    (String) StpKit.MANAGE.getExtra(StpKit.EXTRA_NAME)
            );
            logLoginService.saveData(logLogin);
        } catch (Exception e) {
        }
        StpKit.MANAGE.logout();
        return Boolean.TRUE;
    }
}



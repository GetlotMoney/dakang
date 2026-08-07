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
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.OptionalUtils;
import com.jbk.tool.utils.PwdUtils;
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
        // 比较密码：传输层 RSA 解密后与库内 BCrypt 哈希比对；
        // 库内为历史 RSA 密文等非 BCrypt 格式时 verify 恒 false（fail-closed，由迁移一次性重置）。
        // try 只包解密与比对：若把写日志也圈进来，日志表故障会让密码正确的用户被判成
        // "账号或密码错误"，并留下伪造的 PWD_FAIL 审计误导事后取证
        boolean matched;
        try {
            String inputPwd = RSAUtils.decrypt(authBo.getLoginPwd());
            matched = PwdUtils.verify(inputPwd, employee.getLoginPwd());
        } catch (Exception e) {
            // 解密失败（密文损坏/非本密钥对）同样按凭据错误处理，不外泄具体原因
            matched = false;
        }
        writeLoginLog(matched ? ApiEnum.LoginType.SUCCESS : ApiEnum.LoginType.PWD_FAIL, employee);
        if (!matched) {
            throw new JbkException("账号或密码错误");
        }
        // 强改标记随 JWT 签发：改密/重置都会强制下线，重新登录即刷新，读取方无需回查库
        boolean pwdChangeRequired = employee.getPwdChangeFlag() != null
                && employee.getPwdChangeFlag().intValue() == ApiEnum.Flag.YES.value();
        StpKit.MANAGE.login(employee.getId(), SaLoginModel.create()
                .setExtra(StpKit.EXTRA_NAME, employee.getEmployeeName())
                .setExtra(StpKit.EXTRA_PWD_CHANGE, pwdChangeRequired)
        );
        ApiEmployeeLoginVo employeeVo = BeanUtil.copyProperties(employee, ApiEmployeeLoginVo.class);
        employeeVo.setPwdChangeRequired(pwdChangeRequired);
        // 查看人员菜单权限
        employeeVo.setRbacMenuList(
                roleService.listMenuByUser(employeeVo.getId())
        );
        // 响应体携带 token：PC 端存储后经请求头回传（Cookie 同域串号防护，见 CLAUDE.md）
        employeeVo.setTokenValue(StpKit.MANAGE.getTokenValue());
        return employeeVo;
    }

    /**
     * 登录审计落库：失败不得影响登录判定本身（日志表故障不应变成"密码错误"或 500）。
     */
    private void writeLoginLog(ApiEnum.LoginType type, ApiEmployee employee) {
        try {
            logLoginService.saveData(LogLoginUtils.createLogLogin(
                    type,
                    employee.getId(),
                    StpKit.DRIVER_MANAGE,
                    employee.getEmployeeName()
            ));
        } catch (Exception ignored) {
        }
    }

    @Override
    public Boolean openSafe(String loginPwd) {
        long userId = StpKit.MANAGE.getLoginIdAsLong();
        // 直查 Po：口令哈希不进任何 Vo（ApiEmployeeVo 已剥离 loginPwd，防止随 page/detail 出网）
        ApiEmployee employee = employeeService.getById(userId);
        if (employee == null) {
            StpKit.MANAGE.logout();
            throw new JbkException("员工信息不存在");
        }
        if (employee.getDisabledFlag().intValue() == ApiEnum.Flag.YES.value()) {
            StpKit.MANAGE.logout();
            throw new JbkException("该账号已被禁用");
        }
        // 修复底座缺陷：原实现比较的是"库内密码 vs 库内密码"恒为真，二级认证形同虚设
        String inputPwd = RSAUtils.decrypt(loginPwd);
        if (!PwdUtils.verify(inputPwd, employee.getLoginPwd())) {
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



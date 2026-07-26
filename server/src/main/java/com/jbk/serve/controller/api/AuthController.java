package com.jbk.serve.controller.api;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.api.IAuthService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.SwaggerApiInclude;
import com.jbk.tool.data.api.bo.AuthBo;
import com.jbk.tool.data.api.vo.ApiEmployeeLoginVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.OptionalUtils;
import com.jbk.tool.utils.satoken.StpKit;
import com.jbk.tool.validator.group.InsertGroup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotEmpty;

/**
 *@ClassName AuthController
 *@Author xs
 *@Date 2025/9/8 9:42
 *@Version 1.0
 */
@Tag(name = "API-认证")
@Validated
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private IAuthService authService;

    @Operation(summary = "登录——员工")
    @PostMapping("/loginEmployee")
    public R<ApiEmployeeLoginVo> loginEmployee(
            @RequestBody
            @Validated(InsertGroup.class)
            AuthBo authBo) {
        return R.ok(authService.loginEmployee(authBo));
    }

    @Operation(summary = "退出——员工")
    @PostMapping("/loginOut")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> loginOut(){
        return R.ok(authService.loginOut());
    }

    @LogOperation
    @Operation(summary = "二级认证-后台")
    @PostMapping("/openSafe")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> openSafe(
            @SwaggerApiInclude("loginPwd")
            @RequestBody
            AuthBo authBo) {
        OptionalUtils.emptyToElseThrow(authBo.getLoginPwd(), "密码不为空");
        return R.ok(authService.openSafe(authBo.getLoginPwd()));
    }
}



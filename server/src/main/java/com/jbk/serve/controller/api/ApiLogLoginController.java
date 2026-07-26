package com.jbk.serve.controller.api;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.api.IApiLogLoginService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.SwaggerApiInclude;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.bo.ApiLogLoginBo;
import com.jbk.tool.data.api.po.ApiLogLogin;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.PageGroup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author xs
 * @since 2025-09-08
 */
@Tag(name = "API-登录日志")
@Validated
@RestController
@RequestMapping("/api/logLogin")
public class ApiLogLoginController {

    @Autowired
    private IApiLogLoginService logLoginService;

    @Operation(summary = "分页查询")
    @PostMapping("/pageData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:logLogin:query", type = StpKit.DRIVER_MANAGE)})
    public R<PageDataVo<ApiLogLogin>> pageData(
            @SwaggerApiInclude({"current","size","logUserName","logExecuteTimeBegin","logExecuteTimeEnd","logType","logIp"})
            @RequestBody
            @Validated(PageGroup.class)
            ApiLogLoginBo logLoginBo) {
        return R.ok(logLoginService.pageData(logLoginBo));
    }

    @Operation(summary = "获取")
    @PostMapping("/getData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:logLogin:query", type = StpKit.DRIVER_MANAGE)})
    public R<ApiLogLogin> getData(
            @SwaggerApiInclude({"id"})
            @RequestBody
            @Validated(IdGroup.class)
            ApiLogLoginBo logLoginBo) {
        return R.ok(logLoginService.getData(logLoginBo.getId()));
    }
}



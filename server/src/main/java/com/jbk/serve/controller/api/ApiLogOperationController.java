package com.jbk.serve.controller.api;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.api.IApiLogOperationService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.SwaggerApiInclude;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.bo.ApiLogOperationBo;
import com.jbk.tool.data.api.po.ApiLogOperation;
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
 * @since 2025-09-05
 */
@Tag(name = "API-操作日志")
@Validated
@RestController
@RequestMapping("/api/logOperation")
public class ApiLogOperationController {

    @Autowired
    private IApiLogOperationService logOperationService;

    @Operation(summary = "分页查询")
    @PostMapping("/pageData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:logOperation:query", type = StpKit.DRIVER_MANAGE)})
    public R<PageDataVo<ApiLogOperation>> pageData(
            @SwaggerApiInclude({"current","size","logUserName", "logModule", "logContent", "logExecuteTimeBegin", "logExecuteTimeEnd", "logSuccessFlag", "logIp"})
            @RequestBody
            @Validated(PageGroup.class)
            ApiLogOperationBo logOperationBo) {
        return R.ok(logOperationService.pageData(logOperationBo));
    }

    @Operation(summary = "获取")
    @PostMapping("/getData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:logOperation:query", type = StpKit.DRIVER_MANAGE)})
    public R<ApiLogOperation> getData(
            @SwaggerApiInclude({"id"})
            @RequestBody
            @Validated(IdGroup.class)
            ApiLogOperationBo logOperationBo) {
        return R.ok(logOperationService.getData(logOperationBo.getId()));
    }
}



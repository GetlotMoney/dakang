package com.jbk.serve.controller.api;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.api.IApiEmployeeService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.annotation.SwaggerApiExclude;
import com.jbk.tool.annotation.SwaggerApiInclude;
import com.jbk.tool.consts.ApiEnum;
import com.jbk.tool.data.api.bo.ApiEmployeeBo;
import com.jbk.tool.data.api.vo.ApiEmployeeVo;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.OptionalUtils;
import com.jbk.tool.utils.satoken.StpKit;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import com.jbk.tool.validator.group.PageGroup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
@Tag(name = "API-员工")
@Validated
@RestController
@RequestMapping("/api/employee")
public class ApiEmployeeController {

    @Autowired
    private IApiEmployeeService employeeService;

    @LogOperation
    @Operation(summary = "获取")
    @PostMapping("/getData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "api:employee:query", type = StpKit.DRIVER_MANAGE)})
    public R<ApiEmployeeVo> getData(
            @SwaggerApiInclude("id")
            @RequestBody
            @Validated(IdGroup.class)
            ApiEmployeeVo employeeVo) {
        return R.ok(employeeService.getData(employeeVo.getId()));
    }


    @LogOperation
    @RepeatSubmit
    @Operation(summary = "保存")
    @PostMapping("/saveData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "api:employee:add", type = StpKit.DRIVER_MANAGE)})
    public R<Long> saveData(
            @SwaggerApiExclude({"id", "loginPwd", "disabledFlag"})
            @RequestBody
            @Validated(InsertGroup.class)
            ApiEmployeeBo employeeBo) {
        employeeBo.setDisabledFlag(ApiEnum.Flag.NO.value());
        return R.ok(employeeService.saveData(employeeBo));
    }

    // 分页
    @Operation(summary = "分页查询")
    @PostMapping("/getPage")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "api:employee:query", type = StpKit.DRIVER_MANAGE)})
    public R<PageDataVo<ApiEmployeeVo>> getPage(
            @SwaggerApiInclude({"current", "size", "employeeName","disabledFlag","deptId","positionId","tagId"})
            @RequestBody
            @Validated(PageGroup.class)
            ApiEmployeeBo employeeBo) {
        return R.ok(employeeService.getPage(employeeBo));
    }

    // 修改
    @LogOperation
    @RepeatSubmit
    @Operation(summary = "修改")
    @PostMapping("/updateData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:employee:update", type = StpKit.DRIVER_MANAGE)})
    public R<Long> updateData(
            @SwaggerApiExclude({"loginPwd","newLoginPwd"})
            @RequestBody
            @Validated({IdGroup.class, InsertGroup.class})
            ApiEmployeeBo employeeBo) {
        employeeBo.setLoginPwd(null);
        return R.ok(employeeService.updateData(employeeBo));
    }

    @LogOperation
    @Operation(summary = "删除")
    @PostMapping("/deleteData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:employee:delete", type = StpKit.DRIVER_MANAGE)})
    public R<Boolean> deleteData(
            @SwaggerApiInclude("id")
            @RequestBody
            @Validated(IdGroup.class)
            ApiEmployeeBo employeeBo) {
        return R.ok(employeeService.deleteData(employeeBo.getId()));
    }

    @LogOperation
    @Operation(summary = "重置密码")
    @PostMapping("/resetPassword")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:employee:resetPwd", type = StpKit.DRIVER_MANAGE)})
    public R<Boolean> resetPassword(
            @SwaggerApiInclude({"id"})
            @RequestBody
            @Validated(IdGroup.class)
            ApiEmployeeBo employeeBo) {
        return R.ok(employeeService.resetPassword(employeeBo.getId()));
    }

    @LogOperation
    @Operation(summary = "修改密码")
    @PostMapping("/updatePassword")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<Boolean> updatePassword(
            @SwaggerApiInclude({"id", "loginPwd", "newLoginPwd"})
            @RequestBody
            @Validated(IdGroup.class)
            ApiEmployeeBo employeeBo) {
        OptionalUtils.emptyToElseThrow(employeeBo.getLoginPwd(), "原密码不为空");
        OptionalUtils.emptyToElseThrow(employeeBo.getNewLoginPwd(), "原密码不为空");
        return R.ok(employeeService.updatePassword(employeeBo));
    }


}



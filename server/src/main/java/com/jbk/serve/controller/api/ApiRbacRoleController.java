package com.jbk.serve.controller.api;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.api.IApiRbacRoleService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.annotation.SwaggerApiExclude;
import com.jbk.tool.annotation.SwaggerApiInclude;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.bo.ApiEmployeeBo;
import com.jbk.tool.data.api.bo.ApiRbacRoleBo;
import com.jbk.tool.data.api.bo.ApiRbacRoleEmployeeBo;
import com.jbk.tool.data.api.bo.ApiRbacRoleMenuBo;
import com.jbk.tool.data.api.vo.ApiEmployeeVo;
import com.jbk.tool.data.api.vo.ApiRbacMenuVo;
import com.jbk.tool.data.api.vo.ApiRbacRoleVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import com.jbk.tool.validator.group.PageGroup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
@Tag(name = "API-角色")
@Validated
@RestController
@RequestMapping("/api/rbacRole")
public class ApiRbacRoleController {

    @Autowired
    private IApiRbacRoleService rbacRoleService;

    @LogOperation
    @RepeatSubmit
    @Operation(summary = "添加角色")
    @PostMapping("/saveData")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "api:role:add", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Long> saveData(
            @SwaggerApiExclude("id")
            @RequestBody
            @Validated(InsertGroup.class)
            ApiRbacRoleBo rbacRoleBo) {
        return R.ok(rbacRoleService.saveData(rbacRoleBo));
    }

    @LogOperation
    @RepeatSubmit
    @Operation(summary = "修改角色基本信息")
    @PostMapping("/updateData")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "api:role:update", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> updateData(
            @SwaggerApiExclude("id")
            @RequestBody
            @Validated({InsertGroup.class, IdGroup.class})
            ApiRbacRoleBo rbacRoleBo) {
        return R.ok(rbacRoleService.updateData(rbacRoleBo));
    }

    @LogOperation
    @Operation(summary = "删除角色")
    @PostMapping("/delData")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "api:role:delete", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> delData(
            @SwaggerApiInclude("id")
            @RequestBody
            @Validated(IdGroup.class)
            ApiRbacRoleBo rbacRoleBo) {
        return R.ok(rbacRoleService.delData(rbacRoleBo.getId()));
    }

    @Operation(summary = "查看所有角色")
    @PostMapping("/listData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:role:query", type = StpKit.DRIVER_MANAGE)})
    public R<List<ApiRbacRoleVo>> listData() {
        return R.ok(rbacRoleService.listData());
    }

    @Operation(summary = "查看角色所有权限")
    @PostMapping("/listMenuId")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:role:query", type = StpKit.DRIVER_MANAGE)})
    public R<List<Long>> listMenuIdByRole(
            @NotNull(message = "角色信息不为空")
            @RequestParam("roleId") Long roleId) {

        return R.ok(rbacRoleService.listMenuId(roleId));
    }

    @LogOperation
    @RepeatSubmit
    @Operation(summary = "更新角色所有权限")
    @PostMapping("/updateMenu")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:role:update", type = StpKit.DRIVER_MANAGE)})
    public R<Boolean> updateMenu(
            @SwaggerApiExclude("id")
            @RequestBody
            @Validated(InsertGroup.class)
            ApiRbacRoleMenuBo rbacRoleMenuBo) {

        return R.ok(rbacRoleService.updateMenu(rbacRoleMenuBo));
    }

    @Operation(summary = "查看角色所有人员")
    @PostMapping("/pageEmoloyee")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:role:query", type = StpKit.DRIVER_MANAGE)})
    public R<PageDataVo<ApiEmployeeVo>> pageEmoloyee(
            @SwaggerApiInclude({"id", "current", "size"})
            @RequestBody
            @Validated({IdGroup.class, PageGroup.class})
            ApiRbacRoleBo rbacRoleBo) {
        return R.ok(rbacRoleService.pageEmoloyee(rbacRoleBo));
    }


    @Operation(summary = "查看人员所有角色")
    @PostMapping("/listRoleByEmployee")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:role:query", type = StpKit.DRIVER_MANAGE)})
    public R<List<ApiRbacRoleVo>> listRoleByEmployee(
            @SwaggerApiInclude({"employeeId"})
            @RequestBody
            @Validated({InsertGroup.class})
            ApiRbacRoleEmployeeBo rbacRoleEmployeeBo) {
        return R.ok(rbacRoleService.listRoleByEmployee(rbacRoleEmployeeBo.getEmployeeId()));
    }

    @LogOperation
    @RepeatSubmit
    @Operation(summary = "添加人员角色-多个")
    @PostMapping("/addEmployeeToRoleList")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:role:update", type = StpKit.DRIVER_MANAGE)})
    public R<Boolean> addEmployeeToRoleList(
            @SwaggerApiExclude({"id"})
            @RequestBody
            @Validated({InsertGroup.class})
            ApiRbacRoleEmployeeBo rbacRoleEmployeeBo) {
        return R.ok(rbacRoleService.addEmployeeToRoleList(rbacRoleEmployeeBo));
    }

    @Operation(summary = "获取用户权限信息")
    @PostMapping("/listMenuByEmployee")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<List<ApiRbacMenuVo>> listMenuByEmployee(
            @SwaggerApiInclude({"id"})
            @RequestBody
            @Validated({IdGroup.class})
            ApiEmployeeBo employeeBo) {
        return R.ok(rbacRoleService.listMenuByUser(employeeBo.getId()));
    }
}


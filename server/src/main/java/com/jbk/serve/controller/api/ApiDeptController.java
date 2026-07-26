package com.jbk.serve.controller.api;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.api.IApiDeptService;
import com.jbk.tool.annotation.*;
import com.jbk.tool.data.api.bo.ApiDeptBo;
import com.jbk.tool.data.api.vo.ApiDeptTreeVo;
import com.jbk.tool.data.api.vo.ApiDeptVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author xs
 * @since 2025-09-05
 */
@Tag(name = "API-部门")
@Validated
@RestController
@RequestMapping("/api/dept")
public class ApiDeptController {

    @Autowired
    private IApiDeptService deptService;

    @Operation(summary = "树形结构获取")
    @PostMapping("/treeData")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "api:dept:query", type = StpKit.DRIVER_MANAGE)}
    )
    public R<List<ApiDeptTreeVo>> treeData() {
        return R.ok(deptService.treeData());
    }


    @Operation(summary = "获取")
    @PostMapping("/getData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "api:dept:query", type = StpKit.DRIVER_MANAGE)})
    public R<ApiDeptVo> getData(
            @SwaggerApiInclude("id")
            @RequestBody
            @Validated(IdGroup.class)
            ApiDeptBo deptBo) {
        return R.ok(deptService.getData(deptBo.getId()));
    }

    @LogOperation
    @RepeatSubmit
    @Operation(summary = "添加")
    @PostMapping("/saveData")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "api:dept:add", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Long> saveData(
            @SwaggerApiExclude("id")
            @RequestBody
            @Validated(InsertGroup.class)
            ApiDeptBo deptBo) {
        return R.ok(deptService.saveData(deptBo));
    }


    @LogOperation
    @RepeatSubmit
    @Operation(summary = "删除部门")
    @PostMapping("/deleteData")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "api:dept:delete", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> deleteData(
            @SwaggerApiInclude("id")
            @RequestBody
            @Validated(IdGroup.class)
            ApiDeptBo deptBo) {
        return R.ok(deptService.deleteData(deptBo.getId()));
    }

    @LogOperation
    @RepeatSubmit
    @Operation(summary = "部门修改")
    @PostMapping("updateData")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "api:dept:update", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> updateData(
            @SwaggerApiInclude({"id"})
            @RequestBody
            @Validated({IdGroup.class, InsertGroup.class})
            ApiDeptBo deptBo) {
        return R.ok(deptService.updateData(deptBo));
    }
}

package com.jbk.serve.controller.api;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.api.IApiEmployeeTagService;
import com.jbk.serve.service.api.IApiTagService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.annotation.SwaggerApiExclude;
import com.jbk.tool.annotation.SwaggerApiInclude;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.bo.ApiEmployeeTagBo;
import com.jbk.tool.data.api.bo.ApiTagBo;
import com.jbk.tool.data.api.vo.ApiEmployeeTagVo;
import com.jbk.tool.data.api.vo.ApiEmployeeVo;
import com.jbk.tool.data.api.vo.ApiTagVo;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author xs
 * @since 2025-09-15
 */
@Tag(name = "API-标签管理")
@Validated
@RestController
@RequestMapping("/api/tag")
public class ApiTagController {
    @Autowired
    private IApiTagService tagService;
    @Autowired
    private IApiEmployeeTagService employeeTagService;

    @LogOperation
    @RepeatSubmit
    @Operation(summary = "保存员工标签关联")
    @PostMapping("employeeTag/saveData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:tag:update", type = StpKit.DRIVER_MANAGE)})
    public R<Long> saveEmployeeTagData(
            @SwaggerApiExclude({"id"})
            @RequestBody
            @Validated(InsertGroup.class)
            ApiEmployeeTagBo employeeTagBo) {
        return R.ok(employeeTagService.saveData(employeeTagBo));
    }

    @LogOperation
    @Operation(summary = "根据标签id和员工id去删除关联信息")
    @PostMapping("employeeTag/deleteData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:tag:update", type = StpKit.DRIVER_MANAGE)})
    public R<Boolean> deleteEmployeeData(
            @SwaggerApiInclude({"tagId","employeeId"})
            @RequestBody
            @Validated(IdGroup.class)
            ApiEmployeeTagVo employeeTagVo) {
        OptionalUtils.nullToElseThrow(employeeTagVo.getTagId(), "标签ID不能为空");
        OptionalUtils.nullToElseThrow(employeeTagVo.getEmployeeId(), "员工ID不能为空");
        return R.ok(employeeTagService.deleteEmployeeTagData(employeeTagVo));
    }


    @Operation(summary = "查看标签下所以员工信息")
    @PostMapping("employeeTag/pageEmployee")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:tag:query", type = StpKit.DRIVER_MANAGE)})
    public R<PageDataVo<ApiEmployeeVo>> pageEmployee(
            @SwaggerApiExclude({"tagId"})
            @Validated(PageGroup.class)
            @RequestBody
            ApiEmployeeTagBo employeeTagBo) {
        OptionalUtils.nullToElseThrow(employeeTagBo.getTagId(), "标签信息不为空");
        return R.ok(employeeTagService.pageEmployee(employeeTagBo));
    }



    @Operation(summary = "获取所有标签详情")
    @PostMapping("/listData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:tag:query", type = StpKit.DRIVER_MANAGE)})
    public R<List<ApiTagVo>> listData(
            @SwaggerApiInclude("tagName")
            @RequestBody
            ApiTagBo tagBo) {
        return R.ok(tagService.listData(tagBo.getTagName()));
    }

    @Operation(summary = "获取标签详情")
    @PostMapping("/getData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:tag:query", type = StpKit.DRIVER_MANAGE)})
    public R<ApiTagVo> getData(
            @SwaggerApiInclude("id")
            @RequestBody
            @Validated(IdGroup.class)
            ApiTagBo tagBo) {
        return R.ok(tagService.getData(tagBo.getId()));
    }

    @LogOperation
    @RepeatSubmit
    @Operation(summary = "修改")
    @PostMapping("/updateData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:tag:update", type = StpKit.DRIVER_MANAGE)})
    public R<Boolean> updateData(
            @SwaggerApiExclude("id")
            @RequestBody
            @Validated({InsertGroup.class, IdGroup.class})
            ApiTagBo tagBo) {
        return R.ok(tagService.updateData(tagBo));
    }


    @LogOperation
    @RepeatSubmit
    @Operation(summary = "添加")
    @PostMapping("/saveData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:tag:add", type = StpKit.DRIVER_MANAGE)})
    public R<Long> saveData(
            @SwaggerApiExclude("id")
            @RequestBody
            @Validated(InsertGroup.class)
            ApiTagBo tagBo) {
        return R.ok(tagService.saveData(tagBo));
    }

    @LogOperation
    @Operation(summary = "删除")
    @PostMapping("/deleteData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:tag:delete", type = StpKit.DRIVER_MANAGE)})
    public R<Boolean> deleteData(
            @SwaggerApiInclude("id")
            @RequestBody
            @Validated(IdGroup.class)
            ApiTagBo tagBo) {
        return R.ok(tagService.deleteData(tagBo.getId()));
    }


}


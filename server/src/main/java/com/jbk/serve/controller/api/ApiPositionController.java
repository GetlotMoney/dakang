package com.jbk.serve.controller.api;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.api.IApiPositionService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.annotation.SwaggerApiExclude;
import com.jbk.tool.annotation.SwaggerApiInclude;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.api.bo.ApiEmployeeBo;
import com.jbk.tool.data.api.bo.ApiPositionBo;
import com.jbk.tool.data.api.vo.ApiEmployeeVo;
import com.jbk.tool.data.api.vo.ApiPositionVo;
import com.jbk.tool.domain.R;
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
 * @since 2025-09-05
 */
@Tag(name = "API-职务")
@Validated
@RestController
@RequestMapping("/api/position")
public class ApiPositionController {

    @Autowired
    private IApiPositionService positionService;

    @Operation(summary = "分页查询")
    @PostMapping("/pageData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:position:query", type = StpKit.DRIVER_MANAGE)})
    public R<PageDataVo<ApiPositionVo>> pageData(
            @SwaggerApiInclude({"positionName","current","size"})
            @RequestBody
            @Validated(PageGroup.class)
            ApiPositionBo positionBo) {
        return R.ok(positionService.pageData(positionBo));
    }

    @Operation(summary = "获取")
    @PostMapping("/getData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:position:query", type = StpKit.DRIVER_MANAGE)})
    public R<ApiPositionVo> getData(
            @SwaggerApiInclude("id")
            @RequestBody
            @Validated(IdGroup.class)
            ApiPositionBo positionBo) {
        return R.ok(positionService.getData(positionBo.getId()));
    }

    @LogOperation
    @RepeatSubmit
    @Operation(summary = "修改")
    @PostMapping("/updateData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:position:update", type = StpKit.DRIVER_MANAGE)})
    public R<Boolean> updateData(
            @SwaggerApiExclude("id")
            @RequestBody
            @Validated({InsertGroup.class, IdGroup.class})
            ApiPositionBo positionBo) {
        return R.ok(positionService.updateData(positionBo));
    }

    @LogOperation
    @RepeatSubmit
    @Operation(summary = "添加")
    @PostMapping("/saveData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:position:add", type = StpKit.DRIVER_MANAGE)})
    public R<Long> saveData(
            @SwaggerApiExclude("id")
            @RequestBody
            @Validated(InsertGroup.class)
            ApiPositionBo positionBo) {
        return R.ok(positionService.saveData(positionBo));
    }

    @LogOperation
    @Operation(summary = "删除")
    @PostMapping("/deleteData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:position:delete", type = StpKit.DRIVER_MANAGE)})
    public R<Boolean> deleteData(
            @SwaggerApiInclude("id")
            @RequestBody
            @Validated(IdGroup.class)
            ApiPositionBo positionBo) {
        return R.ok(positionService.deleteData(positionBo.getId()));
    }

    @LogOperation
    @RepeatSubmit
    @Operation(summary = "保存员工职务信息")
    @PostMapping("/saveEmployeePosition")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:position:update", type = StpKit.DRIVER_MANAGE)})
    public R<Boolean> saveEmployeePosition(
            @SwaggerApiInclude({"id","positionId"})
            @RequestBody
            ApiEmployeeBo apiEmployeeBo) {
        return R.ok(positionService.saveEmployeePosition(apiEmployeeBo));
    }

    @Operation(summary = "list该职务下员工信息")
    @PostMapping("/listEmployeesByPosition")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:position:query", type = StpKit.DRIVER_MANAGE)})
    public R<List<ApiEmployeeVo>> listEmployeesByPosition(
            @SwaggerApiInclude({"Id"})
            @RequestBody
            @Validated(IdGroup.class)
            ApiPositionBo apiPositionBo)
    {
        return R.ok(positionService.listEmployeesByPosition(apiPositionBo.getId()));
    }

    @LogOperation
    @Operation(summary = "删除员工职务信息")
    @PostMapping("/delEmployeePosition")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:position:update", type = StpKit.DRIVER_MANAGE)})
    public R<Boolean> delEmployeePosition(
            @SwaggerApiInclude("id")
            @RequestBody
            @Validated(IdGroup.class)
            ApiEmployeeBo apiEmployeeBo) {
        return R.ok(positionService.delEmployeePosition(apiEmployeeBo.getId()));
    }

}



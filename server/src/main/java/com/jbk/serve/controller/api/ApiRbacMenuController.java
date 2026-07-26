package com.jbk.serve.controller.api;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.google.common.collect.Lists;
import com.jbk.serve.service.api.IApiRbacMenuService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.annotation.SwaggerApiExclude;
import com.jbk.tool.annotation.SwaggerApiInclude;
import com.jbk.tool.consts.ApiEnum;
import com.jbk.tool.data.api.bo.*;
import com.jbk.tool.data.api.vo.ApiRbacMenuTreeVo;
import com.jbk.tool.data.api.vo.ApiRbacMenuVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.OptionalUtils;
import com.jbk.tool.utils.satoken.StpKit;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotEmpty;
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
@Tag(name = "API-菜单")
@Validated
@RestController
@RequestMapping("/api/rbacMenu")
public class ApiRbacMenuController {

    @Autowired
    private IApiRbacMenuService rbacMenuService;


    @LogOperation
    @RepeatSubmit
    @Operation(summary = "添加")
    @PostMapping("/saveData")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "api:menu:add", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Long> saveData(
            @SwaggerApiExclude("id")
            @RequestBody
            @Validated(InsertGroup.class)
            ApiRbacMenuBo rbacMenuBo) {
        // 校验信息
        ApiEnum.MenuTypeEnum menuTypeEnum = ApiEnum.MenuTypeEnum.getType(rbacMenuBo.getMenuType());
        switch (menuTypeEnum) {
            case CATALOG:
                OptionalUtils.nullToElseThrow(
                        rbacMenuBo.getMenuVisibleFlag(), "显示状态不为空"
                );
                break;
            case MENU:
                OptionalUtils.nullToElseThrow(
                        rbacMenuBo.getMenuFrameFlag(), "是否为外链不为空"
                );
                if (rbacMenuBo.getMenuFrameFlag().intValue() == ApiEnum.Flag.YES.value()) {
                    OptionalUtils.emptyToElseThrow(
                            rbacMenuBo.getMenuFrameUrl(), "外链地址不为空"
                    );
                }
                OptionalUtils.nullToElseThrow(
                        rbacMenuBo.getMenuVisibleFlag(), "显示状态不为空"
                );
                break;
            case POINTS:
                break;
        }
        return R.ok(rbacMenuService.saveData(rbacMenuBo));
    }


    @Operation(summary = "获取")
    @PostMapping("/getData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:menu:query", type = StpKit.DRIVER_MANAGE)})
    public R<ApiRbacMenuVo> getData(
            @SwaggerApiInclude("id")
            @RequestBody
            @Validated(IdGroup.class)
            ApiRbacMenuBo rbacMenuBo) {
        return R.ok(rbacMenuService.getData(rbacMenuBo.getId()));
    }

    @Operation(summary = "查询菜单树")
    @PostMapping("/treeData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:menu:query", type = StpKit.DRIVER_MANAGE)})
    public R<List<ApiRbacMenuTreeVo>> treeData(
            @NotNull(message = "是否查询功能点不为空")
            @RequestParam("pointsFlag") Integer pointsFlag) {
        List<Integer> menuTypeList = Lists.newArrayList();
        if (pointsFlag.intValue() == ApiEnum.Flag.NO.value()) {
            menuTypeList = Lists.newArrayList(ApiEnum.MenuTypeEnum.CATALOG.getValue(), ApiEnum.MenuTypeEnum.MENU.getValue());
        }
        return R.ok(rbacMenuService.treeData(menuTypeList));
    }

    @LogOperation
    @Operation(summary = "删除菜单")
    @PostMapping("/deleteData")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)}, permission = {@SaCheckPermission(value = "api:menu:delete", type = StpKit.DRIVER_MANAGE)})
    public R<Boolean> deleteData(
            @SwaggerApiInclude("id")
            @RequestBody
            @Validated(IdGroup.class)
            ApiPositionBo positionBo
    ) {
        return R.ok(rbacMenuService.deleteData(positionBo.getId()));
    }

    @LogOperation
    @RepeatSubmit
    @Operation(summary = "修改")
    @PostMapping("/updateData")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "api:menu:update", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> updateData(
            @RequestBody
            @Validated({IdGroup.class, InsertGroup.class})
            ApiRbacMenuBo rbacMenuBo) {
        // 校验信息
        ApiEnum.MenuTypeEnum menuTypeEnum = ApiEnum.MenuTypeEnum.getType(rbacMenuBo.getMenuType());
        switch (menuTypeEnum) {
            case CATALOG:
                OptionalUtils.nullToElseThrow(
                        rbacMenuBo.getMenuVisibleFlag(), "显示状态不为空"
                );
                break;
            case MENU:
                OptionalUtils.nullToElseThrow(
                        rbacMenuBo.getMenuFrameFlag(), "是否为外链不为空"
                );
                if (rbacMenuBo.getMenuFrameFlag().intValue() == ApiEnum.Flag.YES.value()) {
                    OptionalUtils.emptyToElseThrow(
                            rbacMenuBo.getMenuFrameUrl(), "外链地址不为空"
                    );
                }
                OptionalUtils.nullToElseThrow(
                        rbacMenuBo.getMenuVisibleFlag(), "显示状态不为空"
                );
                break;
            case POINTS:
                break;
        }
        return R.ok(rbacMenuService.updateData(rbacMenuBo));
    }



}



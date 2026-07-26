package com.jbk.serve.controller.user;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.user.IWsCourierService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.user.bo.WsCourierBo;
import com.jbk.tool.data.user.vo.WsCourierVo;
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

/**
 * 配送员审核（后台准入管理，REQ-079）
 *
 * @author dakang
 * @since 2026-07-12
 */
@Tag(name = "WS-配送员审核")
@Validated
@RestController
@RequestMapping("/user/courier")
public class WsCourierController {

    @Autowired
    private IWsCourierService courierService;

    @PostMapping("/page")
    @Operation(summary = "分页查询（姓名/电话/状态筛选，待审核排前）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<PageDataVo<WsCourierVo>> page(
            @RequestBody @Validated(PageGroup.class) WsCourierBo bo) {
        return R.ok(courierService.pageData(bo));
    }

    @PostMapping("/detail")
    @Operation(summary = "详情")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<WsCourierVo> detail(
            @RequestBody @Validated(IdGroup.class) WsCourierBo bo) {
        return R.ok(courierService.getData(bo.getId()));
    }

    @PostMapping("/getByUser")
    @Operation(summary = "按用户查询配送员准入记录（用户详情抽屉聚合，无则返回空）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<WsCourierVo> getByUser(@RequestBody WsCourierBo bo) {
        return R.ok(courierService.getByUser(bo.getUserId()));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/add")
    @Operation(summary = "人工创建配送员（创建即待审核）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) }, permission = {
            @SaCheckPermission(value = "user:courier:add", type = StpKit.DRIVER_MANAGE) })
    public R<Long> add(
            @RequestBody @Validated(InsertGroup.class) WsCourierBo bo) {
        return R.ok(courierService.saveData(bo));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/audit")
    @Operation(summary = "审核/启停（通过/驳回/停用/恢复，驳回停用必填备注）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) }, permission = {
            @SaCheckPermission(value = "user:courier:audit", type = StpKit.DRIVER_MANAGE) })
    public R<Boolean> audit(
            @RequestBody @Validated(IdGroup.class) WsCourierBo bo) {
        return R.ok(courierService.audit(bo));
    }
}

package com.jbk.serve.controller.product;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.product.IWsPackageService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.product.bo.WsPackageBo;
import com.jbk.tool.data.product.vo.WsPackageVo;
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
 * 水种套餐——套餐管理（后台）
 * <p>REQ-061：套餐价格/水量/有效期/范围配置；历史订单按下单时 PACKAGE_SNAP 快照结算，
 * 修改与上下架均不影响已成交订单。写操作经 {@link LogOperation} 落操作审计
 * （与本模块水种管理同一手法），不提供删除。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
@Tag(name = "WS-水种套餐-套餐")
@Validated
@RestController
@RequestMapping("/product/package")
public class WsPackageController {

    @Autowired
    private IWsPackageService packageService;

    @PostMapping("/page")
    @Operation(summary = "分页查询")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<PageDataVo<WsPackageVo>> page(
            @RequestBody @Validated(PageGroup.class) WsPackageBo bo) {
        return R.ok(packageService.pageData(bo));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/add")
    @Operation(summary = "新增")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "product:package:add", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Long> add(
            @RequestBody @Validated(InsertGroup.class) WsPackageBo bo) {
        return R.ok(packageService.saveData(bo));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/update")
    @Operation(summary = "修改（不改状态；上下架走 /shelf）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "product:package:update", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> update(
            @RequestBody @Validated({IdGroup.class, InsertGroup.class}) WsPackageBo bo) {
        return R.ok(packageService.updateData(bo));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/shelf")
    @Operation(summary = "上下架（CAS 状态前置校验）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "product:package:shelf", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> shelf(
            @RequestBody @Validated(IdGroup.class) WsPackageBo bo) {
        return R.ok(packageService.shelfData(bo));
    }
}

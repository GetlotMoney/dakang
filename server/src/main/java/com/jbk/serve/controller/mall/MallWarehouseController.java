package com.jbk.serve.controller.mall;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.mall.IMallWarehouseService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallIdBo;
import com.jbk.tool.data.mall.bo.MallQueryBo;
import com.jbk.tool.data.mall.bo.MallStatusChangeBo;
import com.jbk.tool.data.mall.bo.MallWarehouseBo;
import com.jbk.tool.data.mall.vo.MallWarehouseVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商城前置仓管理（E2E-09 S1）：档案、行政区、履约范围、启停。
 * 联系电话出参恒脱敏；范围 JSON 服务端唯一构造。
 *
 * @author dakang
 * @since 2026-08-08
 */
@RestController
@RequestMapping("/mall/warehouse")
@Tag(name = "商城前置仓管理")
@RequiredArgsConstructor
public class MallWarehouseController {

    private final IMallWarehouseService warehouseService;

    @PostMapping("/page")
    @Operation(summary = "前置仓分页（电话脱敏）")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<PageDataVo<MallWarehouseVo>> page(@RequestBody MallQueryBo bo) {
        return R.ok(warehouseService.page(bo));
    }

    @PostMapping("/detail")
    @Operation(summary = "前置仓详情（电话脱敏）")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<MallWarehouseVo> detail(@Validated @RequestBody MallIdBo bo) {
        return R.ok(warehouseService.detail(bo));
    }

    @PostMapping("/list")
    @Operation(summary = "全部前置仓（下拉）")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<List<MallWarehouseVo>> list() {
        return R.ok(warehouseService.listAll());
    }

    @PostMapping("/save")
    @Operation(summary = "新增前置仓")
    @LogOperation
    @RepeatSubmit
    @MySaCheckOr(
        login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
        permission = {@SaCheckPermission(value = "mall:warehouse:edit", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Long> save(@Validated @RequestBody MallWarehouseBo bo) {
        return R.ok(warehouseService.save(bo));
    }

    @PostMapping("/update")
    @Operation(summary = "修改前置仓（编号不可改）")
    @LogOperation
    @RepeatSubmit
    @MySaCheckOr(
        login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
        permission = {@SaCheckPermission(value = "mall:warehouse:edit", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> update(@Validated @RequestBody MallWarehouseBo bo) {
        return R.ok(warehouseService.update(bo));
    }

    @PostMapping("/change-status")
    @Operation(summary = "前置仓启停（版本 CAS；停用不参与可售聚合）")
    @LogOperation
    @RepeatSubmit
    @MySaCheckOr(
        login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
        permission = {@SaCheckPermission(value = "mall:warehouse:edit", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> changeStatus(@Validated @RequestBody MallStatusChangeBo bo) {
        return R.ok(warehouseService.changeStatus(bo));
    }
}

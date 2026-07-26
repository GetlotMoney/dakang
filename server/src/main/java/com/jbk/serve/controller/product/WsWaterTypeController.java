package com.jbk.serve.controller.product;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.product.IWsWaterTypeService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.product.bo.WsWaterTypeBo;
import com.jbk.tool.data.product.vo.WsWaterTypeVo;
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
 * 水种套餐——水种字典（后台）
 * <p>REQ-073：水种由字典统一维护（启停/排序/默认），出水口与套餐只引用不定义。</p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Tag(name = "WS-水种套餐-水种")
@Validated
@RestController
@RequestMapping("/product/water")
public class WsWaterTypeController {

    @Autowired
    private IWsWaterTypeService waterTypeService;

    @PostMapping("/page")
    @Operation(summary = "分页查询")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<PageDataVo<WsWaterTypeVo>> page(
            @RequestBody @Validated(PageGroup.class) WsWaterTypeBo bo) {
        return R.ok(waterTypeService.pageData(bo));
    }

    @PostMapping("/listEnabled")
    @Operation(summary = "全部启用水种（出水口/套餐配置下拉）")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<List<WsWaterTypeVo>> listEnabled() {
        return R.ok(waterTypeService.listEnabled());
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/add")
    @Operation(summary = "新增")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "product:water:add", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Long> add(
            @RequestBody @Validated(InsertGroup.class) WsWaterTypeBo bo) {
        return R.ok(waterTypeService.saveData(bo));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/update")
    @Operation(summary = "修改")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "product:water:update", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> update(
            @RequestBody @Validated({IdGroup.class, InsertGroup.class}) WsWaterTypeBo bo) {
        return R.ok(waterTypeService.updateData(bo));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/delete")
    @Operation(summary = "删除（须先禁用且无出水口引用）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "product:water:delete", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> delete(
            @RequestBody @Validated(IdGroup.class) WsWaterTypeBo bo) {
        return R.ok(waterTypeService.deleteData(bo.getId()));
    }
}

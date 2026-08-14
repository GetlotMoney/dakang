package com.jbk.serve.controller.mall;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.mall.IMallCategoryService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallCategoryBo;
import com.jbk.tool.data.mall.bo.MallQueryBo;
import com.jbk.tool.data.mall.bo.MallStatusChangeBo;
import com.jbk.tool.data.mall.vo.MallCategoryVo;
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
 * 商城分类管理（E2E-09 S1）。
 *
 * @author dakang
 * @since 2026-08-08
 */
@RestController
@RequestMapping("/mall/category")
@Tag(name = "商城分类管理")
@RequiredArgsConstructor
public class MallCategoryController {

    private final IMallCategoryService categoryService;

    @PostMapping("/page")
    @Operation(summary = "分类分页")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<PageDataVo<MallCategoryVo>> page(@RequestBody MallQueryBo bo) {
        return R.ok(categoryService.page(bo));
    }

    @PostMapping("/list")
    @Operation(summary = "全部分类（下拉）")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<List<MallCategoryVo>> list() {
        return R.ok(categoryService.listAll());
    }

    @PostMapping("/save")
    @Operation(summary = "新增分类")
    @LogOperation
    @RepeatSubmit
    @MySaCheckOr(
        login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
        permission = {@SaCheckPermission(value = "mall:category:edit", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Long> save(@Validated @RequestBody MallCategoryBo bo) {
        return R.ok(categoryService.save(bo));
    }

    @PostMapping("/update")
    @Operation(summary = "修改分类（编码不可改）")
    @LogOperation
    @RepeatSubmit
    @MySaCheckOr(
        login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
        permission = {@SaCheckPermission(value = "mall:category:edit", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> update(@Validated @RequestBody MallCategoryBo bo) {
        return R.ok(categoryService.update(bo));
    }

    @PostMapping("/change-status")
    @Operation(summary = "分类启停（停用分类下商品不得新上架）")
    @LogOperation
    @RepeatSubmit
    @MySaCheckOr(
        login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
        permission = {@SaCheckPermission(value = "mall:category:edit", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> changeStatus(@Validated @RequestBody MallStatusChangeBo bo) {
        return R.ok(categoryService.changeStatus(bo));
    }
}

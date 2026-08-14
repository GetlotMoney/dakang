package com.jbk.serve.controller.mall;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.mall.IMallProductService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallIdBo;
import com.jbk.tool.data.mall.bo.MallProductBo;
import com.jbk.tool.data.mall.bo.MallQueryBo;
import com.jbk.tool.data.mall.bo.MallShelfBo;
import com.jbk.tool.data.mall.vo.MallProductDetailVo;
import com.jbk.tool.data.mall.vo.MallProductVo;
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

/**
 * 商城商品管理（E2E-09 S1）：SPU+SKU 联动维护、上下架。
 *
 * @author dakang
 * @since 2026-08-08
 */
@RestController
@RequestMapping("/mall/product")
@Tag(name = "商城商品管理")
@RequiredArgsConstructor
public class MallProductController {

    private final IMallProductService productService;

    @PostMapping("/page")
    @Operation(summary = "商品分页")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<PageDataVo<MallProductVo>> page(@RequestBody MallQueryBo bo) {
        return R.ok(productService.page(bo));
    }

    @PostMapping("/detail")
    @Operation(summary = "商品详情（SPU+SKU+各仓库存）")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<MallProductDetailVo> detail(@Validated @RequestBody MallIdBo bo) {
        return R.ok(productService.detail(bo));
    }

    @PostMapping("/save")
    @Operation(summary = "新建商品（SPU+SKU 同事务）")
    @LogOperation
    @RepeatSubmit
    @MySaCheckOr(
        login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
        permission = {@SaCheckPermission(value = "mall:product:edit", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Long> save(@Validated @RequestBody MallProductBo bo) {
        return R.ok(productService.save(bo));
    }

    @PostMapping("/update")
    @Operation(summary = "修改商品（编号不可改；SKU upsert 不物理删除）")
    @LogOperation
    @RepeatSubmit
    @MySaCheckOr(
        login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
        permission = {@SaCheckPermission(value = "mall:product:edit", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> update(@Validated @RequestBody MallProductBo bo) {
        return R.ok(productService.update(bo));
    }

    @PostMapping("/publish")
    @Operation(summary = "上架（分类启用/启用SKU/可售库存三闸 + 版本 CAS）")
    @LogOperation
    @RepeatSubmit
    @MySaCheckOr(
        login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
        permission = {@SaCheckPermission(value = "mall:product:shelf", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> publish(@Validated @RequestBody MallShelfBo bo) {
        return R.ok(productService.publish(bo));
    }

    @PostMapping("/unpublish")
    @Operation(summary = "下架（版本 CAS；不删 SKU/库存/流水）")
    @LogOperation
    @RepeatSubmit
    @MySaCheckOr(
        login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
        permission = {@SaCheckPermission(value = "mall:product:shelf", type = StpKit.DRIVER_MANAGE)}
    )
    public R<Boolean> unpublish(@Validated @RequestBody MallShelfBo bo) {
        return R.ok(productService.unpublish(bo));
    }
}

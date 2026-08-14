package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mall.IMiniMallService;
import com.jbk.tool.data.mall.bo.MallIdBo;
import com.jbk.tool.data.mall.bo.MiniMallHomeBo;
import com.jbk.tool.data.mall.vo.MiniMallHomeVo;
import com.jbk.tool.data.mall.vo.MiniMallProductDetailVo;
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
 * 小程序商城只读（E2E-09 S1）：已上架商品/启用SKU/启用仓可售聚合白名单出参；
 * 数据源异常 fail-closed，端上不回退 Mock。本期无下单/购物车能力。
 *
 * @author dakang
 * @since 2026-08-08
 */
@RestController
@RequestMapping("/mini/mall")
@Tag(name = "小程序商城")
@RequiredArgsConstructor
public class MiniMallController {

    private final IMiniMallService miniMallService;

    @PostMapping("/home")
    @Operation(summary = "商城首页（启用分类+已上架商品卡）")
    @SaCheckLogin(type = StpKit.DRIVER_KH_USER)
    public R<MiniMallHomeVo> home(@RequestBody MiniMallHomeBo bo) {
        return R.ok(miniMallService.home(bo));
    }

    @PostMapping("/product/detail")
    @Operation(summary = "商品详情（启用SKU+有货布尔）")
    @SaCheckLogin(type = StpKit.DRIVER_KH_USER)
    public R<MiniMallProductDetailVo> productDetail(@Validated @RequestBody MallIdBo bo) {
        return R.ok(miniMallService.productDetail(bo));
    }
}

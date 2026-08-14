package com.jbk.serve.controller.mall;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.mall.IMallStockService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallQueryBo;
import com.jbk.tool.data.mall.bo.MallStockAdjustBo;
import com.jbk.tool.data.mall.vo.MallSkuCandidateVo;
import com.jbk.tool.data.mall.vo.MallStockAdjustResultVo;
import com.jbk.tool.data.mall.vo.MallStockFlowVo;
import com.jbk.tool.data.mall.vo.MallStockVo;
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
 * 商城库存管理（E2E-09 S1）：入库/出库/盘点调整与流水追溯。
 *
 * @author dakang
 * @since 2026-08-08
 */
@RestController
@RequestMapping("/mall/stock")
@Tag(name = "商城库存管理")
@RequiredArgsConstructor
public class MallStockController {

    private final IMallStockService stockService;

    @PostMapping("/page")
    @Operation(summary = "库存分页（仓/分类/商品/SKU 筛选）")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<PageDataVo<MallStockVo>> page(@RequestBody MallQueryBo bo) {
        return R.ok(stockService.page(bo));
    }

    @PostMapping("/sku-candidates")
    @Operation(summary = "库存动作 SKU 候选（SKU⋈商品，独立于库存行；新 SKU 可首次入库）")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<PageDataVo<MallSkuCandidateVo>> skuCandidates(@RequestBody MallQueryBo bo) {
        return R.ok(stockService.skuCandidates(bo));
    }

    @PostMapping("/adjust")
    @Operation(summary = "库存人工动作（入库/出库/盘点调整；请求号幂等，重放返回冻结原结果）")
    @LogOperation
    @MySaCheckOr(
        login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
        permission = {@SaCheckPermission(value = "mall:stock:adjust", type = StpKit.DRIVER_MANAGE)}
    )
    public R<MallStockAdjustResultVo> adjust(@Validated @RequestBody MallStockAdjustBo bo) {
        return R.ok(stockService.adjust(bo));
    }

    @PostMapping("/flow/page")
    @Operation(summary = "库存流水分页")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<PageDataVo<MallStockFlowVo>> flowPage(@RequestBody MallQueryBo bo) {
        return R.ok(stockService.flowPage(bo));
    }
}

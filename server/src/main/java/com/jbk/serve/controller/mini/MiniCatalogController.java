package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mini.IMiniCatalogService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.mini.vo.MiniCatalogVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序目录只读接口（E2E-03 包B：U07 附近水站 / U08 配送下单 / D02 准入表单消费）。
 * <p>路径对齐冻结的前端契约 catalog.ts catalogEndpoints；套餐列表归充值域
 * （/mini/package/list），本控制器不重复提供。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Tag(name = "MINI-目录")
@Validated
@RestController
@RequestMapping("/mini/catalog")
public class MiniCatalogController {

    @Autowired
    private IMiniCatalogService miniCatalogService;

    @PostMapping("/water-type/list")
    @Operation(summary = "水种列表（含停用标注；8 种水最终定义待甲方确认）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<List<MiniCatalogVo.MiniWaterTypeVo>> listWaterTypes() {
        return R.ok(miniCatalogService.listWaterTypes());
    }

    @PostMapping("/station/list")
    @Operation(summary = "水站列表（营业状态与设备/出水口计数派生）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<List<MiniCatalogVo.MiniStationVo>> listStations() {
        return R.ok(miniCatalogService.listStations());
    }
}

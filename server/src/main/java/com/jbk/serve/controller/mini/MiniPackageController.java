package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mini.IMiniPackageService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.mini.vo.MiniPackageVo;
import com.jbk.tool.utils.satoken.StpKit;
import com.jbk.tool.domain.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序充值套餐（L2-READ 只读）。
 *
 * @author dakang
 * @since 2026-07-22
 */
@Tag(name = "MINI-充值套餐")
@Validated
@RestController
@RequestMapping("/mini/package")
@RequiredArgsConstructor
public class MiniPackageController {

    private final IMiniPackageService miniPackageService;

    @PostMapping("/list")
    @Operation(summary = "列出在售充值套餐（只读）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<List<MiniPackageVo>> list() {
        return R.ok(miniPackageService.listOnSale());
    }
}

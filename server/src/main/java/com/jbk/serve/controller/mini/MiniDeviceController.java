package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mini.IMiniDeviceService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.mini.bo.MiniScanBo;
import com.jbk.tool.data.mini.vo.ScanSessionVo;
import com.jbk.tool.data.mini.vo.WaterDeviceContextVo;
import com.jbk.tool.data.mini.vo.WaterEligibilityVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序设备/扫码取水（L1a 扫码链：解析→上下文→取水资格）。
 * <p>
 * 每个接口经 {@link MySaCheckOr} 强制 KH_USER 登录（SaCheckOrAspect 仅识别方法级注解）；
 * userId 一律取会话，禁收前端 userId（安全铁律6）。
 * </p>
 *
 * @author dakang
 * @since 2026-07-19
 */
@Tag(name = "MINI-设备扫码取水")
@Validated
@RestController
@RequestMapping("/mini/device")
public class MiniDeviceController {

    @Autowired
    private IMiniDeviceService miniDeviceService;

    @PostMapping("/scan/resolve")
    @Operation(summary = "扫码解析（码原文→设备/出水口，铸造扫码会话）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<ScanSessionVo> resolveScan(@RequestBody MiniScanBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeviceService.resolveScan(bo.getRawCode(), userId));
    }

    @PostMapping("/water/context")
    @Operation(summary = "取水设备上下文（按会话读设备/水站/出水口）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<WaterDeviceContextVo> waterContext(@RequestBody MiniScanBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeviceService.getWaterContext(bo.getScanSessionId(), userId));
    }

    @PostMapping("/water/eligibility")
    @Operation(summary = "取水资格校验（设备可用性+指定水卡阻断双分组，CARD-SCOPE：cardId 由前端指明）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<WaterEligibilityVo> waterEligibility(@RequestBody MiniScanBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeviceService.checkEligibility(bo.getScanSessionId(), bo.getCardId(), userId));
    }
}

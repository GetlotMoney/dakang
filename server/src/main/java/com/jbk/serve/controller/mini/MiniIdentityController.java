package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.identity.IMiniIdentityService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.identity.bo.*;
import com.jbk.tool.data.identity.vo.*;
import com.jbk.tool.data.settlement.bo.WithdrawBo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 小程序身份申请、渠道、区域血缘、公域线索与演示控制。 */
@Tag(name = "MINI-身份与经营工作台")
@RestController
@RequestMapping("/mini/identity")
@RequiredArgsConstructor
public class MiniIdentityController {

    private final IMiniIdentityService identityService;

    @PostMapping("/overview")
    @Operation(summary = "本人身份申请与授权总览")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniIdentityOverviewVo> overview() {
        return R.ok(identityService.overview(StpKit.KH_USER.getLoginIdAsLong()));
    }

    @RepeatSubmit
    @PostMapping("/apply")
    @Operation(summary = "提交机主/渠道/区域代理申请；演示环境条件通过后自动审核")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniIdentityOverviewVo> apply(@RequestBody @Valid MiniIdentityApplyBo bo) {
        return R.ok(identityService.apply(StpKit.KH_USER.getLoginIdAsLong(), bo));
    }

    @PostMapping("/channel/overview")
    @Operation(summary = "渠道本人工作台（按会话强制过滤）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniIdentityDashboardVo> channelOverview() {
        return R.ok(identityService.channelDashboard(StpKit.KH_USER.getLoginIdAsLong()));
    }

    @PostMapping("/region/overview")
    @Operation(summary = "区域代理本人工作台与公域线索")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniIdentityDashboardVo> regionOverview() {
        return R.ok(identityService.regionDashboard(StpKit.KH_USER.getLoginIdAsLong()));
    }

    @RepeatSubmit
    @PostMapping("/region/lead/confirm")
    @Operation(summary = "确认本人获配公域机主线索并建立冻结血缘")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniIdentityDashboardVo> confirmLead(@RequestBody @Valid MiniPublicLeadBo bo) {
        return R.ok(identityService.confirmPublicLead(StpKit.KH_USER.getLoginIdAsLong(), bo));
    }

    @PostMapping("/demo/control")
    @Operation(summary = "本人演示控制状态（正式环境仅返回关闭态）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniDemoControlVo> demoControl() {
        return R.ok(identityService.demoControl(StpKit.KH_USER.getLoginIdAsLong()));
    }

    @RepeatSubmit
    @PostMapping("/demo/control/update")
    @Operation(summary = "配置下一次模拟支付/设备/配送行为")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniDemoControlVo> updateDemoControl(@RequestBody @Valid MiniDemoControlBo bo) {
        return R.ok(identityService.updateDemoControl(StpKit.KH_USER.getLoginIdAsLong(), bo));
    }

    @RepeatSubmit
    @PostMapping("/wallet/withdraw-sim")
    @Operation(summary = "演示提现：冻结后由Payout-Sim完成，不触发真实出金")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<String> withdrawSim(@RequestBody @Valid WithdrawBo bo) {
        return R.ok(identityService.simulateWithdraw(StpKit.KH_USER.getLoginIdAsLong(), bo));
    }
}

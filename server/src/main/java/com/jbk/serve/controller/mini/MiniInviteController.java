package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.settlement.IInviteService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.settlement.bo.MiniInviteBo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序邀请归因（E2E-08 包D / 甲方③邀请码路径）：本人邀请码 + 补绑推荐人。
 * <p>一律以 KH_USER 会话为身份（铁律6）；绑定一次性（前态 CAS）、防自邀、审计同事务；
 * 绑定不回溯——只影响绑定之后的新订单归因快照。微信分享链接/小程序码归 R-001 外部能力。</p>
 *
 * @author dakang
 * @since 2026-07-31
 */
@Tag(name = "MINI-邀请归因")
@Validated
@RestController
@RequestMapping("/mini/invite")
public class MiniInviteController {

    @Autowired
    private IInviteService inviteService;

    @PostMapping("/my-code")
    @Operation(summary = "本人邀请码（无则惰性生成，确定性派生）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<String> myCode() {
        return R.ok(inviteService.myInviteCode(StpKit.KH_USER.getLoginIdAsLong()));
    }

    @RepeatSubmit
    @PostMapping("/bind")
    @Operation(summary = "补绑推荐人（一次性；防自邀；已绑定明确拒绝）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<Boolean> bind(@RequestBody @Valid MiniInviteBo bo) {
        inviteService.bindReferrer(StpKit.KH_USER.getLoginIdAsLong(), bo.getInviteCode());
        return R.ok(Boolean.TRUE);
    }
}

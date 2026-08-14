package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.taptap.ratelimiter.annotation.RateLimit;
import com.jbk.serve.service.mini.invite.MiniInviteQrService;
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
    @Autowired
    private MiniInviteQrService inviteQrService;

    @PostMapping("/my-code")
    @Operation(summary = "本人邀请码（无则惰性生成，确定性派生）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<String> myCode() {
        return R.ok(inviteService.myInviteCode(StpKit.KH_USER.getLoginIdAsLong()));
    }

    /**
     * 本人邀请小程序码的 scene。
     *
     * <p><b>不收任何入参</b>：归属人只从会话取。若允许前端指定 userId 或 inviteCode，
     * 任何人都能生成一张归属于别人的码，而归属一次性不可逆（D-413），错了只能人工改库。</p>
     *
     * <p>限流按会话人：出码是可被脚本刷的读接口，且每次都会写一条审计。
     * 不限流的话，一个循环就能把领域事件表刷满，真正的审计淹在噪声里。</p>
     *
     * <p>本轮只返回 scene，不返回图片二进制——取图要 access_token 外呼微信，
     * 任务书第二节禁止真实外呼。scene 的签名/限长/防篡改/可过期才是会让归属被冒用的部分，
     * 它们不需要外呼即可完整验证。</p>
     */
    @PostMapping("/qr-scene")
    @Operation(summary = "本人邀请小程序码 scene（带签名与到期日；不收入参，归属只从会话取）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    // keys 用 SpEL 直接取会话身份，按人分桶而不是按 IP——同一个 WiFi 下多人各自出码
    // 不该互相挤掉，而一个人换 IP 也不该重置自己的额度。
    // 【为什么不写成 #userId】那要求方法有一个名为 userId 的**入参**，
    // 而本接口刻意不收任何入参（归属只能来自会话）。为了让限流表达式好写就加一个入参，
    // 等于把"调用方能指定归属人"这个口子重新开出来。
    // 10 次/分钟对正常使用（点一次分享）绰绰有余。
    @RateLimit(keys = "T(com.jbk.tool.utils.satoken.StpKit).KH_USER.getLoginIdAsString()",
            rate = 10, rateInterval = "60s")
    public R<String> qrScene() {
        return R.ok(inviteQrService.issueScene(StpKit.KH_USER.getLoginIdAsLong()));
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

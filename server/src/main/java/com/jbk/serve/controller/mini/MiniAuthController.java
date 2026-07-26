package com.jbk.serve.controller.mini;

import com.jbk.serve.service.mini.IMiniAuthService;
import com.jbk.tool.data.mini.bo.MiniBindPhoneBo;
import com.jbk.tool.data.mini.bo.MiniLoginBo;
import com.jbk.tool.data.mini.vo.MiniAuthResultVo;
import com.jbk.tool.domain.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * 小程序 L2-AUTH：正式微信登录与手机号绑定入口。
 * <p>本组接口本身即"登录"，无需既有会话；建立的会话统一为 {@code StpKit.KH_USER}。
 * 响应体绝不携带 openid / session_key；不提供任何免鉴权或测试账号旁路。</p>
 *
 * @author dakang
 * @since 2026-07-21
 */
@Tag(name = "MINI-登录")
@Validated
@RestController
@RequestMapping("/mini/auth")
@RequiredArgsConstructor
public class MiniAuthController {

    private final IMiniAuthService miniAuthService;

    @PostMapping("/login")
    @Operation(summary = "小程序登录（BOUND 建会话 / UNBOUND 下发绑定票据）")
    public R<MiniAuthResultVo> login(@RequestBody @Valid MiniLoginBo bo) {
        return R.ok(miniAuthService.login(bo));
    }

    @PostMapping("/bind-phone")
    @Operation(summary = "绑定手机号并建立 KH_USER 会话")
    public R<MiniAuthResultVo> bindPhone(@RequestBody @Valid MiniBindPhoneBo bo) {
        return R.ok(miniAuthService.bindPhone(bo));
    }
}

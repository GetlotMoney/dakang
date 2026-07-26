package com.jbk.serve.controller.mini;

import com.jbk.serve.service.mini.IMiniTestLoginService;
import com.jbk.tool.data.mini.bo.MiniTestLoginBo;
import com.jbk.tool.data.mini.vo.MiniAuthResultVo;
import com.jbk.tool.domain.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试登录入口（<b>仅 {@code mini.pay-sim.enabled=true} 时注册</b>，生产不存在该路由）。
 *
 * <p>与 Pay-Sim 共用同一个开关，是刻意的：模拟支付与模拟登录属于同一档次的能力，
 * 应当同生共死。任何一个环境如果开了其中一个而关掉另一个，都说明门控被人手工绕过了。</p>
 */
@Tag(name = "MINI-测试登录(仅测试环境)")
@Validated
@RestController
@RequestMapping("/mini/test-login")
@ConditionalOnProperty(name = "mini.pay-sim.enabled", havingValue = "true")
public class MiniTestLoginController {

    @Autowired
    private IMiniTestLoginService miniTestLoginService;

    @PostMapping("/by-phone")
    @Operation(summary = "以已存在账号的手机号建立 KH_USER 会话（不创建账号，仅测试环境）")
    public R<MiniAuthResultVo> byPhone(@RequestBody @Valid MiniTestLoginBo bo) {
        return R.ok(miniTestLoginService.login(bo));
    }
}

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
 * 测试登录入口（仅 {@code mini.test-login.enabled=true} 时注册，缺省不存在该路由）。
 * 与 Pay-Sim 开关刻意拆开：本端点凭一个手机号即签发完整 KH_USER 会话，签出的 Token 与
 * 正式登录完全同形、事后无法分辨——危险度不同的能力必须能分别授权，跑模拟支付不得顺带
 * 打开身份绕过；需要它的隔离验收环境（e2e 跑批脚本）显式声明。
 */
@Tag(name = "MINI-测试登录(仅测试环境)")
@Validated
@RestController
@RequestMapping("/mini/test-login")
@ConditionalOnProperty(name = "mini.test-login.enabled", havingValue = "true")
public class MiniTestLoginController {

    @Autowired
    private IMiniTestLoginService miniTestLoginService;

    @PostMapping("/by-phone")
    @Operation(summary = "以已存在账号的手机号建立 KH_USER 会话（不创建账号，仅测试环境）")
    public R<MiniAuthResultVo> byPhone(@RequestBody @Valid MiniTestLoginBo bo) {
        return R.ok(miniTestLoginService.login(bo));
    }
}

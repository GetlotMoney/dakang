package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mall.IMallPaySimService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.mall.bo.MallOrderActionBo;
import com.jbk.tool.data.mall.vo.MallOrderDetailVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商城 Pay-Sim 模拟支付（E2E-09 S2）。
 *
 * <p>由 {@code mall.pay-sim.enabled=true} 装配，生产不注册该路由——模拟支付端点
 * 一旦在生产可达，任何登录用户都能把自己的订单标记为已付。即便如此仍强制登录
 * 与归属校验：隔离环境也不给越权操作别人订单的口子。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@RestController
@RequestMapping("/mini/mall/pay-sim")
@Tag(name = "小程序商城模拟支付")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.pay-sim.enabled", havingValue = "true")
public class MiniMallPaySimController {

    private final IMallPaySimService paySimService;

    /**
     * 刻意不加 {@link RepeatSubmit}：支付事实的幂等锚是
     * uk(PAY_SOURCE, FACT_CHANNEL, PROVIDER_EVENT_KEY)，事实键由订单号确定性派生，
     * 重复调用必然复用同一条事实。短时防重切面会把合法重试挡在领域幂等之外，
     * 让"已付款但页面没刷新"的用户点第二次时拿到一个与支付状态无关的错误。
     */
    @PostMapping("/pay")
    @Operation(summary = "模拟支付成功（落事实→推进实销；同订单重复调用幂等）")
    @LogOperation
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_KH_USER)})
    public R<MallOrderDetailVo> pay(@Validated @RequestBody MallOrderActionBo bo) {
        return R.ok(paySimService.pay(StpKit.KH_USER.getLoginIdAsLong(), bo.getOrderNo()));
    }
}

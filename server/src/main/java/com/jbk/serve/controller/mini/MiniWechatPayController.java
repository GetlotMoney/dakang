package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mini.wxpay.MiniWechatPayService;
import com.jbk.serve.service.mini.wxpay.WechatPaySigner;
import com.jbk.tool.annotation.MySaCheckOr;
import com.taptap.ratelimiter.annotation.RateLimit;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.jbk.tool.data.mini.bo.MiniWechatPrepayBo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信支付下单入口（WX-ECO S3）。
 *
 * <p>只收订单号；归属人从会话取，金额从服务端支付单取。
 * 刻意不加 @RepeatSubmit：JSAPI 下单幂等锚在微信侧（同商户订单号复用 prepay_id），
 * 短时防重会把"支付页没拉起来点第二次"的正常重试挡成错误。</p>
 *
 * @author dakang
 * @since 2026-08-13
 */
@Tag(name = "微信支付")
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/mini/wxpay")
public class MiniWechatPayController {

    private final MiniWechatPayService wechatPayService;

    @PostMapping("/prepay")
    @Operation(summary = "充值订单 JSAPI 下单（返回 requestPayment 五参数）")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_KH_USER)})
    @RateLimit(keys = "T(com.jbk.tool.utils.satoken.StpKit).KH_USER.getLoginIdAsString()",
            rate = 10, rateInterval = "60s")
    public R<WechatPaySigner.MiniPayParams> prepay(@RequestBody @Valid MiniWechatPrepayBo bo) {
        return R.ok(wechatPayService.prepay(StpKit.KH_USER.getLoginIdAsLong(), bo.getOrderNo()));
    }
}

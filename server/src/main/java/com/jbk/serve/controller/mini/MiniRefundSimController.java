package com.jbk.serve.controller.mini;

import com.jbk.serve.service.aftersale.refund.IRefundFactService;
import com.jbk.serve.service.aftersale.refund.impl.RefundSimFactProducer;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.domain.R;
import com.jbk.tool.data.aftersale.bo.RefundSimNotifyBo;
import com.jbk.tool.utils.satoken.StpKit;
import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.tool.annotation.MySaCheckOr;
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
 * Refund-Sim 模拟退款事实入口（E2E-04 包B，R0-8）。
 *
 * <p><b>双重门控</b>：类上的 {@code @ConditionalOnProperty} 保证生产环境
 * 连这个 Bean 都不存在（路由 404 而不是 403——没有接口比有接口但拒绝更安全）；
 * 权限上要求后台 {@code order:aftersale:refund}；模拟来源与真实来源必须共用财务退款边界，
 * 不能让日常售后处理权限制造退款成功事实。</p>
 *
 * <p>本接口<b>不返回退款是否成功</b>，只返回事实的消费结局：
 * 「事实已被消费」与「退款成功」是两件事，混为一谈正是 R0-8 要避免的（见 Outcome 注释）。</p>
 */
@Tag(name = "Refund-Sim 模拟退款")
@RestController
@RequiredArgsConstructor
@RequestMapping("/mini/refund-sim")
@ConditionalOnProperty(name = "mini.refund-sim.enabled", havingValue = "true")
public class MiniRefundSimController {

    private final RefundSimFactProducer producer;

    @PostMapping("/notify")
    @RepeatSubmit
    @Operation(summary = "模拟支付机构退款事实（造一条与真实通知同构的事实并走同一处理器）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) },
            permission = { @SaCheckPermission(value = "order:aftersale:refund", type = StpKit.DRIVER_MANAGE) })
    public R<IRefundFactService.Outcome> notifyFact(@RequestBody @Validated RefundSimNotifyBo bo) {
        return R.ok(producer.notifyFact(bo));
    }
}

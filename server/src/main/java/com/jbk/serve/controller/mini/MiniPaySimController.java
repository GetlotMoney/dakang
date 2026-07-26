package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mini.IMiniPaySimService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.mini.bo.MiniPaySimBo;
import com.jbk.tool.data.mini.vo.MiniPaySimVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
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
 * Pay-Sim 模拟支付入口（<b>仅 {@code mini.pay-sim.enabled=true} 时才注册</b>，生产不存在该路由）。
 *
 * <p>仍然强制 KH_USER 登录且只能付自己的订单——模拟环境放宽的是"钱从哪来"，
 * 不是"谁能操作谁的单"。归属校验一旦在这里松掉，演示环境就会养成错误的肌肉记忆。</p>
 */
@Tag(name = "MINI-模拟支付(仅测试环境)")
@Validated
@RestController
@RequestMapping("/mini/pay-sim")
@ConditionalOnProperty(name = "mini.pay-sim.enabled", havingValue = "true")
public class MiniPaySimController {

    @Autowired
    private IMiniPaySimService miniPaySimService;

    @PostMapping("/pay")
    @Operation(summary = "模拟支付成功（造一条与真实回调同构的支付事实并走同一处理器）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniPaySimVo> pay(@RequestBody @Valid MiniPaySimBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniPaySimService.pay(bo, userId));
    }

    /**
     * 模拟支付方主动查单。<b>它不是"超时关单接口"</b>——调用方不能决定订单关不关，
     * 只能拿到支付方的回答（NOTPAY/CLOSED）并让统一处理器按契约推进。
     */
    @PostMapping("/query")
    @Operation(summary = "模拟支付方主动查单（造一条 FACT_CHANNEL=2 查询事实并走同一处理器）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniPaySimVo> query(@RequestBody @Valid MiniPaySimBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniPaySimService.query(bo, userId));
    }
}

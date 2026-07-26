package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mini.IMiniDeliveryService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.delivery.bo.DeliveryCreateBo;
import com.jbk.tool.data.mini.vo.MiniDeliveryCreateVo;
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
 * 小程序配送下单（E2E-03 包B / U08）。
 * <p>Controller 只做鉴权 + 会话取 userId + 参数透传；计价/幂等/扣款/任务生成
 * 全部在包A 事务服务内裁决（铁律6：userId 只从会话取，禁收前端 userId）。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Tag(name = "MINI-配送下单")
@Validated
@RestController
@RequestMapping("/mini/delivery/order")
public class MiniDeliveryOrderController {

    @Autowired
    private IMiniDeliveryService miniDeliveryService;

    @PostMapping("/create")
    @Operation(summary = "创建配送订单（即时/预约/自动补货；卡余额即时扣款，requestId 幂等）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniDeliveryCreateVo> create(@RequestBody @Valid DeliveryCreateBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeliveryService.createOrder(bo, userId));
    }
}

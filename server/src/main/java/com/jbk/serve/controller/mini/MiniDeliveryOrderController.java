package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.aftersale.IDeliveryCancelService;
import com.jbk.serve.service.mini.IMiniDeliveryService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.aftersale.bo.DeliveryCancelBo;
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
    @Autowired
    private IDeliveryCancelService deliveryCancelService;

    @PostMapping("/create")
    @Operation(summary = "创建配送订单（即时/预约/自动补货；卡余额即时扣款，requestId 幂等）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniDeliveryCreateVo> create(@RequestBody @Valid DeliveryCreateBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeliveryService.createOrder(bo, userId));
    }

    /**
     * 待接单取消（E2E-04 包A）。返回的是<b>面向用户的结果文案</b>而不是布尔值：
     * 「已退还至水卡」与「退款处理中」是两种必须区分的结局——订单状态先于资金独占，
     * 业务段提交而资金段失败时钱仍在途，此时回一个 true 会让用户以为已经到账。
     */
    @RepeatSubmit
    @PostMapping("/cancel")
    @Operation(summary = "待接单取消配送订单（任务1→6、订单2→7，整单满额返还由售后内核独立事务执行）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<String> cancel(@RequestBody @Valid DeliveryCancelBo bo) {
        // 归属只从会话取（铁律6）：请求体里没有 userId，也不接受
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(deliveryCancelService.cancelPendingOrder(bo, userId));
    }
}

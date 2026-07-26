package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mini.IMiniDeliveryService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.delivery.bo.DeliveryAppealCreateBo;
import com.jbk.tool.data.mini.bo.MiniOrderAppealDetailBo;
import com.jbk.tool.data.mini.bo.MiniOrderDeliveryTaskBo;
import com.jbk.tool.data.mini.vo.MiniDeliveryAppealVo;
import com.jbk.tool.data.mini.vo.MiniDeliveryTaskVo;
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
 * 小程序订单侧配送视图（E2E-03 包B / U06·U09：消费者视角任务证据与申诉）。
 *
 * <p>路径挂在 /mini/order 下与冻结的前端契约（order.ts orderEndpoints）一致；
 * 独立成类不动既有 MiniOrderController。归属校验（本人订单/本人申诉）与
 * 24h 窗口等业务判定全部在 Service / 包A 事务内完成（铁律6）。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Tag(name = "MINI-订单配送视图")
@Validated
@RestController
@RequestMapping("/mini/order")
public class MiniOrderDeliveryController {

    @Autowired
    private IMiniDeliveryService miniDeliveryService;

    @PostMapping("/delivery-task/detail")
    @Operation(summary = "本人配送订单的任务证据（U06 轨迹/三照/实际数量/申诉截止；无任务返回空）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniDeliveryTaskVo> deliveryTaskDetail(@RequestBody @Valid MiniOrderDeliveryTaskBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeliveryService.getMyDeliveryTask(bo.getOrderNo(), userId));
    }

    @PostMapping("/appeal/detail")
    @Operation(summary = "本人申诉详情（非本人按不存在拒绝）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniDeliveryAppealVo> appealDetail(@RequestBody @Valid MiniOrderAppealDetailBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeliveryService.getMyAppeal(bo.getAppealId(), userId));
    }

    @PostMapping("/appeal/create")
    @Operation(summary = "创建配送申诉（本人+订单已完成+任务已签收+24h 窗口，事务内裁决）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniDeliveryAppealVo> appealCreate(@RequestBody @Valid DeliveryAppealCreateBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniDeliveryService.createAppeal(bo, userId));
    }
}

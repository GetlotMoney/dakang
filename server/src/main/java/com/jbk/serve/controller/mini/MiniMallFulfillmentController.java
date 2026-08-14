package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mall.IMallFulfillmentService;
import com.jbk.serve.service.mall.IMallSelfDeliveryService;
import com.jbk.serve.service.mall.IMallShipmentService;
import com.jbk.tool.data.mall.bo.MallFulfillActionBo;
import com.jbk.tool.data.mall.bo.MallFulfillSignBo;
import com.jbk.tool.data.mall.vo.MallFulfillVo;
import com.jbk.tool.data.mall.vo.MallShipmentVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序商城履约（E2E-09 S3）：用户侧物流回看与签收，配送员侧本人任务。
 *
 * <p>两类身份共用同一个会话类型但走不同端点：用户端按 USER_ID 过滤，配送端按
 * 配送员身份反查本人已分配任务。归属恒取会话，不接受任何 userId/courierId 入参。</p>
 *
 * <p>签收端点不带 @RepeatSubmit：领域幂等已由「精确前态 + 版本号」CAS 保证，
 * HTTP 层防重会在并发下把合法请求截成"操作太频繁"，反而盖住真实结论。</p>
 *
 * @author dakang
 * @since 2026-08-09
 */
@RestController
@RequestMapping("/mini/mall/fulfillment")
@Tag(name = "小程序商城履约")
@RequiredArgsConstructor
public class MiniMallFulfillmentController {

    private final IMallFulfillmentService fulfillmentService;
    private final IMallSelfDeliveryService selfDeliveryService;
    private final IMallShipmentService shipmentService;

    @PostMapping("/detail")
    @Operation(summary = "本人商城订单履约详情（仓库/配送员/进度/签收）")
    @SaCheckLogin(type = StpKit.DRIVER_KH_USER)
    public R<MallFulfillVo> detail(@Validated @RequestBody MallFulfillActionBo bo) {
        return R.ok(fulfillmentService.detailForUser(
                StpKit.KH_USER.getLoginIdAsLong(), bo.getOrderNo()));
    }

    @PostMapping("/sign")
    @Operation(summary = "用户签收（订单转已完成）")
    @SaCheckLogin(type = StpKit.DRIVER_KH_USER)
    public R<MallFulfillVo> sign(@Validated @RequestBody MallFulfillSignBo bo) {
        return R.ok(fulfillmentService.signByUser(StpKit.KH_USER.getLoginIdAsLong(), bo));
    }

    @PostMapping("/shipments")
    @Operation(summary = "本人订单的出库包裹（承运商/运单号/物流轨迹）")
    @SaCheckLogin(type = StpKit.DRIVER_KH_USER)
    public R<List<MallShipmentVo>> shipments(@Validated @RequestBody MallFulfillActionBo bo) {
        return R.ok(shipmentService.listForUser(
                StpKit.KH_USER.getLoginIdAsLong(), bo.getOrderNo()));
    }

    @PostMapping("/courier/list")
    @Operation(summary = "配送员本人商城任务列表")
    @SaCheckLogin(type = StpKit.DRIVER_KH_USER)
    public R<List<MallFulfillVo>> courierList() {
        return R.ok(selfDeliveryService.listForCourier(StpKit.KH_USER.getLoginIdAsLong()));
    }

    @PostMapping("/courier/detail")
    @Operation(summary = "配送员本人商城任务详情")
    @SaCheckLogin(type = StpKit.DRIVER_KH_USER)
    public R<MallFulfillVo> courierDetail(@Validated @RequestBody MallFulfillActionBo bo) {
        return R.ok(selfDeliveryService.detailForCourier(
                StpKit.KH_USER.getLoginIdAsLong(), bo.getOrderNo()));
    }

    @PostMapping("/courier/fetch")
    @Operation(summary = "配送员取货")
    @SaCheckLogin(type = StpKit.DRIVER_KH_USER)
    public R<MallFulfillVo> fetch(@Validated @RequestBody MallFulfillActionBo bo) {
        return R.ok(selfDeliveryService.fetch(StpKit.KH_USER.getLoginIdAsLong(), bo));
    }

    @PostMapping("/courier/arrive")
    @Operation(summary = "配送员送达")
    @SaCheckLogin(type = StpKit.DRIVER_KH_USER)
    public R<MallFulfillVo> arrive(@Validated @RequestBody MallFulfillActionBo bo) {
        return R.ok(selfDeliveryService.arrive(StpKit.KH_USER.getLoginIdAsLong(), bo));
    }
}

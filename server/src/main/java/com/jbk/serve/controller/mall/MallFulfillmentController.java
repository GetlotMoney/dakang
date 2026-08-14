package com.jbk.serve.controller.mall;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.mall.IMallFulfillmentService;
import com.jbk.tool.data.mall.vo.MallShipmentVo;
import com.jbk.tool.data.mall.bo.MallShipmentCreateBo;
import com.jbk.serve.service.mall.IMallShipmentService;
import com.jbk.serve.service.mall.IMallLogisticsService;
import com.jbk.serve.service.mall.IMallSelfDeliveryService;
import com.jbk.tool.data.mall.bo.MallFulfillActionBo;
import com.jbk.tool.data.mall.bo.MallFulfillAssignBo;
import com.jbk.tool.data.mall.vo.MallCourierCandidateVo;
import com.jbk.tool.data.mall.vo.MallFulfillVo;
import com.jbk.tool.data.mall.vo.MallLogisticsProviderVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
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
 * 商城履约（E2E-09 S3，PC 前置仓与分配）。
 *
 * <p>只开放履约动作，不开放订单改状态：订单状态由履约动作在服务内同事务推进，
 * 单独提供改单入口等于给了一条绕过库存与资金证据的后门。</p>
 *
 * <p>签收刻意不在 PC 开放——签收是用户的动作，后台代签会让「用户已确认收货」这句话失去意义。</p>
 *
 * <p>任务生成也不在 PC 开放：它只由 {@code MallFulfillmentDispatchWorker} 幂等补齐。
 * 人工生成端点会成为绕过仓库归属的详情读取出口——凭订单号即可拿到任务信息。</p>
 *
 * <p><b>权限分档</b>：拣货与打包（{@code mall:fulfillment:handle}）是仓内作业，
 * 分配配送员与创建第三方运单（{@code mall:fulfillment:dispatch}）决定这批货交给谁、
 * 并同事务冻结承运渠道——两者授权范围不同，不能合成一个权限点。此前四个写端点
 * 只校验登录，服务层的前置仓归属校验只挡住「不是我的仓」，挡不住「不该做这个动作的人」。
 * 权限码与菜单功能点 {@code MENU_API_PERMS} 必须逐字一致。</p>
 *
 * @author dakang
 * @since 2026-08-09
 */
@RestController
@RequestMapping("/mall/fulfillment")
@Tag(name = "商城履约")
@RequiredArgsConstructor
public class MallFulfillmentController {

    private final IMallFulfillmentService fulfillmentService;
    private final IMallSelfDeliveryService selfDeliveryService;
    private final IMallLogisticsService logisticsService;
    private final IMallShipmentService shipmentService;

    @PostMapping("/detail")
    @Operation(summary = "商城履约详情（含完整时间线，只读）")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<MallFulfillVo> detail(@Validated @RequestBody MallFulfillActionBo bo) {
        return R.ok(fulfillmentService.detailForManage(
                StpKit.MANAGE.getLoginIdAsLong(), bo.getOrderNo()));
    }

    @PostMapping("/pick")
    @Operation(summary = "前置仓拣货（订单转履约中）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "mall:fulfillment:handle", type = StpKit.DRIVER_MANAGE)}
    )
    @LogOperation
    public R<MallFulfillVo> pick(@Validated @RequestBody MallFulfillActionBo bo) {
        return R.ok(fulfillmentService.pick(StpKit.MANAGE.getLoginIdAsLong(), bo));
    }

    @PostMapping("/pack")
    @Operation(summary = "前置仓打包完成")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "mall:fulfillment:handle", type = StpKit.DRIVER_MANAGE)}
    )
    @LogOperation
    public R<MallFulfillVo> pack(@Validated @RequestBody MallFulfillActionBo bo) {
        return R.ok(fulfillmentService.pack(StpKit.MANAGE.getLoginIdAsLong(), bo));
    }

    @PostMapping("/courier-candidates")
    @Operation(summary = "可分配配送员候选（绑定生效+准入启用+非下单本人）")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<List<MallCourierCandidateVo>> courierCandidates(
            @Validated @RequestBody MallFulfillActionBo bo) {
        return R.ok(selfDeliveryService.courierCandidates(
                StpKit.MANAGE.getLoginIdAsLong(), bo.getOrderNo()));
    }

    @PostMapping("/assign")
    @Operation(summary = "分配自营配送员（同事务冻结渠道为自营，与创建运单互斥）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "mall:fulfillment:dispatch", type = StpKit.DRIVER_MANAGE)}
    )
    @LogOperation
    public R<MallFulfillVo> assign(@Validated @RequestBody MallFulfillAssignBo bo) {
        return R.ok(selfDeliveryService.assign(StpKit.MANAGE.getLoginIdAsLong(), bo));
    }

    @PostMapping("/shipment/providers")
    @Operation(summary = "已注册承运商（取自实际装配的适配器，不是字典）")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<List<MallLogisticsProviderVo>> providers() {
        return R.ok(logisticsService.providers());
    }

    @PostMapping("/shipment/create")
    @Operation(summary = "创建第三方运单（同事务冻结渠道为第三方，与分配配送员互斥）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "mall:fulfillment:dispatch", type = StpKit.DRIVER_MANAGE)}
    )
    @LogOperation
    public R<MallFulfillVo> createShipment(@Validated @RequestBody MallShipmentCreateBo bo) {
        return R.ok(logisticsService.createShipment(StpKit.MANAGE.getLoginIdAsLong(), bo));
    }

    @PostMapping("/shipment/list")
    @Operation(summary = "订单出库包裹（含第三方物流轨迹，只读）")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<List<MallShipmentVo>> shipments(@Validated @RequestBody MallFulfillActionBo bo) {
        // 先过仓归属再列包裹：包裹带收货信息，绕过归属就等于开了一个按单号查地址的口子
        fulfillmentService.detailForManage(StpKit.MANAGE.getLoginIdAsLong(), bo.getOrderNo());
        return R.ok(shipmentService.listByOrderNo(bo.getOrderNo()));
    }
}

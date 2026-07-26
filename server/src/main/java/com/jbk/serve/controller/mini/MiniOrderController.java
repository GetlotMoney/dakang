package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mini.IMiniOrderService;
import com.jbk.serve.service.mini.IMiniPayStatusService;
import com.jbk.serve.service.mini.IMiniRechargeService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mini.bo.MiniPayStatusBo;
import com.jbk.tool.data.mini.bo.MiniRechargeCreateBo;
import com.jbk.tool.data.mini.vo.MiniPayStatusVo;
import com.jbk.tool.data.mini.vo.MiniRechargeOrderVo;
import com.jbk.tool.data.trade.bo.CreateWaterOrderBo;
import com.jbk.tool.data.trade.bo.MiniOrderDetailBo;
import com.jbk.tool.data.trade.bo.MiniOrderQueryBo;
import com.jbk.tool.data.trade.vo.OrderDetailVo;
import com.jbk.tool.data.trade.vo.OrderItemVo;
import com.jbk.tool.domain.R;

import jakarta.validation.Valid;
import com.jbk.tool.utils.satoken.StpKit;
import com.jbk.tool.validator.group.PageGroup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序订单（L1b 扫码取水下单：创建+分页+详情）。
 * <p>每接口经 {@link MySaCheckOr} 强制 KH_USER 登录；下单人/查询范围一律取会话，禁收前端 userId（铁律6）。</p>
 *
 * @author dakang
 * @since 2026-07-19
 */
@Tag(name = "MINI-订单")
@Validated
@RestController
@RequestMapping("/mini/order")
public class MiniOrderController {

    @Autowired
    private IMiniOrderService miniOrderService;

    @Autowired
    private IMiniRechargeService miniRechargeService;

    @Autowired
    private IMiniPayStatusService miniPayStatusService;

    @PostMapping("/recharge/create")
    @Operation(summary = "创建充值订单（L2-ORDER：只落待支付 order+payment，不模拟支付成功）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniRechargeOrderVo> rechargeCreate(@RequestBody @Valid MiniRechargeCreateBo bo) {
        // 铁律6：userId 只从会话取；金额与支付来源均由服务端决定，不接受前端传入。
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniRechargeService.create(bo, userId));
    }

    @PostMapping("/pay-status")
    @Operation(summary = "查询充值订单支付状态（精确状态矩阵，任一错位 fail-closed）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<MiniPayStatusVo> payStatus(@RequestBody @Valid MiniPayStatusBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniPayStatusService.query(bo, userId));
    }

    @PostMapping("/water/create")
    @Operation(summary = "创建扫码取水订单（余额/水量支付即时扣款，幂等）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<OrderDetailVo> createWaterOrder(@RequestBody @Validated CreateWaterOrderBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniOrderService.createWaterOrder(bo, userId));
    }

    @PostMapping("/page")
    @Operation(summary = "分页查询本人订单")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<PageDataVo<OrderItemVo>> pageMyOrders(@RequestBody @Validated(PageGroup.class) MiniOrderQueryBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniOrderService.pageMyOrders(bo, userId));
    }

    @PostMapping("/detail")
    @Operation(summary = "查询本人订单详情")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_KH_USER) })
    public R<OrderDetailVo> orderDetail(@RequestBody MiniOrderDetailBo bo) {
        Long userId = StpKit.KH_USER.getLoginIdAsLong();
        return R.ok(miniOrderService.getMyOrderDetail(bo, userId));
    }
}

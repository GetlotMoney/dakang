package com.jbk.serve.controller.trade;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.trade.IAdminOrderService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.trade.bo.AdminOrderBo;
import com.jbk.tool.data.trade.vo.AdminOrderItemVo;
import com.jbk.tool.data.trade.vo.AdminOrderTraceVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.PageGroup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单中心（PC 管理端，H2 订单接真）。
 * <p>
 * 只读：分页 + 全链路追溯。均需 DRIVER_MANAGE 会话，管理端可见全量订单
 * （与小程序 MiniOrderController 的 KH_USER 本人范围区开，铁律6）。
 * 读接口沿用项目 station 口径：仅登录校验、不挂功能点（订单为系统/小程序生成，PC 无手工写操作）。
 * </p>
 *
 * @author dakang
 * @since 2026-07-20
 */
@Tag(name = "WS-订单中心")
@Validated
@RestController
@RequestMapping("/order/order")
@RequiredArgsConstructor
public class AdminOrderController {

    private final IAdminOrderService adminOrderService;

    @PostMapping("/page")
    @Operation(summary = "分页查询订单（订单号/类型/状态/用户关键词筛选）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<PageDataVo<AdminOrderItemVo>> page(@RequestBody @Validated(PageGroup.class) AdminOrderBo bo) {
        return R.ok(adminOrderService.pageOrders(bo));
    }

    @PostMapping("/trace")
    @Operation(summary = "订单全链路追溯（订单+指令+流水）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<AdminOrderTraceVo> trace(@RequestBody @Validated(IdGroup.class) AdminOrderBo bo) {
        return R.ok(adminOrderService.getOrderTrace(bo.getId()));
    }
}

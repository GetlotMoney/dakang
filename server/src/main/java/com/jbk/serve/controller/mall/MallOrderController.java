package com.jbk.serve.controller.mall;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mall.IMallOrderService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallOrderActionBo;
import com.jbk.tool.data.mall.bo.MallOrderQueryBo;
import com.jbk.tool.data.mall.vo.MallOrderDetailVo;
import com.jbk.tool.data.mall.vo.MallOrderVo;
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

/**
 * 商城订单台账（E2E-09 S2，PC 只读）。
 *
 * <p>刻意不提供任何写入端点：后台改单会绕过库存动作与支付事实，让订单状态与
 * 库存、资金三者各说各话。需要人工干预时走对账流程，不走改单。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@RestController
@RequestMapping("/mall/order")
@Tag(name = "商城订单台账")
@RequiredArgsConstructor
public class MallOrderController {

    private final IMallOrderService orderService;

    @PostMapping("/page")
    @Operation(summary = "商城订单分页（只读）")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<PageDataVo<MallOrderVo>> page(@RequestBody MallOrderQueryBo bo) {
        return R.ok(orderService.pageForAdmin(bo));
    }

    @PostMapping("/detail")
    @Operation(summary = "商城订单详情（商品/地址/选仓/支付/明细快照，只读）")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<MallOrderDetailVo> detail(@Validated @RequestBody MallOrderActionBo bo) {
        return R.ok(orderService.detailForAdmin(bo.getOrderNo()));
    }
}

package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mall.IMallCartService;
import com.jbk.serve.service.mall.IMallOrderService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallCartSaveBo;
import com.jbk.tool.data.mall.bo.MallCheckoutBo;
import com.jbk.tool.data.mall.bo.MallOrderActionBo;
import com.jbk.tool.data.mall.bo.MallOrderQueryBo;
import com.jbk.tool.data.mall.vo.MallOrderDetailVo;
import com.jbk.tool.data.mall.vo.MallOrderVo;
import com.jbk.tool.data.mall.vo.MiniMallCartVo;
import com.jbk.tool.data.mall.vo.MiniMallCheckoutVo;
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
 * 小程序商城交易（E2E-09 S2）：购物车、结算预览、下单、订单查询与取消。
 *
 * <p>归属用户恒取会话（{@code StpKit.KH_USER.getLoginIdAsLong()}），接口不接受
 * 任何形式的 userId 入参——数据范围在 Service 层强制过滤，不依赖前端传参圈定。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@RestController
@RequestMapping("/mini/mall")
@Tag(name = "小程序商城交易")
@RequiredArgsConstructor
public class MiniMallTradeController {

    private final IMallCartService cartService;
    private final IMallOrderService orderService;

    private static Long userId() {
        return StpKit.KH_USER.getLoginIdAsLong();
    }

    // ==================== 购物车 ====================

    @PostMapping("/cart/list")
    @Operation(summary = "购物车列表（失效行保留并标注原因）")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_KH_USER)})
    public R<MiniMallCartVo> cartList() {
        return R.ok(cartService.list(userId()));
    }

    @PostMapping("/cart/save")
    @Operation(summary = "加购或改量（increment=true 累加，false 覆盖）")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_KH_USER)})
    public R<MiniMallCartVo> cartSave(@Validated @RequestBody MallCartSaveBo bo) {
        return R.ok(cartService.save(userId(), bo));
    }

    @PostMapping("/cart/delete")
    @Operation(summary = "移除购物车行")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_KH_USER)})
    public R<MiniMallCartVo> cartDelete(@RequestBody MallCartSaveBo bo) {
        return R.ok(cartService.remove(userId(), bo.getSkuId()));
    }

    // ==================== 结算与下单 ====================

    @PostMapping("/checkout/preview")
    @Operation(summary = "结算预览（只读试算，不预占库存）")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_KH_USER)})
    public R<MiniMallCheckoutVo> checkoutPreview(@Validated @RequestBody MallCheckoutBo bo) {
        return R.ok(orderService.preview(userId(), bo));
    }

    /**
     * 刻意不加 {@link RepeatSubmit}：创单的幂等由 uk(USER_ID, REQUEST_ID) 提供跨进程、
     * 跨时间的持久保证，同号同参重放必须返回原订单。叠加短时防重切面会让合法的网络重试
     * 在进入领域幂等核验前就被拒绝，用户看到的是"请勿重复提交"而不是他那张已经创建好的订单。
     */
    @PostMapping("/order/create")
    @Operation(summary = "创建订单（选仓+原子预占；同请求号重放返回原订单）")
    @LogOperation
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_KH_USER)})
    public R<MallOrderVo> orderCreate(@Validated @RequestBody MallCheckoutBo bo) {
        return R.ok(orderService.create(userId(), bo));
    }

    @PostMapping("/order/page")
    @Operation(summary = "本人商城订单分页")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_KH_USER)})
    public R<PageDataVo<MallOrderVo>> orderPage(@RequestBody MallOrderQueryBo bo) {
        return R.ok(orderService.pageForUser(userId(), bo));
    }

    @PostMapping("/order/detail")
    @Operation(summary = "本人商城订单详情（明细为下单快照）")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_KH_USER)})
    public R<MallOrderDetailVo> orderDetail(@Validated @RequestBody MallOrderActionBo bo) {
        return R.ok(orderService.detailForUser(userId(), bo.getOrderNo()));
    }

    @PostMapping("/order/cancel")
    @Operation(summary = "取消未支付订单（释放全部预占）")
    @LogOperation
    @RepeatSubmit
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_KH_USER)})
    public R<MallOrderVo> orderCancel(@Validated @RequestBody MallOrderActionBo bo) {
        return R.ok(orderService.cancelByUser(userId(), bo));
    }

    @PostMapping("/order/pay-status")
    @Operation(summary = "查询本人订单支付状态")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_KH_USER)})
    public R<MallOrderDetailVo> orderPayStatus(@Validated @RequestBody MallOrderActionBo bo) {
        return R.ok(orderService.detailForUser(userId(), bo.getOrderNo()));
    }
}

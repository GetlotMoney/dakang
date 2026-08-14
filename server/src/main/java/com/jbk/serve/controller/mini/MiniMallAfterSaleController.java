package com.jbk.serve.controller.mini;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.mall.IMallAfterSaleService;
import com.jbk.tool.data.mall.bo.MallAfterSaleApplyBo;
import com.jbk.tool.data.mall.bo.MallAfterSaleNoBo;
import com.jbk.tool.data.mall.vo.MallAfterSaleVo;
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
 * 小程序商城售后（E2E-09 S4）：申请、撤销、列表与详情。
 *
 * <p>归属恒取会话，接口不接受任何 userId 入参。<b>申请入参里没有金额字段</b>——
 * 应退金额由服务端按原订单不可变明细算出。</p>
 *
 * <p>申请端点不带 HTTP 防重：领域幂等由 uk(USER_ID, REQUEST_ID) 保证，
 * 切面防重会在并发下把合法请求截成「操作太频繁」，反而盖住真实结论。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
@RestController
@RequestMapping("/mini/mall/aftersale")
@Tag(name = "小程序商城售后")
@RequiredArgsConstructor
public class MiniMallAfterSaleController {

    private final IMallAfterSaleService afterSaleService;

    @PostMapping("/apply")
    @Operation(summary = "申请售后（同请求号重放返回原单）")
    @SaCheckLogin(type = StpKit.DRIVER_KH_USER)
    public R<MallAfterSaleVo> apply(@Validated @RequestBody MallAfterSaleApplyBo bo) {
        return R.ok(afterSaleService.apply(StpKit.KH_USER.getLoginIdAsLong(), bo));
    }

    @PostMapping("/cancel")
    @Operation(summary = "撤销售后申请（仅待审核可撤）")
    @SaCheckLogin(type = StpKit.DRIVER_KH_USER)
    public R<MallAfterSaleVo> cancel(@Validated @RequestBody MallAfterSaleNoBo bo) {
        return R.ok(afterSaleService.cancelByUser(StpKit.KH_USER.getLoginIdAsLong(), bo));
    }

    @PostMapping("/list")
    @Operation(summary = "本人售后列表")
    @SaCheckLogin(type = StpKit.DRIVER_KH_USER)
    public R<List<MallAfterSaleVo>> list() {
        return R.ok(afterSaleService.listForUser(StpKit.KH_USER.getLoginIdAsLong()));
    }

    @PostMapping("/detail")
    @Operation(summary = "本人售后详情（含时间线）")
    @SaCheckLogin(type = StpKit.DRIVER_KH_USER)
    public R<MallAfterSaleVo> detail(@Validated @RequestBody MallAfterSaleNoBo bo) {
        return R.ok(afterSaleService.detailForUser(
                StpKit.KH_USER.getLoginIdAsLong(), bo.getAfterSaleNo()));
    }
}

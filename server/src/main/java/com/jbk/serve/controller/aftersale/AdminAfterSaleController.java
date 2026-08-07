package com.jbk.serve.controller.aftersale;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.aftersale.IAdminAfterSaleService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.aftersale.bo.AdminAfterSaleActionBo;
import com.jbk.tool.data.aftersale.bo.AfterSaleExecuteBo;
import com.jbk.tool.data.aftersale.bo.WaterAbnormalReconcileBo;
import com.jbk.tool.data.aftersale.vo.AdminAfterSaleActionItemVo;
import com.jbk.tool.data.aftersale.vo.AdminRechargeRefundPreviewVo;
import com.jbk.tool.data.aftersale.vo.AdminWaterAbnormalPreviewVo;
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
 * 售后退款与补偿（PC 管理端，E2E-04 包A）。
 *
 * <p>查询只读，走 {@code order:aftersale:query}；执行与核账确认是写操作，走
 * {@code order:aftersale:handle} + {@link LogOperation} 操作审计 + {@link RepeatSubmit} 防重复提交。
 * 两个权限码由迁移 {@code 2026-07-29-aftersale-e2e04-a.sql} 落库（菜单 1139/1140），
 * 与此处的 {@code @SaCheckPermission} 逐字一致——不一致时接口恒 403 而菜单照常显示。</p>
 *
 * <p><b>请求体里没有任何金额、水量、目标状态字段。</b>前端只提交定位键与说明：
 * 四元额度在登记时按订单快照算定并冻结，核账终态在事务内按账本重算，
 * 连预览给出的建议值都不作数。这是本模块的接口面约束，不是实现细节。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
@Tag(name = "WS-订单中心-售后处理")
@Validated
@RestController
@RequestMapping("/order/after-sale")
@RequiredArgsConstructor
public class AdminAfterSaleController {

    private final IAdminAfterSaleService adminAfterSaleService;

    @PostMapping("/page")
    @Operation(summary = "分页查询售后台账（来源/类型/状态/关键字筛选，手机号脱敏下发）")
    @MySaCheckOr(
            login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) },
            permission = { @SaCheckPermission(value = "order:aftersale:query", type = StpKit.DRIVER_MANAGE) })
    public R<PageDataVo<AdminAfterSaleActionItemVo>> page(
            @RequestBody @Validated(PageGroup.class) AdminAfterSaleActionBo bo) {
        return R.ok(adminAfterSaleService.pageActions(bo));
    }

    @PostMapping("/detail")
    @Operation(summary = "售后台账详情（四元额度、执行状态、失败原因与重试排期）")
    @MySaCheckOr(
            login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) },
            permission = { @SaCheckPermission(value = "order:aftersale:query", type = StpKit.DRIVER_MANAGE) })
    public R<AdminAfterSaleActionItemVo> detail(
            @RequestBody @Validated(IdGroup.class) AdminAfterSaleActionBo bo) {
        return R.ok(adminAfterSaleService.detail(bo.getId()));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/execute")
    @Operation(summary = "执行售后返还（认领→资金写入→失败落痕，三步独立事务由服务层编排）")
    @MySaCheckOr(
            login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) },
            permission = { @SaCheckPermission(value = "order:aftersale:handle", type = StpKit.DRIVER_MANAGE) })
    public R<Boolean> execute(@RequestBody @Validated AfterSaleExecuteBo bo) {
        // 操作人取当前后台会话员工ID（UPDATE_BY 与审计归属），不信任请求体自报身份
        Long adminUserId = StpKit.MANAGE.getLoginIdAsLong();
        adminAfterSaleService.execute(bo, adminUserId);
        return R.ok(Boolean.TRUE);
    }

    @PostMapping("/water/preview")
    @Operation(summary = "取水异常核账依据预览（只读；不可确认时给出精确阻断原因）")
    @MySaCheckOr(
            login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) },
            permission = { @SaCheckPermission(value = "order:aftersale:query", type = StpKit.DRIVER_MANAGE) })
    public R<AdminWaterAbnormalPreviewVo> waterPreview(
            @RequestBody @Validated(IdGroup.class) WaterAbnormalReconcileBo bo) {
        return R.ok(adminAfterSaleService.previewWaterAbnormal(bo.getOrderId()));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/water/confirm")
    @Operation(summary = "确认取水异常核账并推进订单终态（零资金写入，终态由服务端重算）")
    @MySaCheckOr(
            login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) },
            permission = { @SaCheckPermission(value = "order:aftersale:handle", type = StpKit.DRIVER_MANAGE) })
    public R<Boolean> waterConfirm(@RequestBody @Validated WaterAbnormalReconcileBo bo) {
        Long adminUserId = StpKit.MANAGE.getLoginIdAsLong();
        adminAfterSaleService.confirmWaterAbnormal(bo.getOrderId(), bo.getHandleRemark(), adminUserId);
        return R.ok(Boolean.TRUE);
    }

    @PostMapping("/refund/request")
    @RepeatSubmit
    @Operation(summary = "发起外部退款（Refund-Sim / 未来微信）；只受理，不判定成功")
    @LogOperation
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) },
            permission = { @SaCheckPermission(value = "order:aftersale:refund", type = StpKit.DRIVER_MANAGE) })
    public R<Long> requestRefund(@RequestBody @Validated AfterSaleExecuteBo bo) {
        return R.ok(adminAfterSaleService.requestRefund(bo, StpKit.MANAGE.getLoginIdAsLong()));
    }

    @PostMapping("/refund/recharge/preview")
    @Operation(summary = "充值退款依据预览（只读：可退金额、冲减权益、卡是否会注销；不可退时给出阻断原因）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) },
            permission = { @SaCheckPermission(value = "order:aftersale:refund", type = StpKit.DRIVER_MANAGE) })
    public R<AdminRechargeRefundPreviewVo> previewRechargeRefund(
            @RequestBody @Validated(IdGroup.class) WaterAbnormalReconcileBo bo) {
        // 预览也走财务权限：它下发的是可退金额，与「能不能看台账」不是同一件事
        return R.ok(adminAfterSaleService.previewRechargeRefund(bo.getOrderId()));
    }

    @PostMapping("/refund/recharge")
    @RepeatSubmit
    @Operation(summary = "受理并发起已入账充值/购卡退款（金额由服务端按权益批次折算，请求体只给订单ID）")
    @LogOperation
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) },
            permission = { @SaCheckPermission(value = "order:aftersale:refund", type = StpKit.DRIVER_MANAGE) })
    public R<Long> requestRechargeRefund(@RequestBody @Validated WaterAbnormalReconcileBo bo) {
        // 复用 WaterAbnormalReconcileBo 的 orderId 定位字段：本接口同样「只提供订单ID」，
        // 不新增一个只有一个字段的 Bo。金额、终态、卡处置一律服务端算定。
        // 权限码与执行/核账刻意不同（order:aftersale:refund）：R0-7 要求购卡充值退款
        // 与外部退款使用独立财务审核权限，不能与配送售后的日常处理权共用一个开关。
        return R.ok(adminAfterSaleService.requestRechargeRefund(
                bo.getOrderId(), bo.getHandleRemark(), StpKit.MANAGE.getLoginIdAsLong()));
    }

    @PostMapping("/refund/recharge/unsettled")
    @RepeatSubmit
    @Operation(summary = "受理并发起已付款未入账异常充值单全额退款（请求体只含订单ID与说明）")
    @LogOperation
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) },
            permission = { @SaCheckPermission(value = "order:aftersale:refund", type = StpKit.DRIVER_MANAGE) })
    public R<Long> requestUnsettledRechargeRefund(@RequestBody @Validated WaterAbnormalReconcileBo bo) {
        return R.ok(adminAfterSaleService.requestUnsettledRechargeRefund(
                bo.getOrderId(), bo.getHandleRemark(), StpKit.MANAGE.getLoginIdAsLong()));
    }

    @PostMapping("/resend/generate")
    @RepeatSubmit
    @Operation(summary = "生成补送子订单与任务；生成后售后动作仍为待执行，签收才完成")
    @LogOperation
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) },
            permission = { @SaCheckPermission(value = "order:aftersale:handle", type = StpKit.DRIVER_MANAGE) })
    public R<Long> generateResend(@RequestBody @Validated AfterSaleExecuteBo bo) {
        return R.ok(adminAfterSaleService.generateResend(bo, StpKit.MANAGE.getLoginIdAsLong()));
    }
}

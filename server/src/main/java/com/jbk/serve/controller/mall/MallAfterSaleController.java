package com.jbk.serve.controller.mall;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.mall.IMallAfterSaleService;
import com.jbk.serve.service.mall.IMallRefundSimService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.mall.bo.MallAfterSaleAbortBo;
import com.jbk.tool.data.mall.bo.MallAfterSaleAuditBo;
import com.jbk.tool.data.mall.bo.MallAfterSaleInspectBo;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallAfterSaleNoBo;
import com.jbk.tool.data.mall.bo.MallAfterSaleQueryBo;
import com.jbk.tool.data.mall.vo.MallAfterSaleVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商城售后（E2E-09 S4，PC 审核与执行）。
 *
 * <p>只开放审核、收货、质检三个受控动作。<b>没有任意金额输入、没有库存终值输入、
 * 也没有绕过退款事实的「直接成功」按钮</b>——那三样每一样都能让后台凭一次点击造出
 * 一笔账面上无法追溯的资金或库存变化。</p>
 *
 * <p>模拟退款端点只在 {@code mall.refund-sim.enabled=true} 时可用；关闭时调用直接拒绝，
 * 不回退成「假装退款成功」。</p>
 *
 * <p><b>权限分档</b>：审核（{@code mall:aftersale:audit}）决定这笔退款要不要发生，
 * 收货/质检/中止（{@code mall:aftersale:handle}）决定退多少与货回不回库，
 * 模拟退款（{@code mall:aftersale:refund}）直接造收款事实——三件事风险不同，
 * 授权也必须能分开给。此前五个写端点只校验登录，任何后台账号都能一路走完。
 * 权限码与菜单功能点 {@code MENU_API_PERMS} 必须逐字一致，否则接口恒 403 而按钮照常显示。</p>
 *
 * <p>只读端点仍只校验登录：可见范围已由服务层按操作员前置仓归属收口，读侧权限点
 * 与商城其余只读面一并另行收敛。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
@RestController
@RequestMapping("/mall/aftersale")
@Tag(name = "商城售后")
@RequiredArgsConstructor
public class MallAfterSaleController {

    private final IMallAfterSaleService afterSaleService;
    /** Refund-Sim 未启用时该 Bean 不存在：用 Provider 承接，调用时才 fail-closed 拒绝。 */
    private final ObjectProvider<IMallRefundSimService> refundSimProvider;

    @PostMapping("/page")
    @Operation(summary = "商城售后台账分页（按操作员归属仓过滤）")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<PageDataVo<MallAfterSaleVo>> page(@RequestBody MallAfterSaleQueryBo bo) {
        return R.ok(afterSaleService.pageForManage(StpKit.MANAGE.getLoginIdAsLong(), bo));
    }

    @PostMapping("/detail")
    @Operation(summary = "商城售后详情（含时间线，只读）")
    @SaCheckLogin(type = StpKit.DRIVER_MANAGE)
    public R<MallAfterSaleVo> detail(@Validated @RequestBody MallAfterSaleNoBo bo) {
        return R.ok(afterSaleService.detailForManage(
                StpKit.MANAGE.getLoginIdAsLong(), bo.getAfterSaleNo()));
    }

    @PostMapping("/audit")
    @Operation(summary = "售后审核（通过/驳回）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "mall:aftersale:audit", type = StpKit.DRIVER_MANAGE)}
    )
    @LogOperation
    public R<MallAfterSaleVo> audit(@Validated @RequestBody MallAfterSaleAuditBo bo) {
        return R.ok(afterSaleService.audit(StpKit.MANAGE.getLoginIdAsLong(), bo));
    }

    @PostMapping("/receive")
    @Operation(summary = "确认收到退货")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "mall:aftersale:handle", type = StpKit.DRIVER_MANAGE)}
    )
    @LogOperation
    public R<MallAfterSaleVo> receive(@Validated @RequestBody MallAfterSaleNoBo bo) {
        return R.ok(afterSaleService.confirmReceive(StpKit.MANAGE.getLoginIdAsLong(), bo));
    }

    @PostMapping("/inspect")
    @Operation(summary = "退货质检（结论决定退款与回库）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "mall:aftersale:handle", type = StpKit.DRIVER_MANAGE)}
    )
    @LogOperation
    public R<MallAfterSaleVo> inspect(@Validated @RequestBody MallAfterSaleInspectBo bo) {
        return R.ok(afterSaleService.inspect(StpKit.MANAGE.getLoginIdAsLong(), bo));
    }

    @PostMapping("/exchange-abort")
    @Operation(summary = "中止换货补发（释放预占并转人工）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "mall:aftersale:handle", type = StpKit.DRIVER_MANAGE)}
    )
    @LogOperation
    public R<MallAfterSaleVo> abortExchange(@Validated @RequestBody MallAfterSaleAbortBo bo) {
        return R.ok(afterSaleService.abortExchange(StpKit.MANAGE.getLoginIdAsLong(), bo));
    }

    @PostMapping("/refund-sim")
    @Operation(summary = "发起模拟退款（仅隔离环境）")
    @MySaCheckOr(
            login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)},
            permission = {@SaCheckPermission(value = "mall:aftersale:refund", type = StpKit.DRIVER_MANAGE)}
    )
    @LogOperation
    public R<MallAfterSaleVo> refundSim(@Validated @RequestBody MallAfterSaleNoBo bo) {
        Long operatorId = StpKit.MANAGE.getLoginIdAsLong();
        IMallRefundSimService refundSim = refundSimProvider.getIfAvailable();
        if (refundSim == null) {
            // 关闭时明确拒绝，绝不回退成「假装退成功」
            return R.error("模拟退款未启用");
        }
        refundSim.refund(operatorId, bo.getAfterSaleNo());
        return R.ok(afterSaleService.detailForManage(operatorId, bo.getAfterSaleNo()));
    }
}

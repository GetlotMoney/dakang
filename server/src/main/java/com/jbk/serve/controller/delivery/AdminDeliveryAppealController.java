package com.jbk.serve.controller.delivery;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.delivery.IAdminDeliveryService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.delivery.bo.AdminDeliveryAppealBo;
import com.jbk.tool.data.delivery.bo.DeliveryAppealDecideBo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryAppealEvidenceVo;
import com.jbk.tool.data.delivery.vo.AdminDeliveryAppealItemVo;
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
 * 配送申诉处理（PC 管理端，E2E-03 包C）。
 * <p>
 * 查询只读（DRIVER_MANAGE 登录）；裁决为本模块唯一写操作：
 * {@code order:appeal:handle} 功能点（03-demo-baseline 菜单 1133）+
 * {@link LogOperation} 操作审计 + {@link RepeatSubmit} 防重复提交，
 * 事务完全委托包A 裁决服务（只允许 3不成立驳回/5补送待执行/2成立待补偿，
 * 绝不写退款——资金补偿只落待处理态，真实退款属 E2E-04）。
 * </p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Tag(name = "WS-订单中心-申诉处理")
@Validated
@RestController
@RequestMapping("/order/appeal")
@RequiredArgsConstructor
public class AdminDeliveryAppealController {

    private final IAdminDeliveryService adminDeliveryService;

    @PostMapping("/page")
    @Operation(summary = "分页查询申诉（状态/订单号筛选，待处理排前）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<PageDataVo<AdminDeliveryAppealItemVo>> page(
            @RequestBody @Validated(PageGroup.class) AdminDeliveryAppealBo bo) {
        return R.ok(adminDeliveryService.pageAppeals(bo));
    }

    @PostMapping("/evidence")
    @Operation(summary = "申诉证据详情（用户举证 + 配送员举证 + 关联履约任务，共键 fail-closed）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<AdminDeliveryAppealEvidenceVo> evidence(
            @RequestBody @Validated(IdGroup.class) AdminDeliveryAppealBo bo) {
        return R.ok(adminDeliveryService.appealEvidence(bo.getId()));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/decide")
    @Operation(summary = "裁决提交（仅 3不成立驳回/5补送待执行/2成立待补偿；委托包A 裁决事务）")
    @MySaCheckOr(
            login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) },
            permission = { @SaCheckPermission(value = "order:appeal:handle", type = StpKit.DRIVER_MANAGE) })
    public R<Boolean> decide(@RequestBody @Validated DeliveryAppealDecideBo bo) {
        // 处理人取当前后台会话员工ID（HANDLE_BY 审计归属），不信任请求体自报身份
        Long adminUserId = StpKit.MANAGE.getLoginIdAsLong();
        return R.ok(adminDeliveryService.decideAppeal(bo, adminUserId));
    }
}

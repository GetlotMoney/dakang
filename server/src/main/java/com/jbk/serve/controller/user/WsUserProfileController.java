package com.jbk.serve.controller.user;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.user.IWsUserProfileService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.user.bo.WsUserProfileBo;
import com.jbk.tool.data.user.vo.WsUserAuditVo;
import com.jbk.tool.data.user.vo.WsUserFlowVo;
import com.jbk.tool.data.user.vo.WsUserOrderVo;
import com.jbk.tool.data.user.vo.WsUserRelationItemVo;
import com.jbk.tool.data.user.vo.WsUserRelationVo;
import com.jbk.tool.data.user.vo.WsUserVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import com.jbk.tool.validator.group.PageGroup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户档案（后台一人一档只读聚合面）。
 *
 * <p>六个分区各自一个只读端点，各自分页；全控制器**无任何写端点**——余额、水量、状态、
 * 关系在这里都只能被看见，不能被改。想改这些事实必须走各自领域已有的受控写入口。</p>
 *
 * <p>权限口径：登录之外再要求 {@code user:user:query}。用户档案会集中呈现一个人的号码、
 * 资产与交易痕迹，「能打开页面」和「能读到具体某个人」必须可以分别授权，
 * 不能靠前端把按钮藏起来当作防线。</p>
 *
 * @author dakang
 * @since 2026-08-13
 */
@Tag(name = "WS-用户档案")
@Validated
@RestController
@RequestMapping("/user/profile")
@RequiredArgsConstructor
public class WsUserProfileController {

    private final IWsUserProfileService userProfileService;

    @PostMapping("/identity")
    @Operation(summary = "身份资料（手机号脱敏）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) }, permission = {
            @SaCheckPermission(value = "user:user:query", type = StpKit.DRIVER_MANAGE) })
    public R<WsUserVo> identity(@RequestBody @Valid WsUserProfileBo bo) {
        return R.ok(userProfileService.getIdentity(bo.getUserId()));
    }

    @PostMapping("/orderPage")
    @Operation(summary = "订单分页")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) }, permission = {
            @SaCheckPermission(value = "user:user:query", type = StpKit.DRIVER_MANAGE) })
    public R<PageDataVo<WsUserOrderVo>> orderPage(
            @RequestBody @Validated(PageGroup.class) WsUserProfileBo bo) {
        return R.ok(userProfileService.pageOrders(bo));
    }

    @PostMapping("/flowPage")
    @Operation(summary = "资金流水分页（只读事实）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) }, permission = {
            @SaCheckPermission(value = "user:user:query", type = StpKit.DRIVER_MANAGE) })
    public R<PageDataVo<WsUserFlowVo>> flowPage(
            @RequestBody @Validated(PageGroup.class) WsUserProfileBo bo) {
        return R.ok(userProfileService.pageFlows(bo));
    }

    @PostMapping("/relation")
    @Operation(summary = "关系归属（上一级邀请人 + 直接下级计数）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) }, permission = {
            @SaCheckPermission(value = "user:user:query", type = StpKit.DRIVER_MANAGE) })
    public R<WsUserRelationVo> relation(@RequestBody @Valid WsUserProfileBo bo) {
        return R.ok(userProfileService.getRelation(bo.getUserId()));
    }

    @PostMapping("/inviteePage")
    @Operation(summary = "直接下级分页（仅一级）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) }, permission = {
            @SaCheckPermission(value = "user:user:query", type = StpKit.DRIVER_MANAGE) })
    public R<PageDataVo<WsUserRelationItemVo>> inviteePage(
            @RequestBody @Validated(PageGroup.class) WsUserProfileBo bo) {
        return R.ok(userProfileService.pageInvitees(bo));
    }

    @PostMapping("/auditPage")
    @Operation(summary = "审计记录分页")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) }, permission = {
            @SaCheckPermission(value = "user:user:query", type = StpKit.DRIVER_MANAGE) })
    public R<PageDataVo<WsUserAuditVo>> auditPage(
            @RequestBody @Validated(PageGroup.class) WsUserProfileBo bo) {
        return R.ok(userProfileService.pageAudits(bo));
    }
}

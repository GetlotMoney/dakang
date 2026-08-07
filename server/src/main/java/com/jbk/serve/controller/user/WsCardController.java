package com.jbk.serve.controller.user;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jbk.serve.service.user.IWsCardService;
import com.jbk.tool.annotation.LogOperation;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.annotation.RepeatSubmit;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.user.bo.WsCardBo;
import com.jbk.tool.data.user.vo.WsCardVo;
import com.jbk.tool.domain.R;
import com.jbk.tool.utils.satoken.StpKit;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.PageGroup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 水卡管理（后台，一期口径：只读 + 冻结/解冻）
 * <p>
 * 开卡/充值/迁移属商业一期（REQ-078 高风险资金操作需财务审核），本控制器不提供。
 * </p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Tag(name = "WS-水卡管理")
@Validated
@RestController
@RequestMapping("/user/card")
public class WsCardController {

    @Autowired
    private IWsCardService cardService;
    @Autowired
    private com.jbk.serve.service.settlement.IGiftCardService giftCardService;

    @PostMapping("/page")
    @Operation(summary = "分页查询（卡号/类型/状态/持卡用户筛选）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<PageDataVo<WsCardVo>> page(
            @RequestBody @Validated(PageGroup.class) WsCardBo bo) {
        return R.ok(cardService.pageData(bo));
    }

    @PostMapping("/detail")
    @Operation(summary = "详情（含授权成员列表）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<WsCardVo> detail(
            @RequestBody @Validated(IdGroup.class) WsCardBo bo) {
        return R.ok(cardService.getData(bo.getId()));
    }

    @PostMapping("/listByUser")
    @Operation(summary = "按用户查卡（用户详情抽屉聚合）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) })
    public R<List<WsCardVo>> listByUser(@RequestBody WsCardBo bo) {
        return R.ok(cardService.listByUser(bo.getUserId()));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/changeStatus")
    @Operation(summary = "冻结/解冻（高风险：卡状态影响刷卡授权）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) }, permission = {
            @SaCheckPermission(value = "user:card:status", type = StpKit.DRIVER_MANAGE) })
    public R<Boolean> changeStatus(
            @RequestBody @Validated(IdGroup.class) WsCardBo bo) {
        return R.ok(cardService.changeStatus(bo));
    }

    @LogOperation
    @RepeatSubmit
    @PostMapping("/issueGift")
    @Operation(summary = "运营赠卡发放（高风险：人工权益发放；D-213 口径带有效期不可充值，请求号幂等）")
    @MySaCheckOr(login = { @SaCheckLogin(type = StpKit.DRIVER_MANAGE) }, permission = {
            @SaCheckPermission(value = "user:card:issue", type = StpKit.DRIVER_MANAGE) })
    public R<Long> issueGift(
            @RequestBody @jakarta.validation.Valid com.jbk.tool.data.settlement.bo.GiftIssueBo bo) {
        return R.ok(giftCardService.issue(bo, StpKit.MANAGE.getLoginIdAsLong()));
    }
}

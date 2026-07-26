package com.jbk.serve.controller.user;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.jbk.serve.service.user.IWsUserService;
import com.jbk.tool.annotation.MySaCheckOr;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.user.bo.WsUserBo;
import com.jbk.tool.data.user.vo.WsUserVo;
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

/**
 * 用户管理（后台，C 端用户 ws_user）
 * <p>REQ-018 一期口径：mock 用户与只读信息，后台不提供用户新增/编辑；
 * 水卡、授权成员、配送员审核随用户管理模块子页扩展。</p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Tag(name = "WS-用户管理")
@Validated
@RestController
@RequestMapping("/user/user")
public class WsUserController {

    @Autowired
    private IWsUserService wsUserService;

    @PostMapping("/page")
    @Operation(summary = "分页查询（按姓名/手机号筛选）")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<PageDataVo<WsUserVo>> page(
            @RequestBody @Validated(PageGroup.class) WsUserBo bo) {
        return R.ok(wsUserService.pageData(bo));
    }

    @PostMapping("/detail")
    @Operation(summary = "详情")
    @MySaCheckOr(login = {@SaCheckLogin(type = StpKit.DRIVER_MANAGE)})
    public R<WsUserVo> detail(
            @RequestBody @Validated(IdGroup.class) WsUserBo bo) {
        return R.ok(wsUserService.getData(bo.getId()));
    }
}
